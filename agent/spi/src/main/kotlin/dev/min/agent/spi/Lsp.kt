package dev.min.agent.spi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class LspServerSpec(val name: String, val config: JsonObject) {
    val command: String get() = config.getValue("command").jsonPrimitive.content
    val args: List<String> get() = (config["args"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    val extensions: Map<String, String> get() = config.getValue("extensionToLanguage").jsonObject.mapValues { it.value.jsonPrimitive.content }
    val env: Map<String, String> get() = (config["env"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
    val startupTimeout: Long get() = (config["startupTimeout"] as? JsonPrimitive)?.longOrNull ?: 30_000
    val shutdownTimeout: Long get() = (config["shutdownTimeout"] as? JsonPrimitive)?.longOrNull ?: 2_000
    val requestTimeout: Long? get() = (config["requestTimeout"] as? JsonPrimitive)?.longOrNull
    val diagnostics: Boolean get() = (config["diagnostics"] as? JsonPrimitive)?.booleanOrNull ?: true
    val restartLimit: Int get() = if ((config["restartOnCrash"] as? JsonPrimitive)?.booleanOrNull == false) 0
        else ((config["maxRestarts"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtMost(1)

    companion object {
        val FIELDS = setOf("command", "args", "extensionToLanguage", "transport", "env", "initializationOptions", "settings",
            "workspaceFolder", "startupTimeout", "shutdownTimeout", "requestTimeout", "restartOnCrash", "maxRestarts", "diagnostics")
        fun problem(value: JsonElement): String? {
            val v = value as? JsonObject ?: return "不是对象"
            val unknown = v.keys - FIELDS
            if (unknown.isNotEmpty()) return "未知键 $unknown"
            fun string(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            val command = string(v["command"]) ?: return "缺 command 字符串"
            if (command.isBlank() || !command.startsWith('/') && command.any { it.isWhitespace() }) return "command 不能为空或包含参数；参数放 args"
            val ext = v["extensionToLanguage"] as? JsonObject ?: return "缺 extensionToLanguage 对象"
            if (ext.isEmpty() || ext.any { !it.key.startsWith('.') || string(it.value).isNullOrBlank() }) return "extensionToLanguage 必须非空、键以 . 开头、值为语言字符串"
            if ("args" in v && (v["args"] !is JsonArray || v["args"]!!.jsonArray.any { string(it) == null })) return "args 必须是字符串数组"
            if ("env" in v && (v["env"] !is JsonObject || v["env"]!!.jsonObject.values.any { string(it) == null })) return "env 必须是字符串对象"
            if ("transport" in v && string(v["transport"]) !in setOf("stdio", "socket")) return "transport 必须是 stdio 或 socket"
            if ("workspaceFolder" in v && string(v["workspaceFolder"]) == null) return "workspaceFolder 必须是字符串"
            for (key in listOf("startupTimeout", "shutdownTimeout", "requestTimeout", "maxRestarts")) if (key in v) {
                val p = v[key] as? JsonPrimitive
                val n = p?.takeUnless { it.isString }?.longOrNull
                if (n == null || n < (if (key == "maxRestarts") 0 else 1) || key == "maxRestarts" && n > Int.MAX_VALUE) return "$key 必须是合法整数"
            }
            for (key in listOf("restartOnCrash", "diagnostics")) if (key in v && (v[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) return "$key 必须是布尔值"
            return null
        }
    }
}

data class LspQuery(val operation: String, val path: GuestPath, val line: Int, val character: Int, val query: String? = null)

@Serializable
data class LspDiagnostic(val path: GuestPath, val line: Int, val character: Int, val message: String, val code: String? = null, val source: String? = null)

/** 基线留在端口内部；工具只带回不透明的票据，避免把进程状态写进日志。 */
interface LspEditToken

interface LspPort {
    val available: Boolean
    suspend fun query(input: LspQuery): ToolResult
    suspend fun beforeEdit(path: GuestPath, text: String?): LspEditToken?
    suspend fun afterEdit(path: GuestPath, text: String, token: LspEditToken?): List<LspDiagnostic>

    object Unavailable : LspPort {
        override val available = false
        override suspend fun query(input: LspQuery) = ToolResult(listOf(dev.min.agent.protocol.TextPart("没有配置这个语言的服务器。")), isError = true)
        override suspend fun beforeEdit(path: GuestPath, text: String?) = null
        override suspend fun afterEdit(path: GuestPath, text: String, token: LspEditToken?) = emptyList<LspDiagnostic>()
    }
}
