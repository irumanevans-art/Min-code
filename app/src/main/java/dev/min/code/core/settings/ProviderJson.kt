package dev.min.code.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 编辑面板的「JSON 形态」：一条供应商配置和一份规范文本之间的互转。
 *
 * ## 和桌面版 cc-switch 相反的取舍
 *
 * 桌面版里那份 JSON 就是存储本身，结构化的表单是它的视图。这里反过来：**结构化字段是
 * 存储，JSON 是它的双向投影**。理由是这边的事实来源必须能被进程 env 直接读（见
 * `ProviderSync`），存一份自由格式的 JSON 等于把「哪些键真的会生效」这件事变成运行时
 * 才知道的事。
 *
 * 代价是这份文本只认一种形状：
 *
 * ```json
 * { "env": { "ANTHROPIC_BASE_URL": "…", "ANTHROPIC_AUTH_TOKEN": "…", "其它": "…" } }
 * ```
 *
 * 顶层的其它键（从桌面版导出的文件里可能带 `permissions`、`model`）**原样忽略并报数**，
 * 不静默吞掉——用户会以为它们被保存了。
 */

/** token 在掩码模式下的占位符。存回去时看到它就表示「这一栏没动过」 */
internal const val TOKEN_PLACEHOLDER = "••••••••"

private val prettyJson = Json { prettyPrint = true }

/**
 * 渲染成规范文本。
 *
 * 键序固定（地址、token，其余按字典序），这样两次编辑之间的差异是用户自己改的那些，
 * 而不是 map 迭代顺序的抖动。
 *
 * [revealToken] 为 false 时 token 位置渲染成 [TOKEN_PLACEHOLDER]——「看一眼这条配置
 * 长什么样」不该先要求把明文 key 摊在屏幕上。
 */
internal fun profileToJsonText(profile: ApiProfile, revealToken: Boolean): String {
    val env = buildMap {
        put("ANTHROPIC_BASE_URL", profile.baseUrl)
        put(
            "ANTHROPIC_AUTH_TOKEN",
            when {
                profile.token.isBlank() -> ""
                revealToken -> profile.token
                else -> TOKEN_PLACEHOLDER
            },
        )
        sanitizedProfileEnv(profile).toSortedMap().forEach { (k, v) -> put(k, v) }
    }
    val root = JsonObject(mapOf("env" to JsonObject(env.mapValues { JsonPrimitive(it.value) })))
    return prettyJson.encodeToString(JsonObject.serializer(), root)
}

internal sealed interface ProfileJsonParse {
    data class Ok(
        val baseUrl: String,
        /** null = 文本里仍是 [TOKEN_PLACEHOLDER]，保持原来的 token 不动 */
        val token: String?,
        val env: Map<String, String>,
        /** 顶层被忽略的那些键。非空时界面要说出来「这 N 个键没有被保存」 */
        val ignoredKeys: List<String> = emptyList(),
    ) : ProfileJsonParse

    data class Err(val reason: Reason, val detail: String = "") : ProfileJsonParse

    /** 分成几种是为了能翻译：界面按 reason 取文案，[Err.detail] 填出事的那个键名 */
    enum class Reason { NOT_JSON, NOT_AN_OBJECT, ENV_NOT_AN_OBJECT, ENV_VALUE_NOT_A_STRING }
}

/**
 * 解析回来。失败要带**能读的**原因，不能只给一句「JSON 无效」——这是一个手写文本框，
 * 用户需要知道是哪一行错了。
 */
internal fun parseProfileJson(text: String): ProfileJsonParse {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        ?: return ProfileJsonParse.Err(ProfileJsonParse.Reason.NOT_JSON)
    val obj = root as? JsonObject
        ?: return ProfileJsonParse.Err(ProfileJsonParse.Reason.NOT_AN_OBJECT)
    val envElement = obj["env"]
    // 没有 env 这个键算空配置，不算错：用户可能正在从头写
    val env = when {
        envElement == null -> JsonObject(emptyMap())
        envElement is JsonObject -> envElement
        else -> return ProfileJsonParse.Err(ProfileJsonParse.Reason.ENV_NOT_AN_OBJECT)
    }

    val flat = linkedMapOf<String, String>()
    env.forEach { (key, value) ->
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            // 数字和嵌套对象都拒掉：环境变量的值只能是字符串，
            // 悄悄把 30000 转成 "30000" 会让用户以为自己写对了
            return ProfileJsonParse.Err(ProfileJsonParse.Reason.ENV_VALUE_NOT_A_STRING, key)
        }
        flat[key.trim()] = primitive.content
    }

    val rawToken = flat.remove("ANTHROPIC_AUTH_TOKEN").orEmpty()
    return ProfileJsonParse.Ok(
        baseUrl = normalizeBaseUrl(flat.remove("ANTHROPIC_BASE_URL").orEmpty()),
        token = if (rawToken == TOKEN_PLACEHOLDER) null else rawToken.trim(),
        env = sanitizeEnv(flat),
        ignoredKeys = obj.keys.filterNot { it == "env" },
    )
}

/** 把解析结果落回一条配置上。[ProfileJsonParse.Ok.token] 为 null 时保留原 token */
internal fun ApiProfile.withJsonParse(parsed: ProfileJsonParse.Ok): ApiProfile = copy(
    baseUrl = parsed.baseUrl,
    token = parsed.token ?: token,
    env = parsed.env,
    // 地址改了就该重新确认一次明文风险，和 updateProfile 里那条是同一个道理
    insecureAck = insecureAck && parsed.baseUrl == baseUrl,
)
