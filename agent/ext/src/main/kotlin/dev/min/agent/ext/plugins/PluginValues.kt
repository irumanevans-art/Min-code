package dev.min.agent.ext.plugins

import dev.min.agent.spi.McpOAuthCredentials
import dev.min.agent.spi.McpTokenKey
import dev.min.agent.spi.McpTokenStore
import kotlinx.serialization.json.*

/** 复用宿主 MCP 私有仓库：Android 的 Keystore 与桌面 ACL 保持同一道边界，不创建 rootfs 明文文件。 */
class PluginSecretStore(private val tokens: McpTokenStore) {
    private fun key(id: String, option: String) = McpTokenKey("plugin-config:$id", "plugin-config:$option")
    suspend fun load(id: String, option: String): JsonElement? =
        tokens.load(key(id, option))?.accessToken?.let(Json::parseToJsonElement)

    suspend fun save(id: String, option: String, value: JsonElement) {
        tokens.save(key(id, option), McpOAuthCredentials(
            issuer = "plugin-config", authorizationEndpoint = "", tokenEndpoint = "", clientId = "",
            redirectUri = "", accessToken = value.toString(),
        ))
    }
}

/** 运行时值不属于 LoadedPlugin，也不属于事件；默认 toString 不能打印凭证。 */
class PluginValues(val public: JsonObject, private val secrets: Map<String, JsonElement>) {
    fun value(key: String): JsonElement? = secrets[key] ?: public[key]
    fun isSensitive(key: String): Boolean = key in secrets
    override fun toString(): String = "PluginValues(keys=${(public.keys + secrets.keys).sorted()})"

    companion object {
        suspend fun read(plugin: LoadedPlugin, options: JsonObject, store: PluginSecretStore): PluginValues {
            val public = linkedMapOf<String, JsonElement>()
            val secrets = linkedMapOf<String, JsonElement>()
            val definitions = (plugin.userConfig + plugin.channels.flatMap { it.userConfig }).distinctBy { it.key }
            for (def in definitions) {
                if (def.sensitive) {
                    // settings 中旧的敏感字段一律不采纳；只有显式配置入口可以将新值保存到安全仓库。
                    (store.load(plugin.ref.id, def.key) ?: def.default)?.let { secrets[def.key] = it }
                } else (options[def.key] ?: def.default)?.let { public[def.key] = it }
            }
            return PluginValues(JsonObject(public), secrets)
        }
    }
}

data class PluginVars(val root: String, val data: String, val project: String) {
    fun paths(raw: String): String = raw.replace("\${CLAUDE_PLUGIN_ROOT}", root)
        .replace("\${CLAUDE_PLUGIN_DATA}", data).replace("\${CLAUDE_PROJECT_DIR}", project)
}

private val USER_REFERENCE = Regex("\\$\\{user_config\\.([A-Za-z_][A-Za-z0-9_]*)}")
private fun text(value: JsonElement): String = (value as? JsonPrimitive)?.content ?: value.toString()

/** MCP 的配置只在使用时展开；缺值保留占位符，解析器能提示缺失而不打印值。 */
fun resolvePluginMcp(raw: String, vars: PluginVars, values: PluginValues): String =
    USER_REFERENCE.replace(vars.paths(raw)) { m -> values.value(m.groupValues[1])?.let(::text) ?: m.value }

/** 模型正文绝不能借用 MCP 的展开函数；敏感字段始终留占位符，包括未配置的敏感字段。 */
fun resolvePluginBody(raw: String, vars: PluginVars, values: PluginValues, sensitiveKeys: Set<String>): String =
    USER_REFERENCE.replace(vars.paths(raw)) { m ->
        val key = m.groupValues[1]
        if (key in sensitiveKeys) m.value else values.public[key]?.let(::text) ?: m.value
    }

fun pluginDataId(ref: PluginRef): String = ref.id.map {
    if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-'
}.joinToString("")

/** 写回入口先分流再构造 settings JSON，调用方永远拿不到敏感值的明文 settings 片段。 */
suspend fun savePluginOptions(
    plugin: LoadedPlugin, values: JsonObject, store: PluginSecretStore,
): JsonObject {
    val definitions = (plugin.userConfig + plugin.channels.flatMap { it.userConfig }).associateBy { it.key }
    val public = linkedMapOf<String, JsonElement>()
    for ((key, value) in values) {
        val def = definitions[key] ?: continue
        if (def.sensitive) store.save(plugin.ref.id, key, value) else public[key] = value
    }
    return JsonObject(mapOf("options" to JsonObject(public)))
}
