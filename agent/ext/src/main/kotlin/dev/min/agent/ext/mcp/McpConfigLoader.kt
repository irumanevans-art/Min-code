package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.ConfigIo
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.GuestPaths
import dev.min.agent.ext.config.MergedSettings
import dev.min.agent.ext.config.SettingsReader
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * MCP 服务器配置加载器（MCP设计 §3）：读 Claude Code 用户现有的配置文件，按 CC 的来源优先级、企业管控、
 * 信任与批准规则，产出「有哪些服务器、每台能不能启动、为什么」。**只读 + 解析**，不连服务器、不跑 headersHelper。
 *
 * 来源与优先级（同名取最高优先级的**整条**定义，不按字段合并；cc-mcp.md「Scope hierarchy」）：
 * 1. `managed-mcp.json` 存在即**独占**——只加载它与 `managedMcpServers`，其余来源一概不读，空表 = 关闭 MCP；
 *    文件存在但读不了或不是合法 JSON → **失败即关**（一台都不加载；CC 原文对这种情况没写，照「managed 设置坏了
 *    CC 拒绝启动」的同类做法取保守）。
 * 2. 平时：`managedMcpServers`（managed 设置层提供，条目只许远程 https 且无 `${VAR}`）> local
 *    （`~/.claude.json` 的 `projects["<项目路径>"].mcpServers`，键的路径比较做规范化）> project（`.mcp.json`）
 *    > user（`~/.claude.json` 顶层）。
 *
 * 闸门（[McpGate]，同时命中取最严）：deniedMcpServers（一切来源）> 不在 allowedMcpServers（managed 自己的
 * 服务器免检）> disabledMcpjsonServers（任何设置层，包括没被信任的项目层）/ `~/.claude.json` 的 disabledMcpServers
 * （界面开关）/ ws 传输 > 项目服务器未信任 > 项目服务器未批准。
 *
 * 环境变量：`${VAR}` / `${VAR:-default}`（command、args、env 的值、url、headers 的值）；未设且无默认的保留字面并
 * 记进 [McpServerEntry.unresolvedVars]；远程 url / headers 里的凭证类变量恒读为空（见 [McpVars]）。
 *
 * note 只给人看不进模型上下文，且**不含任何配置值**（header / env 的值、展开后的 URL 都不写），同名冲突的端点
 * 照 CC 的做法引用**原文**（`${VAR}` 不展开）。
 */
object McpConfigLoader {
    /** CC 文档点名的保留名（内置服务器；cc-mcp.md「Reserved names」）。`anthropic-skills` 只藏 prompts，不禁载入 */
    private val RESERVED_NAMES = setOf("workspace", "claude-in-chrome", "computer-use", "Claude Preview", "Claude Browser")

    suspend fun load(
        fs: WorkspaceFs,
        home: GuestPath,
        projectRoot: GuestPath,
        settings: MergedSettings,
        trustProject: Boolean,
        env: (String) -> String?,
        managedDir: GuestPath = GuestPath("/etc/claude-code"),
        plugins: List<dev.min.agent.ext.plugins.PluginMcpSource> = emptyList(),
        /**
         * `--restricted`（Min 的解释）：只连托管层与 `--settings` 声明的服务器。用户层（~/.claude.json 顶层）、
         * 本项目 local（~/.claude.json 的 projects 项）、项目 `.mcp.json` 与插件自带的服务器一律不收；managed-mcp.json 与
         * `managedMcpServers`（managed 层，以及 --settings 里同名键）照常。
         */
        restricted: Boolean = false,
    ): McpConfigSnapshot {
        val pluginSources = if (restricted) emptyList() else plugins
        val io = ConfigIo(fs)
        val notes = io.notes
        val homeDir = GuestPaths.normalize(home.value)
        val projectDir = GuestPaths.normalize(projectRoot.value)

        // ---- managed-mcp.json：存在即独占；坏了失败即关 ----
        val managedFile = GuestPaths.join(GuestPaths.normalize(managedDir.value), "managed-mcp.json")
        val exclusiveServers: Map<String, JsonObject>? = if (io.kind(managedFile) == null) null else {
            val obj = io.readText(managedFile)?.let { SettingsReader.parseObject(it, managedFile, notes) }
            if (obj == null) {
                notes += "managed-mcp.json 存在但读不了或不是合法 JSON：一台服务器都不加载（失败即关，企业策略不能静默失效）"
                return McpConfigSnapshot(emptyList(), notes.toList(), managedExclusive = true)
            }
            // managed 文件里没有 mcpServers 键：当作空表（关闭 MCP）并说明，与「空表 = 关」同向
            mcpServersOf(obj, managedFile, absentMeansOff = true, notes)
        }

        // ---- ~/.claude.json：user 顶层、local（按项目路径）、界面停用开关 ----
        val claudeJsonPath = GuestPaths.join(homeDir, ".claude.json")
        val claudeJson = io.readText(claudeJsonPath)?.let { SettingsReader.parseObject(it, claudeJsonPath, notes) }
        val userServers = if (restricted) LinkedHashMap() else claudeJson?.let { mcpServersOf(it, claudeJsonPath, absentMeansOff = false, notes) } ?: LinkedHashMap()
        // projects 下的键与项目路径做规范化比较（尾斜杠不算差别）
        val projectState = (claudeJson?.get("projects") as? JsonObject)
            ?.entries?.firstOrNull { GuestPaths.normalize(it.key) == projectDir }?.value as? JsonObject
        val localServers = if (restricted) LinkedHashMap() else projectState?.let { mcpServersOf(it, claudeJsonPath, absentMeansOff = false, notes) } ?: LinkedHashMap()
        val uiDisabled = projectState?.stringArrayOf("disabledMcpServers")?.toSet() ?: emptySet()

        // ---- 项目 .mcp.json（独占时不读）----
        val projectServers = if (exclusiveServers != null || restricted) LinkedHashMap<String, JsonObject>() else {
            val path = GuestPaths.join(projectDir, ".mcp.json")
            io.readText(path)?.let { SettingsReader.parseObject(it, path, notes) }
                ?.let { mcpServersOf(it, path, absentMeansOff = false, notes) } ?: LinkedHashMap()
        }

        // ---- managedMcpServers（managed 设置层提供，不独占）----
        val providedServers = managedSettingServers(settings, notes, restricted)

        // ---- 逐条解析（高优先级在前），同名去重并记 shadowed ----
        val ordered: List<Pair<Origin, Map<String, JsonObject>>> = if (exclusiveServers != null) {
            listOf(Origin.ManagedFile to exclusiveServers, Origin.ManagedSetting to providedServers)
        } else {
            listOf(Origin.ManagedSetting to providedServers, Origin.Local to localServers, Origin.ProjectFile to projectServers, Origin.User to userServers, Origin.Plugin to pluginSources.associate { it.name to it.config })
        }
        val parsed = ordered.flatMap { (origin, servers) ->
            servers.map { (name, raw) -> parseOne(name, raw, origin, notes) }.filterNotNull()
        }
        val winners = mutableListOf<Parsed>()
        for ((name, group) in parsed.groupBy { it.name }) {
            val winner = group.first()
            winners += winner
            val losers = group.drop(1)
            if (losers.isNotEmpty()) {
                val differing = losers.filter { endpointOf(it) != endpointOf(winner) }
                if (differing.isNotEmpty()) {
                    // 端点引用原文（${VAR} 不展开），与 CC 的同名冲突警告同一口径，不会带出解析后的值
                    notes += "MCP 服务器 \"$name\" 在多个作用域有定义，生效的是 ${winner.origin.label}" +
                        "（覆盖 ${losers.map { it.origin.label }.distinct().joinToString("、")}）；" +
                        "与 ${differing.joinToString("、") { "${it.origin.label}(${endpointOf(it)})" }} 的端点不同，如非有意请清理多余的定义"
                }
            }
        }

        // ---- 管控、批准与停用的判定材料 ----
        val policy = McpPolicy.fromLayers(settings.layers, env, notes)
        val trustedLayers = settings.layers.filter { trustProject || it.source == ConfigSource.User || it.source == ConfigSource.Managed }
        val disabledMcpjson = settings.layers.flatMap { it.raw.stringArrayOf("disabledMcpjsonServers") }.toSet()
        val enabledMcpjson = trustedLayers.flatMap { it.raw.stringArrayOf("enabledMcpjsonServers") }.toSet()
        // enableAll：未信任的文件夹里「任何一份可信文件写了 true 就算」；信任了则最高层的值说话（false 能压低层的 true）
        val enableAll = if (trustProject) {
            trustedLayers.lastOrNull { it.raw.boolOf("enableAllProjectMcpServers") != null }?.raw?.boolOf("enableAllProjectMcpServers") == true
        } else {
            trustedLayers.any { it.raw.boolOf("enableAllProjectMcpServers") == true }
        }

        // 会话环境：settings 的 env 层叠在宿主环境之上（CC 把 env 应用到每个会话，展开发生在会话环境里）
        val sessionEnv: (String) -> String? = { name -> settings.env[name] ?: env(name) }

        val entries = winners.map { p ->
            val (spec, unresolved) = buildSpec(p, sessionEnv)
            if (unresolved.isNotEmpty()) {
                // CC：配置照常加载、保留字面 ${VAR}，在 /mcp 与 mcp list 里点名变量并给警告
                notes += "MCP 服务器 \"${p.name}\" 引用了未设置的环境变量（${unresolved.joinToString("、")}），保留字面不展开"
            }
            // project / local 的 headersHelper 要等工作区信任；未信任时置空（CC：helper 只在静态 headers 下用）
            val finalSpec = if (spec is McpServerSpec.Remote && spec.headersHelper != null &&
                (p.origin == Origin.ProjectFile || p.origin == Origin.Local) && !trustProject
            ) {
                notes += "MCP 服务器 \"${p.name}\" 的 headersHelper 来自${p.origin.label}作用域，工作区还没被信任，暂不启用（只用静态 headers）"
                spec.copy(headersHelper = null)
            } else spec

            val candidates = mutableListOf<Pair<McpGate, String>>()
            if (p.origin == Origin.Plugin) pluginSources.firstOrNull { it.name == p.name }?.error?.let { candidates += McpGate.Disabled to it }
            if (p.origin == Origin.Plugin && pluginSources.firstOrNull { it.name == p.name }?.trusted != true) {
                candidates += McpGate.Untrusted to "插件启用来源的工作区未信任"
            }
            if (p.origin == Origin.Plugin && pluginSources.firstOrNull { it.name == p.name }?.requiresApproval == true &&
                !(enableAll || p.name in enabledMcpjson)) {
                candidates += McpGate.NeedsApproval to "项目插件 MCP 服务器需要逐服务器批准（enabledMcpjsonServers）"
            }
            policy.deniedBy(finalSpec)?.let { candidates += McpGate.Denied to "被 deniedMcpServers 拦下（${it.describe}）" }
            // 组织自己的服务器免 allowlist：managedMcpServers 恒免；managed-mcp.json 里用了 ${VAR} 的不免（CC 原文）
            val exempt = p.origin == Origin.ManagedSetting || (p.origin == Origin.ManagedFile && !rawUsesVars(p.raw))
            if (!exempt && !policy.allowedBy(finalSpec)) candidates += McpGate.Denied to "不在 allowedMcpServers 里（企业 allowlist）"
            if (p.origin == Origin.ProjectFile && p.name in disabledMcpjson) {
                candidates += McpGate.Disabled to "被 disabledMcpjsonServers 拒绝（任何设置层写了都生效）"
            }
            if (p.name in uiDisabled) candidates += McpGate.Disabled to "在本项目被停用（~/.claude.json 的 disabledMcpServers）"
            if (finalSpec is McpServerSpec.Remote && finalSpec.kind == McpTransportKind.Ws) {
                candidates += McpGate.Disabled to "ws 传输首期不支持（MCP设计 §13）"
            }
            if (p.origin == Origin.ProjectFile) {
                if (!trustProject) {
                    // 设计 §3 比 CC 严：不信任就不启动，不管批准与否（CC 只是不采纳仓库里的批准键）
                    candidates += McpGate.Untrusted to "工作区还没被信任（设计 §3：项目 .mcp.json 的服务器一律不启动）"
                } else if (!(enableAll || p.name in enabledMcpjson)) {
                    candidates += McpGate.NeedsApproval to "还没被批准（enableAllProjectMcpServers / enabledMcpjsonServers）"
                }
            }
            val strictest = candidates.maxByOrNull { it.first.ordinal }
            McpServerEntry(
                spec = finalSpec,
                gate = strictest?.first ?: McpGate.Enabled,
                reason = strictest?.second,
                unresolvedVars = unresolved,
                shadowed = parsed.filter { it.name == p.name && it !== p }.map { it.scope }.distinct(),
            )
        }
        return McpConfigSnapshot(entries.sortedBy { it.spec.name }, notes.toList(), managedExclusive = exclusiveServers != null)
    }

    // ---------- 来源 ----------

    /** 条目的来源细分（两个 managed 来源在 [McpScope] 里同为一档，优先级 managed-mcp.json > managedMcpServers） */
    private enum class Origin(val label: String, val scope: McpScope) {
        ManagedFile("managed-mcp.json", McpScope.Managed),
        ManagedSetting("managedMcpServers", McpScope.Managed),
        Local("local", McpScope.Local),
        ProjectFile("project", McpScope.Project),
        User("user", McpScope.User),
        Plugin("plugin", McpScope.Plugin),
    }

    /**
     * 取一个配置对象里的 `mcpServers`（只收条目是对象的）。[absentMeansOff] = true 时缺键 / 类型不对都当作空表
     * 并记说明——managed-mcp.json 存在而 `mcpServers` 缺失与「空表 = 关闭 MCP」同向，取保守。
     */
    private fun mcpServersOf(obj: JsonObject, path: String, absentMeansOff: Boolean, notes: MutableList<String>): LinkedHashMap<String, JsonObject> {
        val value = obj["mcpServers"]
        if (value == null || value is JsonNull) {
            if (absentMeansOff) notes += "$path 里没有 mcpServers：当作空表（关闭 MCP）"
            return LinkedHashMap()
        }
        if (value !is JsonObject) {
            notes += "跳过 $path 的 `mcpServers`：期望对象，实际是${if (value is JsonArray) "数组" else "非对象"}" +
                if (absentMeansOff) "，当作空表（关闭 MCP）" else ""
            return LinkedHashMap()
        }
        val out = LinkedHashMap<String, JsonObject>()
        for ((name, entry) in value) {
            if (entry is JsonObject) out[name] = entry
            else notes += "跳过 $path 的 `mcpServers.$name`：条目应是对象"
        }
        return out
    }

    /**
     * managed 设置层的 `managedMcpServers`（不独占）。条目限制照原文「What an entry can contain」：
     * 只许 http / sse（含 streamable-http 别名）、https URL、无 command / args / env / headersHelper、无 `${VAR}`；
     * 违规的跳过并记 note。非 managed 层写了这个键：忽略并说明（CC：「drops the key with a warning」）。
     */
    private fun managedSettingServers(settings: MergedSettings, notes: MutableList<String>, restricted: Boolean): LinkedHashMap<String, JsonObject> {
        // --restricted（Min 的解释）：--settings 也算运营方给的来源，同 managed 层一样认 managedMcpServers
        fun operatorLayer(source: ConfigSource) = source == ConfigSource.Managed || (restricted && source == ConfigSource.Cli)
        for (layer in settings.layers) {
            if (!operatorLayer(layer.source) && layer.raw.containsKey("managedMcpServers")) {
                notes += "忽略 ${layer.path.value} 的 `managedMcpServers`：这个键只在 managed 设置层生效"
            }
        }
        val out = LinkedHashMap<String, JsonObject>()
        for (layer in settings.layers.filter { operatorLayer(it.source) }) {
            val value = layer.raw["managedMcpServers"] ?: continue
            if (value is JsonNull) continue
            if (value !is JsonObject) {
                notes += "跳过 ${layer.path.value} 的 `managedMcpServers`：期望对象（Claude Desktop 的数组形态 CC 不收）"
                continue
            }
            for ((name, entry) in value) {
                if (entry !is JsonObject) {
                    notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：条目应是对象"
                    continue
                }
                if (!validName(name)) {
                    notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：名字只许字母数字连字符下划线"
                    continue
                }
                val type = (entry["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val kind = when (type) {
                    "http", "streamable-http" -> McpTransportKind.Http
                    "sse" -> McpTransportKind.Sse
                    else -> null
                }
                val url = (entry["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val members = listOf("command", "args", "env", "headersHelper").any { entry.containsKey(it) && entry[it] !is JsonNull }
                when {
                    kind == null -> notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：type 必须是 http 或 sse"
                    url == null || !url.startsWith("https://") ->
                        notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：url 必须是 https:// 开头"
                    members -> notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：不允许 command / args / env / headersHelper"
                    rawUsesVars(entry) -> notes += "跳过 ${layer.path.value} 的 `managedMcpServers.$name`：条目里不允许 \${VAR} 引用"
                    else -> out[name] = entry
                }
            }
        }
        return out
    }

    // ---------- 条目解析（原文照读，不展开、不修剪） ----------

    private data class Parsed(
        val name: String,
        val origin: Origin,
        val raw: JsonObject,
        val transport: McpTransportKind,
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val headersHelper: String? = null,
        val oauth: McpOAuthConfig? = null,
        val timeoutMs: Long? = null,
        val alwaysLoad: Boolean = false,
    ) {
        val scope: McpScope get() = origin.scope
    }

    private fun parseOne(name: String, raw: JsonObject, origin: Origin, notes: MutableList<String>): Parsed? {
        if (name in RESERVED_NAMES) {
            notes += "跳过 MCP 服务器 \"$name\"：名字是 CC 内置服务器的保留名，请改名"
            return null
        }
        val valid = if (origin == Origin.Plugin) {
            val parts = name.split(':')
            parts.size == 3 && parts[0] == "plugin" && dev.min.agent.ext.plugins.validPluginName(parts[1]) && validName(parts[2])
        } else validName(name)
        if (!valid) {
            notes += "跳过 MCP 服务器 \"$name\"：名字只许字母数字连字符下划线"
            return null
        }
        val type = (raw["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val hasUrl = raw.containsKey("url") && raw["url"] !is JsonNull
        if (type == null && hasUrl) {
            notes += "跳过 MCP 服务器 \"$name\"：有 url 却没有 type（没有 type 读作 stdio，属配置错误），请补 \"type\": \"http\"（或 sse / ws）"
            return null
        }
        val transport = when (type) {
            null, "stdio" -> McpTransportKind.Stdio
            "http", "streamable-http" -> McpTransportKind.Http
            "sse" -> McpTransportKind.Sse
            "ws" -> McpTransportKind.Ws
            "sdk" -> {
                notes += "跳过 MCP 服务器 \"$name\"：type \"sdk\" 只能由 SDK 宿主进程内注册"
                return null
            }

            else -> {
                notes += "跳过 MCP 服务器 \"$name\"：不认识的 type \"$type\""
                return null
            }
        }

        fun string(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // 尾部（与首部）空白只警告不修剪（CC「Hidden whitespace」）：点名字段，不回显值
        val wsFields = buildList {
            if (string("command")?.let { it != it.trim() } == true) add("command")
            if (string("url")?.let { it != it.trim() } == true) add("url")
            (raw["args"] as? JsonArray)?.forEachIndexed { i, el ->
                if ((el as? JsonPrimitive)?.isString == true && el.content != el.content.trim()) add("args[$i]")
            }
            (raw["env"] as? JsonObject)?.forEach { (k, v) ->
                if (k != k.trim()) add("env.<键名>")
                if ((v as? JsonPrimitive)?.isString == true && v.content != v.content.trim()) add("env.$k")
            }
            (raw["headers"] as? JsonObject)?.forEach { (k, v) ->
                if (k != k.trim()) add("headers.<键名>")
                if ((v as? JsonPrimitive)?.isString == true && v.content != v.content.trim()) add("headers.$k")
            }
        }
        if (wsFields.isNotEmpty()) notes += "MCP 服务器 \"$name\" 的这些字段带首尾空白（不修剪，原样使用）：${wsFields.joinToString("、")}"

        val timeout = when (val t = raw["timeout"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val n = if (t.isString) null else t.longOrNull
                if (n != null && n >= 1000) n else {
                    notes += "忽略 MCP 服务器 \"$name\" 的 timeout（低于 1000 或不是整数；CC 的最低生效值是 1000 毫秒）"
                    null
                }
            }

            else -> null
        }
        val alwaysLoad = (raw["alwaysLoad"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

        return if (transport == McpTransportKind.Stdio) {
            val command = string("command")
            if (command == null) {
                notes += "跳过 MCP 服务器 \"$name\"：stdio 服务器缺 command"
                return null
            }
            Parsed(
                name = name, origin = origin, raw = raw, transport = transport,
                command = command,
                args = (raw["args"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val text = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (text == null) notes += "跳过 MCP 服务器 \"$name\" 的 args 里一个非字符串元素"
                    text
                },
                env = stringMapOf(raw, "env"),
                timeoutMs = timeout, alwaysLoad = alwaysLoad,
            )
        } else {
            val url = string("url")
            if (url == null) {
                notes += "跳过 MCP 服务器 \"$name\"：远程服务器缺 url"
                return null
            }
            // 空 url 是合法的占位（CC 显示 not configured，不算错误、不尝试连接）
            Parsed(
                name = name, origin = origin, raw = raw, transport = transport,
                url = url,
                headers = stringMapOf(raw, "headers"),
                headersHelper = string("headersHelper"),
                oauth = (raw["oauth"] as? JsonObject)?.let { o ->
                    fun s(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    McpOAuthConfig(
                        clientId = s("clientId"),
                        callbackPort = (o["callbackPort"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.content.toIntOrNull() },
                        authServerMetadataUrl = s("authServerMetadataUrl"),
                        scopes = s("scopes"),
                        clientSecret = s("clientSecret"),
                    )
                },
                timeoutMs = timeout, alwaysLoad = alwaysLoad,
            )
        }
    }

    // ---------- 展开与闸门辅助 ----------

    /** 按传输展开环境变量，产出最终 spec 与未解析变量清单 */
    private fun buildSpec(p: Parsed, lookup: (String) -> String?): Pair<McpServerSpec, List<String>> {
        val unresolved = mutableSetOf<String>()
        fun ex(text: String, credentialEmpty: Boolean): String =
            McpVars.expand(text, lookup, credentialEmpty).also { unresolved += it.unresolved }.value
        return if (p.transport == McpTransportKind.Stdio) {
            McpServerSpec.Stdio(
                name = p.name, scope = p.scope, timeoutMs = p.timeoutMs, alwaysLoad = p.alwaysLoad,
                command = ex(p.command.orEmpty(), credentialEmpty = false),
                args = p.args.map { ex(it, false) },
                env = p.env.mapValues { (_, v) -> ex(v, false) },
            ) to unresolved.sorted()
        } else {
            McpServerSpec.Remote(
                name = p.name, scope = p.scope, timeoutMs = p.timeoutMs, alwaysLoad = p.alwaysLoad,
                kind = p.transport,
                url = ex(p.url.orEmpty(), credentialEmpty = true),
                headers = p.headers.mapValues { (_, v) -> ex(v, true) },
                headersHelper = p.headersHelper,
                oauth = p.oauth?.let { it.copy(clientSecret = it.clientSecret?.let { secret -> ex(secret, true) }) },
            ) to unresolved.sorted()
        }
    }

    /** 同名冲突的 note 里引用的「端点」：原文照录（不展开），stdio 是命令行，远程是 URL */
    private fun endpointOf(p: Parsed): String = when (p.transport) {
        McpTransportKind.Stdio -> (listOf(p.command.orEmpty()) + p.args).joinToString(" ")
        else -> p.url.orEmpty()
    }

    /** 条目任何字符串值里出现 `${NAME}` 引用就为真（managed-mcp.json 免 allowlist 的判定） */
    private fun rawUsesVars(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.any(::rawUsesVars)
        is JsonArray -> element.any(::rawUsesVars)
        is JsonPrimitive -> element.isString && McpVars.usesVars(element.content)
        JsonNull -> false
    }

    private fun validName(name: String): Boolean = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun stringMapOf(obj: JsonObject, key: String): Map<String, String> {
        val o = obj[key] as? JsonObject ?: return emptyMap()
        return o.entries.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }.toMap(LinkedHashMap())
    }

    private fun JsonObject.stringArrayOf(key: String): List<String> =
        ((this[key] as? JsonArray) ?: return emptyList()).mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    private fun JsonObject.boolOf(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
