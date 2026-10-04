package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 从 JSON 对象里按类型取键。类型不对：跳过这个键，记一条说清「哪个文件、哪个键、为什么」的 note，不抛异常。
 * `null` 视为没设置（CC 的 managed 文档里 `null` 就是「没有这个键」），不记 note。
 */
internal class JsonFields(
    private val obj: JsonObject,
    private val file: String,
    private val prefix: String,
    private val notes: MutableList<String>,
) {
    fun string(key: String): String? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else bad(key, "期望字符串", v)
        else -> bad(key, "期望字符串", v)
    }

    fun bool(key: String): Boolean? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> v.takeIf { !it.isString }?.booleanOrNull ?: bad(key, "期望布尔值 true/false", v)
        else -> bad(key, "期望布尔值 true/false", v)
    }

    fun int(key: String, min: Int): Int? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            val n = v.takeIf { !it.isString }?.intOrNull
            when {
                n == null -> bad(key, "期望整数", v)
                n < min -> bad(key, "期望不小于 $min 的整数，实际是 $n", null)
                else -> n
            }
        }
        else -> bad(key, "期望整数", v)
    }

    /** 字符串数组；其中不是字符串（或是空串）的项单独跳过并记 note，其余项照收 */
    fun stringList(key: String): List<String> = stringListOrNull(key).orEmpty()

    /** 同 [stringList]，但区分「没写」（null）与「写了空数组」（空列表）：安全闸要靠后者表达「全拦」 */
    fun stringListOrNull(key: String): List<String>? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonArray -> v.mapIndexedNotNull { index, item ->
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                text == null -> {
                    notes += "跳过 $file 的 `$prefix$key[$index]`：期望字符串，实际是${describe(item)}"
                    null
                }
                text.isBlank() -> {
                    notes += "跳过 $file 的 `$prefix$key[$index]`：空字符串"
                    null
                }
                else -> text.trim()
            }
        }
        else -> {
            bad<Unit>(key, "期望字符串数组", v)
            emptyList()
        }
    }

    fun obj(key: String): JsonObject? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonObject -> v
        else -> bad(key, "期望对象", v)
    }

    /** 值全是字符串的对象（`env`）：不是字符串的条目单独跳过 */
    fun stringMap(key: String): Map<String, String> = entries(key, "字符串") { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    /** 值全是布尔的对象（`enabledPlugins`） */
    fun boolMap(key: String): Map<String, Boolean> = entries(key, "布尔值") { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }

    /** 值只能是字符串 `"disable"` 的锁类键（`disableBypassPermissionsMode`、`disableAutoMode`） */
    fun disableLock(key: String): Boolean = when (val v = obj[key]) {
        null, JsonNull -> false
        is JsonPrimitive -> (v.isString && v.content == "disable").also { ok ->
            if (!ok) notes += "忽略 $file 的 `$prefix$key`：只认字符串 \"disable\"，实际是${describe(v)}"
        }
        else -> {
            bad<Unit>(key, "期望字符串 \"disable\"", v)
            false
        }
    }

    private fun <T : Any> entries(key: String, expected: String, pick: (JsonElement) -> T?): Map<String, T> {
        val o = obj(key) ?: return emptyMap()
        val out = LinkedHashMap<String, T>()
        for ((k, v) in o) {
            if (v == JsonNull) continue
            val value = pick(v)
            if (value == null) notes += "跳过 $file 的 `$prefix$key.$k`：期望$expected，实际是${describe(v)}" else out[k] = value
        }
        return out
    }

    private fun <T> bad(key: String, expected: String, actual: JsonElement?): T? {
        val tail = if (actual == null) "" else "，实际是${describe(actual)}"
        notes += "跳过 $file 的 `$prefix$key`：$expected$tail"
        return null
    }

    companion object {
        fun describe(v: JsonElement): String = when (v) {
            is JsonObject -> "对象"
            is JsonArray -> "数组"
            JsonNull -> "null"
            is JsonPrimitive -> if (v.isString) "字符串 \"${v.content.take(30)}\"" else "${v.content.take(30)}（不是字符串）"
        }
    }
}

/** 一个设置文件的 JSON 文本 → [SettingsLayer]。纯逻辑，不碰文件系统 */
internal object SettingsReader {
    private val json = Json

    /**
     * 解析文本成 JSON 对象。空文件按 `{}`（CC 的 managed 文档：「An empty managed settings file counts as {}」）；
     * 不是合法 JSON 或顶层不是对象：记 note、返回 null（整个文件跳过，与 CC「skip the broken file」一致）。
     */
    fun parseObject(text: String, path: String, notes: MutableList<String>): JsonObject? {
        if (text.isBlank()) return JsonObject(emptyMap())
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            notes += "跳过 $path：不是合法 JSON（${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "解析失败"}）"
            return null
        } catch (e: IllegalArgumentException) {
            notes += "跳过 $path：不是合法 JSON（${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "解析失败"}）"
            return null
        }
        if (element !is JsonObject) {
            notes += "跳过 $path：顶层应是 JSON 对象，实际是${JsonFields.describe(element)}"
            return null
        }
        return element
    }

    fun layer(source: ConfigSource, path: String, baseDir: GuestPath, raw: JsonObject, notes: MutableList<String>): SettingsLayer {
        val f = JsonFields(raw, path, "", notes)
        val p = f.obj("permissions")?.let { JsonFields(it, path, "permissions.", notes) }
        // 文档：`disableAutoMode` 在顶层，也接受写在 `permissions` 下
        val autoLock = f.disableLock("disableAutoMode")
        val permissions = if (p == null) {
            PermissionsSettings(disableAutoMode = autoLock)
        } else {
            PermissionsSettings(
                allow = p.stringList("allow"),
                ask = p.stringList("ask"),
                deny = p.stringList("deny"),
                defaultMode = p.string("defaultMode"),
                additionalDirectories = p.stringList("additionalDirectories"),
                disableBypassPermissionsMode = p.disableLock("disableBypassPermissionsMode"),
                disableAutoMode = p.disableLock("disableAutoMode") || autoLock,
                blockReadsOutsideWorkingDirectories = p.bool("blockReadsOutsideWorkingDirectories"),
            )
        }
        return SettingsLayer(
            source = source,
            path = GuestPath(path),
            baseDir = baseDir,
            raw = raw,
            permissions = permissions,
            env = f.stringMap("env"),
            model = f.string("model"),
            effortLevel = f.string("effortLevel"),
            outputStyle = f.string("outputStyle"),
            agent = f.string("agent"),
            language = f.string("language"),
            alwaysThinkingEnabled = f.bool("alwaysThinkingEnabled"),
            autoMemoryEnabled = f.bool("autoMemoryEnabled"),
            autoMemoryDirectory = f.string("autoMemoryDirectory"),
            claudeMdExcludes = f.stringList("claudeMdExcludes"),
            // 文档：整数，最小 1
            cleanupPeriodDays = f.int("cleanupPeriodDays", min = 1),
            disableAllHooks = f.bool("disableAllHooks"),
            allowManagedHooksOnly = f.bool("allowManagedHooksOnly"),
            allowManagedPermissionRulesOnly = f.bool("allowManagedPermissionRulesOnly"),
            hooks = f.obj("hooks"),
            // http hook 的两道闸（设计 §8 H3）：定义过就按清单限制；空数组也是定义过（拦掉全部），不能折成「没定义」
            allowedHttpHookUrls = f.stringListOrNull("allowedHttpHookUrls"),
            httpHookAllowedEnvVars = f.stringListOrNull("httpHookAllowedEnvVars"),
            enabledPlugins = f.boolMap("enabledPlugins"),
        )
    }
}
