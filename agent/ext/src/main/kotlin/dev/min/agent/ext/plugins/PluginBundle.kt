package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.*
import dev.min.agent.ext.skills.SkillFiles
import dev.min.agent.ext.styles.OutputStyleDef
import dev.min.agent.spi.*
import kotlinx.serialization.json.*

fun pluginHookExportNames(keys: List<String>): List<String> =
    listOf("CLAUDE_PLUGIN_ROOT", "CLAUDE_PLUGIN_DATA", "CLAUDE_PROJECT_DIR") + keys.map { "CLAUDE_PLUGIN_OPTION_${it.uppercase()}" }

/** 插件只支持 command hook，不能借 frontmatter 把未支持类型绕进既有引擎。 */
fun commandPluginHooks(events: JsonObject): JsonObject = JsonObject(events.mapNotNull { (event, value) ->
    val groups = (value as? JsonArray)?.mapNotNull { group ->
        val obj = group as? JsonObject ?: return@mapNotNull null
        val handlers = (obj["hooks"] as? JsonArray)?.filter { (it as? JsonObject)?.get("type") == JsonPrimitive("command") }.orEmpty()
        if (handlers.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(handlers)))
    }.orEmpty()
    if (groups.isEmpty()) null else event to JsonArray(groups)
}.toMap())

/** 不含用户配置值的会话组件；正文在调用时读，密钥只在 MCP 使用侧展开。 */
class PluginBundle(
    val batch: PluginBatch,
    val skills: List<SkillDef>,
    val commands: List<CommandDef>,
    val agents: List<AgentDef>,
    val hookSources: List<JsonObject>,
    val styles: Map<String, OutputStyleDef>,
    val notes: List<String>,
    private val fs: WorkspaceFs,
    private val contexts: Map<String, Pair<PluginVars, PluginValues>>,
    private val trusted: Set<String>,
) {
    suspend fun readBody(path: GuestPath): String? {
        val plugin = batch.plugins.firstOrNull { path.value.startsWith(it.root.trimEnd('/') + "/") } ?: return SkillFiles.readBody(fs, path)
        val command = plugin.commands.firstOrNull { inlinePath(plugin, it) == path }
        val body = command?.content ?: SkillFiles.readBody(fs, path) ?: return null
        val (vars, values) = contexts.getValue(plugin.ref.id)
        val sensitive = (plugin.userConfig + plugin.channels.flatMap { it.userConfig }).filter { it.sensitive }.map { it.key }.toSet()
        return resolvePluginBody(body, vars, values, sensitive)
    }

    fun mcpSources(): List<PluginMcpSource> = batch.plugins.flatMap { plugin ->
        val (vars, values) = contexts.getValue(plugin.ref.id)
        plugin.mcpServers.map { server ->
            fun expand(value: JsonElement): JsonElement = when (value) {
                is JsonPrimitive -> if (value.isString) JsonPrimitive(resolvePluginMcp(value.content, vars, values)) else value
                is JsonArray -> JsonArray(value.map(::expand))
                is JsonObject -> JsonObject(value.mapValues { expand(it.value) })
            }
            // 未信任时不读取或传输真值；MCP 加载器仍能给出 Untrusted 的可见结论。
            val forbiddenHelper = (server.config["headersHelper"] as? JsonPrimitive)?.content?.contains("\${user_config.") == true
            val safeConfig = if (forbiddenHelper) JsonObject(server.config - "headersHelper") else server.config
            val resolved = if (plugin.ref.id in trusted) expand(safeConfig).jsonObject else safeConfig
            val config = if ((resolved["type"] as? JsonPrimitive)?.content in listOf(null, "stdio")) {
                JsonObject(resolved + ("env" to JsonObject((resolved["env"] as? JsonObject).orEmpty() + mapOf(
                    "CLAUDE_PLUGIN_ROOT" to JsonPrimitive(vars.root), "CLAUDE_PLUGIN_DATA" to JsonPrimitive(vars.data),
                ))))
            } else resolved
            PluginMcpSource(server.serverName, config, plugin.ref.id in trusted, if (forbiddenHelper) "headersHelper 不得引用 user_config" else null)
        }
    }

    companion object {
        fun inlinePath(plugin: LoadedPlugin, command: PluginCommand) = GuestPath(
            plugin.root.trimEnd('/') + "/.claude-plugin/inline/" + command.name.substringAfter(':').replace(':', '/') + ".md",
        )

        suspend fun load(
            fs: WorkspaceFs, cwd: GuestPath, home: GuestPath, settings: MergedSettings,
            dirs: List<GuestPath> = emptyList(),
            canonical: (GuestPath) -> GuestPath = { it },
            trust: (GuestPath) -> Boolean = { false },
            secrets: PluginSecretStore = PluginSecretStore(McpTokenStore.Unavailable),
            installedPath: (String) -> GuestPath = { GuestPath(it) },
            shellPath: (GuestPath) -> String = { it.value },
        ): PluginBundle {
            val io = ConfigIo(fs)
            val candidates = mutableListOf<PluginRequest>()
            val options = linkedMapOf<String, JsonObject>()
            fun localInstall(raw: String): GuestPath? = try { installedPath(raw) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // 安装索引可能过期或含本机无效路径；诊断不能带出原始字段值。
                io.notes += "本地插件安装记录路径无效，已跳过"
                null
            }
            // pluginConfigs 只能由用户/托管层提供，项目文件不能注入或覆盖凭证配置。
            settings.layers.filter { it.source == ConfigSource.User || it.source == ConfigSource.Managed }.forEach { layer ->
                (layer.raw["pluginConfigs"] as? JsonObject)?.forEach { (id, config) ->
                    (config as? JsonObject)?.get("options")?.let { if (it is JsonObject) options[id] = it }
                }
            }
            val pluginsDir = GuestPaths.join(home.value, ".claude/plugins")
            val index = io.readText("$pluginsDir/installed_plugins.json")?.let {
                SettingsReader.parseObject(it, "installed_plugins.json", io.notes)?.get("plugins") as? JsonObject
            }
            index?.forEach { (id, installs) ->
                // 只接已有本地安装记录；远程源、市场安装及自动下载不属于本期。
                val records = installs as? JsonArray ?: return@forEach
                val eligible = records.mapNotNull { it as? JsonObject }.filter { record ->
                    val scope = (record["scope"] as? JsonPrimitive)?.content
                    scope == "user" || (scope in setOf("project", "local") &&
                        (record["projectPath"] as? JsonPrimitive)?.content?.let { localInstall(it)?.let(canonical) == canonical(cwd) } == true)
                }
                val record = eligible.lastOrNull() ?: return@forEach
                val rawPath = (record["installPath"] as? JsonPrimitive)?.content ?: return@forEach
                val path = localInstall(rawPath) ?: return@forEach
                candidates += PluginRequest(path.value, PluginRef(id.substringBefore('@'), id.substringAfter('@', "").ifEmpty { null }),
                    enabledPlugins = settings.enabledPlugins + (id to (settings.enabledPlugins[id] ?: false)))
            }
            // 未安装索引的本地缓存也可以按 enabledPlugins 找到；禁用项仍留作依赖候选。
            for (id in settings.enabledPlugins.keys) {
                if (candidates.any { it.ref?.id == id }) continue
                val market = id.substringAfter('@', "")
                if (market.isEmpty()) continue
                val base = "$pluginsDir/cache/$market/${id.substringBefore('@')}"
                val versions = io.entries(base).filter { it.kind == FileKind.Directory }
                versions.lastOrNull()?.let { candidates += PluginRequest("$base/${it.name}", PluginRef(id.substringBefore('@'), market), enabledPlugins = settings.enabledPlugins) }
            }
            dirs.forEach { candidates += PluginRequest(it.value, null) }
            val batch = PluginLoader(fs, canonical).loadAll(candidates)
            val notes = (batch.errors + batch.overrides + batch.plugins.flatMap { it.warnings }).toMutableList()
            val skillDefs = mutableListOf<SkillDef>()
            val commandDefs = mutableListOf<CommandDef>()
            val agentDefs = mutableListOf<AgentDef>()
            val hooks = mutableListOf<JsonObject>()
            val styles = linkedMapOf<String, OutputStyleDef>()
            val contexts = linkedMapOf<String, Pair<PluginVars, PluginValues>>()
            val trusted = mutableSetOf<String>()
            val realUserPlugins = canonical(GuestPath(pluginsDir)).value.trimEnd('/')
            for (plugin in batch.plugins) {
                val realRoot = canonical(GuestPath(plugin.root)).value
                var isTrusted = realRoot.startsWith("$realUserPlugins/") || trust(GuestPath(plugin.root))
                if (isTrusted) trusted += plugin.ref.id
                else if (plugin.hooks.isNotEmpty() || plugin.mcpServers.isNotEmpty()) notes += "plugin:${plugin.namespace} 所在目录未信任，hooks / MCP 未启用"
                val vars = PluginVars(shellPath(GuestPath(plugin.root)), shellPath(GuestPath("$pluginsDir/data/${pluginDataId(plugin.ref)}")), shellPath(cwd))
                val label = "plugin:${plugin.namespace}"
                val public = options[plugin.ref.id] ?: JsonObject(emptyMap())
                val values = try {
                    PluginValues.read(plugin, public, if (isTrusted) secrets else PluginSecretStore(McpTokenStore.Unavailable))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    // 安全仓库损坏不能降级到 settings 中的明文，也不能让一个插件阻止其他配置装载。
                    notes += "$label 的安全配置读取失败，hooks / MCP 未启用"
                    isTrusted = false
                    trusted.remove(plugin.ref.id)
                    PluginValues.read(plugin, public, PluginSecretStore(McpTokenStore.Unavailable))
                }
                val definitions = plugin.userConfig + plugin.channels.flatMap { it.userConfig }
                val sensitive = definitions.filter { it.sensitive }.map { it.key }.toSet()
                fun renderHooks(rawEvents: JsonObject): JsonObject {
                    val env = buildJsonObject {
                        put("CLAUDE_PLUGIN_ROOT", vars.root)
                        put("CLAUDE_PLUGIN_DATA", vars.data)
                        put("CLAUDE_PROJECT_DIR", vars.project)
                        definitions.forEach { def ->
                            // hook 配置会被冻结到日志，敏感值只能保留占位符。
                            put("CLAUDE_PLUGIN_OPTION_${def.key.uppercase()}", if (def.sensitive) "\${user_config.${def.key}}"
                                else values.public[def.key]?.let { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty())
                        }
                    }
                    return JsonObject(commandPluginHooks(rawEvents).mapNotNull { (event, groups) ->
                        val rendered = groups.jsonArray.mapNotNull group@{ group ->
                            val obj = group.jsonObject
                            val handlers = obj.getValue("hooks").jsonArray.mapNotNull handler@{ value ->
                                val handler = value.jsonObject
                                val command = (handler["command"] as? JsonPrimitive)?.content
                                if (command?.contains("\${user_config.") == true) {
                                    notes += "$label 的 shell hook 引用了 user_config，已跳过；请使用 exec args 或 OPTION 环境变量"
                                    return@handler null
                                }
                                val out = handler.toMutableMap()
                                command?.let { out["command"] = JsonPrimitive(vars.paths(it)) }
                                (handler["args"] as? JsonArray)?.let { args ->
                                    out["args"] = JsonArray(args.map { JsonPrimitive(resolvePluginBody(it.jsonPrimitive.content, vars, values, sensitive)) })
                                }
                                out["minPluginEnv"] = env
                                JsonObject(out)
                            }
                            if (handlers.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(handlers)))
                        }
                        if (rendered.isEmpty()) null else event to JsonArray(rendered)
                    }.toMap())
                }
                contexts[plugin.ref.id] = vars to values
                plugin.skills.forEach { skill ->
                    val path = GuestPaths.join(plugin.root, skill.skillMd)
                    SkillLoader.readSkill(io, ConfigSource.Plugin, GuestPaths.parentOf(path), skill.name.substringAfterLast(':'))?.let { def ->
                        skillDefs += def.copy(name = skill.name, body = resolvePluginBody(def.body, vars, values, plugin.userConfig.filter { it.sensitive }.map { it.key }.toSet()), sourceLabel = label, hooks = if (isTrusted) def.hooks?.let(::renderHooks) else null)
                    }
                }
                plugin.commands.forEach { command ->
                    val path = command.sourcePath?.let { GuestPath(GuestPaths.join(plugin.root, it)) } ?: inlinePath(plugin, command)
                    val realPath = canonical(path).value
                    val commandRoot = canonical(GuestPath(plugin.root)).value.trimEnd('/')
                    if (!realPath.startsWith("$commandRoot/")) {
                        notes += "$label 的 command ${path.value} escapes plugin directory，跳过该组件"
                        return@forEach
                    }
                    val def = if (command.content == null) CommandLoader.read(io, ConfigSource.Plugin, path.value, command.name)
                    else CommandDef(command.name, command.description ?: command.content.lineSequence().firstOrNull().orEmpty(), path, command.content, ConfigSource.Plugin)
                    def?.let { commandDefs += it.copy(body = resolvePluginBody(it.body, vars, values, plugin.userConfig.filter { it.sensitive }.map { it.key }.toSet()), description = command.description ?: it.description,
                        argumentHint = command.argumentHint ?: it.argumentHint, model = command.model ?: it.model,
                        allowedTools = if (command.allowedTools.isEmpty()) it.allowedTools else command.allowedTools,
                        sourceLabel = label, hooks = if (isTrusted) it.hooks?.let(::renderHooks) else null) }
                }
                plugin.agents.forEach { agent ->
                    AgentLoader.read(io, ConfigSource.Plugin, GuestPaths.join(plugin.root, agent.filePath), fallbackName = agent.name.substringAfterLast(':'))?.let {
                        val sensitive = plugin.userConfig.filter { d -> d.sensitive }.map { d -> d.key }.toSet()
                        // 官方插件 agent 不能自行提供权限模式、hooks、MCP 或启动输入。
                        agentDefs += it.copy(name = agent.name, sourceLabel = label, prompt = resolvePluginBody(it.prompt, vars, values, sensitive),
                            hooks = null, permissionMode = null, mcpServers = null, initialPrompt = null)
                    }
                }
                if (isTrusted) {
                    val events = JsonObject(plugin.hooks.mapValues { (_, handlers) -> JsonArray(handlers.map { handler ->
                        buildJsonObject {
                            handler.matcher?.let { put("matcher", it) }
                            put("hooks", JsonArray(listOf(handler.raw)))
                        }
                    }) })
                    renderHooks(events).takeIf { it.isNotEmpty() }?.let { hooks += it }
                }
                plugin.outputStyles.forEach { style -> io.readText(GuestPaths.join(plugin.root, style.filePath))?.let {
                    val fm = FrontmatterParser.parse(it, style.filePath)
                    val fields = FrontmatterFields(fm.fields, style.filePath, io.notes)
                    val sensitive = plugin.userConfig.filter { d -> d.sensitive }.map { d -> d.key }.toSet()
                    styles[style.name] = OutputStyleDef(style.name, fields.string("description"), fields.bool("keep-coding-instructions") ?: false,
                        resolvePluginBody(fm.body, vars, values, sensitive))
                } }
            }
            notes += io.notes
            return PluginBundle(batch, skillDefs, commandDefs, agentDefs, hooks, styles, notes, fs, contexts, trusted)
        }
    }
}

/** 已经由目录信任闸门判定的配置输入；toString 不得打印展开后的 env / headers。 */
class PluginMcpSource(val name: String, val config: JsonObject, val trusted: Boolean, val error: String? = null) {
    override fun toString(): String = "PluginMcpSource(name=$name, trusted=$trusted)"
}
