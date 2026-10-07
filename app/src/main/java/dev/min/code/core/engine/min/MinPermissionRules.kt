package dev.min.code.core.engine.min

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 审批卡「总是允许」写到哪一层（设计 §10 末尾 K14：规则由内核判定、**写哪一层 settings 文件属于宿主**，随审批卡做）。
 *
 * 不给共享的项目层（`.claude/settings.json`）：它多半是提交进仓库的，一次点击就悄悄改一个被别人共用的文件不合适，
 * 需要的话用户自己改；给的三层都是只影响自己的。
 */
enum class RuleLayer(val id: String) {
    /** 只在本会话里有效，不写文件（内核的会话规则） */
    Session("session"),

    /** 本项目、只对自己（`<项目>/.claude/settings.local.json`，CC 的「不再询问」写的同一个文件） */
    Local("local"),

    /** 所有项目（`~/.claude/settings.json`） */
    User("user"),
    ;

    companion object {
        fun of(id: String): RuleLayer? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 「始终允许」选项的 id：`always:<层>:<规则原文>`。规则里有冒号、括号、空格都无所谓——只在第二个冒号处切一次。
 * 不放进通知按钮（`notificationSafe = false`），所以这个 id 只在界面与路由之间走。
 */
object AlwaysOptionIds {
    private const val PREFIX = dev.min.code.core.engine.ApprovalOption.ALWAYS_PREFIX

    fun of(layer: RuleLayer, rule: String): String = "$PREFIX${layer.id}:$rule"

    fun parse(optionId: String): Pair<RuleLayer, String>? {
        if (!optionId.startsWith(PREFIX)) return null
        val rest = optionId.removePrefix(PREFIX)
        val colon = rest.indexOf(':')
        if (colon <= 0 || colon == rest.length - 1) return null
        val layer = RuleLayer.of(rest.substring(0, colon)) ?: return null
        return layer to rest.substring(colon + 1)
    }
}

/** [addAllowRule] 的结果 */
sealed interface AddRule {
    /** 改好的整份文本 */
    data class Updated(val text: String) : AddRule

    /** 规则本来就在 `permissions.allow` 里 */
    data object AlreadyThere : AddRule

    /** 现有文件不是能安全改的形状（不是 JSON 对象、`permissions` / `allow` 类型不对）：**不动它**，原因给人看 */
    data class Refused(val reason: String) : AddRule
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * 把 [rule] 加进 settings 文本的 `permissions.allow`。纯函数：
 * - 文件不存在 / 全是空白 → 新建 `{"permissions":{"allow":[rule]}}`；
 * - 其余的键、键的顺序原样保留（我们只是往数组里追加）；
 * - 已经在里面 → [AddRule.AlreadyThere]，不重写文件；
 * - **文件已经坏了或形状不对就拒绝**，绝不覆盖——那份文件可能是用户手写的全部权限配置，覆盖了他就丢了。
 * 输出两格缩进，与 CC 自己写的 settings 文件同一个样子，两边来回编辑 diff 不吵。
 */
fun addAllowRule(existing: String?, rule: String): AddRule {
    val root: JsonObject = if (existing.isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        val parsed = try {
            Json.parseToJsonElement(existing)
        } catch (e: SerializationException) {
            return AddRule.Refused("现有文件不是合法的 JSON")
        } catch (e: IllegalArgumentException) {
            return AddRule.Refused("现有文件不是合法的 JSON")
        }
        parsed as? JsonObject ?: return AddRule.Refused("现有文件的顶层不是 JSON 对象")
    }
    val permissions: JsonObject = when (val p = root["permissions"]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> p
        else -> return AddRule.Refused("现有文件里的 permissions 不是对象")
    }
    val allow: List<JsonElement> = when (val a = permissions["allow"]) {
        null -> emptyList()
        is JsonArray -> a
        else -> return AddRule.Refused("现有文件里的 permissions.allow 不是数组")
    }
    if (allow.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == rule }) return AddRule.AlreadyThere
    val newPermissions = JsonObject(permissions + ("allow" to JsonArray(allow + JsonPrimitive(rule))))
    val newRoot = JsonObject(root + ("permissions" to newPermissions))
    return AddRule.Updated(prettyJson.encodeToString(JsonElement.serializer(), newRoot) + "\n")
}

/** 各层 settings 文件的 guest 路径。[cwd] 是会话的工作目录，[home] 是 rootfs 里的用户主目录 */
fun settingsFileOf(layer: RuleLayer, cwd: GuestPath, home: GuestPath): GuestPath? = when (layer) {
    RuleLayer.Session -> null
    RuleLayer.Local -> GuestPath(cwd.value.trimEnd('/') + "/.claude/settings.local.json")
    RuleLayer.User -> GuestPath(home.value.trimEnd('/') + "/.claude/settings.json")
}

/** [persistAllowRule] 的结局：给人看的一句话在调用方拼 */
sealed interface PersistRule {
    data object Written : PersistRule
    data object AlreadyThere : PersistRule
    data class Refused(val reason: String) : PersistRule
    data class Failed(val reason: String) : PersistRule
}

/**
 * 读 → 改 → 原子写。[fs] 是宿主的工作区文件系统（和工具读写文件用的同一个，越界一律抛）。
 * 读不到（文件不存在）当空文件；其余异常 [PersistRule.Failed]，取消照常往外传。
 */
suspend fun writeAllowRule(fs: WorkspaceFs, layer: RuleLayer, rule: String, cwd: GuestPath, home: GuestPath): PersistRule {
    val file = settingsFileOf(layer, cwd, home) ?: return PersistRule.Written
    val existing = try {
        if (fs.stat(file) == null) null else fs.read(file).decodeToString()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return PersistRule.Failed(e.message ?: e.javaClass.simpleName)
    }
    return when (val added = addAllowRule(existing, rule)) {
        is AddRule.AlreadyThere -> PersistRule.AlreadyThere
        is AddRule.Refused -> PersistRule.Refused(added.reason)
        is AddRule.Updated -> try {
            fs.writeAtomic(file, added.text.encodeToByteArray())
            PersistRule.Written
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PersistRule.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
