package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.*
import dev.min.agent.ext.skills.SkillFiles
import dev.min.agent.ext.styles.OutputStyleDef
import dev.min.agent.spi.*
import kotlinx.serialization.json.*

/** 类型和字段交给 HookSet 统一校验，不能在插件发现时静默丢失门禁。 */
fun commandPluginHooks(events: JsonObject): JsonObject = JsonObject(events.mapNotNull { (event, value) ->
    val groups = (value as? JsonArray)?.mapNotNull { group ->
        val obj = group as? JsonObject ?: return@mapNotNull null
        val handlers = (obj["hooks"] as? JsonArray).orEmpty()
        if (handlers.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(handlers)))
    }.orEmpty()
    if (groups.isEmpty()) null else event to JsonArray(groups)
}.toMap())

/** 不含密钥真值的会话组件；正文在调用时读，密钥仅在 MCP / hook 使用侧展开。 */
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
    private val approvalRequired: Set<String>,
) {
    fun resolveHook(handler: dev.min.agent.ext.hooks.HookHandler): dev.min.agent.ext.hooks.HookHandler {
        val id = (handler.raw["minPluginId"] as? JsonPrimitive)?.content ?: return handler
        if (id !in trusted) return handler
        val (vars, values) = contexts.getValue(id)
        // 真值只存在于执行器的参数与进程环境，冻结的 hooks 与正文仍保留占位符。
        return handler.copy(args = handler.args?.map { resolvePluginMcp(it, vars, values) },
            pluginEnv = handler.pluginEnv.mapValues { resolvePluginMcp(it.value, vars, values) })
    }
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
            PluginMcpSource(server.serverName, config, plugin.ref.id in trusted, if (forbiddenHelper) "headersHelper 不得引用 user_config" else null,
                requiresApproval = plugin.ref.id in approvalRequired)
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
            val enabledSources = linkedMapOf<String, ConfigSource>()
            val enabled = linkedMapOf<String, Boolean>()
            settings.layers.forEach { layer -> layer.enabledPlugins.forEach { (id, value) -> enabled[id] = value; enabledSources[id] = layer.source } }
            // 同时启用表示已有个人授权，项目重复声明不能把它降为项目插件。
            settings.layers.filter { it.source == ConfigSource.User }.forEach { layer -> layer.enabledPlugins.filterValues { it }.keys.forEach { enabledSources[it] = ConfigSource.User } }
            val managed = settings.layers.lastOrNull { it.source == ConfigSource.Managed }
            managed?.enabledPlugins?.forEach { (id, value) -> enabled[id] = value; enabledSources[id] = ConfigSource.Managed }
            // 托管层已覆盖最终值；普通低层的 false 不能否决更高层明确启用的依赖。
            val disabledDependencies = enabled.filterValues { !it }.keys.toSet()
            val scopes = linkedMapOf<String, String?>()
            val inlineRoots = mutableSetOf<String>()
            val cacheRoot = canonical(GuestPath(GuestPaths.join(home.value, ".claude/plugins/cache"))).value.trimEnd('/')
            fun validId(id: String): Boolean {
                val parts = id.split('@')
                val valid = parts.size == 2 && validPluginName(parts[0]) && Regex("[A-Za-z0-9_-]+").matches(parts[1])
                if (!valid) io.notes += "enabledPlugins 插件键不合法，已跳过：$id"
                return valid
            }
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
                if (!validId(id)) return@forEach
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
                scopes[canonical(path).value] = (record["scope"] as? JsonPrimitive)?.content
                candidates += PluginRequest(path.value, PluginRef(id.substringBefore('@'), id.substringAfter('@', "").ifEmpty { null }),
                    enabledPlugins = enabled + (id to (enabled[id] ?: false)), disabledDependencies = disabledDependencies)
            }
            // 未安装索引的本地缓存也可以按 enabledPlugins 找到；禁用项仍留作依赖候选。
            for (id in enabled.keys) {
                if (!validId(id)) continue
                if (candidates.any { it.ref?.id == id }) continue
                val market = id.substringAfter('@', "")
                if (market.isEmpty()) continue
                val base = "$pluginsDir/cache/$market/${id.substringBefore('@')}"
                if (!canonical(GuestPath(base)).value.startsWith("$cacheRoot/")) {
                    io.notes += "enabledPlugins 缓存路径 escapes cache：$base"
                    continue
                }
                val versions = io.entries(base).filter { it.kind == FileKind.Directory }
                versions.maxWithOrNull(compareBySemanticVersion { it.name })?.let {
                    val path = GuestPath("$base/${it.name}")
                    if (!canonical(path).value.startsWith("$cacheRoot/")) io.notes += "enabledPlugins 版本路径 escapes cache：${path.value}"
                    else candidates += PluginRequest(path.value, PluginRef(id.substringBefore('@'), market), enabledPlugins = enabled, disabledDependencies = disabledDependencies)
                }
            }
            val disableSideload = (managed?.raw?.get("disableSideloadFlags") as? JsonPrimitive)?.booleanOrNull == true
            if (disableSideload && dirs.isNotEmpty()) io.notes += "托管 disableSideloadFlags 禁止 --plugin-dir"
            else dirs.forEach {
                inlineRoots += canonical(it).value
                candidates += PluginRequest(it.value, null, enabledPlugins = enabled, sideload = true, disabledDependencies = disabledDependencies)
            }
            val acceptedCandidates = candidates.filter { request ->
                val realRoot = canonical(GuestPath(request.root)).value
                val source = enabledSources[request.ref?.id]
                val projectEnabled = source in setOf(ConfigSource.Project, ConfigSource.Local) || scopes[realRoot] in setOf("project", "local")
                val accepted = request.sideload || !projectEnabled || trust(cwd)
                // 信任前连正文和默认设置也不能装入会话，不能只关掉 hooks / MCP。
                if (!accepted) io.notes += "plugin:${request.ref?.id ?: request.root} 启用来源所属工作区未信任，整个插件未加载"
                accepted
            }
            val batch = PluginLoader(fs, canonical).loadAll(acceptedCandidates, managed?.enabledPlugins?.keys.orEmpty().map { it.substringBefore('@') }.toSet())
            val notes = (batch.errors + batch.overrides + batch.plugins.flatMap { it.warnings }).toMutableList()
            val skillDefs = mutableListOf<SkillDef>()
            val commandDefs = mutableListOf<CommandDef>()
            val agentDefs = mutableListOf<AgentDef>()
            val hooks = mutableListOf<JsonObject>()
            val styles = linkedMapOf<String, OutputStyleDef>()
            val contexts = linkedMapOf<String, Pair<PluginVars, PluginValues>>()
            val trusted = mutableSetOf<String>()
            val approvalRequired = mutableSetOf<String>()
            for (plugin in batch.plugins) {
                val realRoot = canonical(GuestPath(plugin.root)).value
                val source = enabledSources[plugin.ref.id]
                val projectEnabled = source in setOf(ConfigSource.Project, ConfigSource.Local) || scopes[realRoot] in setOf("project", "local")
                if (projectEnabled) approvalRequired += plugin.ref.id
                var isTrusted = realRoot in inlineRoots || if (projectEnabled) trust(cwd)
                    else source in setOf(ConfigSource.User, ConfigSource.Managed) || scopes[realRoot] == "user"
                if (isTrusted) trusted += plugin.ref.id
                else if (plugin.hooks.isNotEmpty() || plugin.mcpServers.isNotEmpty()) notes += "plugin:${plugin.namespace} 启用来源所属工作区未信任，hooks / MCP 未启用"
                val vars = PluginVars(shellPath(GuestPath(plugin.root)), shellPath(GuestPath("$pluginsDir/data/${pluginDataId(plugin.ref)}")), shellPath(cwd))
                val label = "plugin:${plugin.namespace}"
                val public = options[plugin.ref.id] ?: options[plugin.ref.name] ?: JsonObject(emptyMap())
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
                                // prompt / agent / mcp_tool 型声明原样保留：模型侧执行器认它们（受上面的信任闸管着）。
                                // mcp_tool 的 server 要写 scoped 名（plugin:<插件名>:<服务器名>，CC hooks.md :546），
                                // 与 McpConfigLoader 注册插件服务器的名字同一个口径
                                val command = (handler["command"] as? JsonPrimitive)?.content
                                if (command?.contains("\${user_config.") == true) {
                                    notes += "$label 的 shell hook 引用了 user_config，已跳过；请使用 exec args 或 OPTION 环境变量"
                                    return@handler null
                                }
                                val out = handler.toMutableMap()
                                command?.let { out["command"] = JsonPrimitive(vars.paths(it)) }
                                (handler["args"] as? JsonArray)?.let { args ->
                                    if (args.any { it !is JsonPrimitive || !it.isString }) {
                                        notes += "$label 的 hook args 必须是字符串数组，跳过该 handler"
                                        return@handler null
                                    }
                                    out["args"] = JsonArray(args.map { JsonPrimitive(resolvePluginBody(it.jsonPrimitive.content, vars, values, sensitive)) })
                                }
                                out["minPluginEnv"] = env
                                out["minPluginId"] = JsonPrimitive(plugin.ref.id)
                                JsonObject(out)
                            }
                            if (handlers.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(handlers)))
                        }
                        if (rendered.isEmpty()) null else event to JsonArray(rendered)
                    }.toMap()).also {
                        notes += dev.min.agent.ext.hooks.HookSet.parse(listOf(HookSource(ConfigSource.Plugin, it))).notes
                    }
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
            return PluginBundle(batch, skillDefs, commandDefs, agentDefs, hooks, styles, notes, fs, contexts, trusted, approvalRequired)
        }
    }
}

/** 已经由目录信任闸门判定的配置输入；toString 不得打印展开后的 env / headers。 */
class PluginMcpSource(val name: String, val config: JsonObject, val trusted: Boolean, val error: String? = null, val requiresApproval: Boolean = false) {
    override fun toString(): String = "PluginMcpSource(name=$name, trusted=$trusted)"
}
