package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.GuestPaths
import kotlinx.serialization.json.*

internal class PluginParser(internal val directories: Set<String>) {
    internal val componentErrors = mutableListOf<String>()
    fun parse(request: PluginRequest, root: String, files: Map<String, String>): PluginLoadOutcome {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val manifest = files[".claude-plugin/plugin.json"]?.let { normalizeLegacy(parseJson(it) as? JsonObject ?: bug("plugin.json 顶层必须是对象"), warnings) }
        if (manifest != null) validateTypes(manifest)
        val entry = request.entry ?: request.ref?.marketplace?.let { buildJsonObject { put("name", request.ref.name) } }
        if (entry != null) validateTypes(entry)
        val rootName = root.substringAfterLast('/')

        val violations = mutableListOf<Violation>()
        fun safeDeclarations(raw: JsonObject?): JsonObject? {
            raw ?: return null
            val invalid = mutableSetOf<Pair<String, String>>()
            collectPaths(raw).forEach { (key, path) ->
                val bad = mutableListOf<Violation>()
                checkPath(key, path, files, rootName, bad)
                if (key == "mcpServers" && !path.startsWith("https://") && !path.endsWith(".json") && !path.endsWith(".mcpb") && !path.endsWith(".dxt")) {
                    bad += Violation(Kind.MCP_BUNDLE_EXT, "mcpServers 路径 \"$path\" 必须是 .json / .mcpb / .dxt 或 https URL")
                }
                if (bad.isNotEmpty()) { invalid += key to path; violations += bad }
            }
            fun filter(key: String, value: JsonElement): JsonElement? = when (value) {
                is JsonPrimitive -> value.takeUnless { key to value.content in invalid }
                is JsonArray -> JsonArray(value.mapNotNull { filter(key, it) })
                is JsonObject -> if (key == "commands") JsonObject(value.filter { (name, command) ->
                    val source = (command as? JsonObject)?.strOrNull("source")
                    source == null || "commands.$name.source" to source !in invalid
                }) else value
            }
            return JsonObject(raw.mapValues { (key, value) -> filter(key, value) ?: JsonArray(emptyList()) })
        }
        val safeManifest = safeDeclarations(manifest)
        val safeEntry = safeDeclarations(entry)
        validate(safeManifest, safeEntry, files, rootName, violations)
        violations.forEach {
            if (it.kind == Kind.CLAUDE_MD || it.kind == Kind.DEFAULT_IGNORED) warnings += it.detail + (it.docFragment?.let { fragment -> " [$fragment]" } ?: "")
            else errors += it.detail + (it.docFragment?.let { fragment -> " [$fragment]" } ?: "")
        }
        if (violations.any { it.kind.fatal }) return PluginLoadOutcome(warnings = warnings, errors = errors)
        val loaded = discover(rootName, files, normalizeDeclarations(safeManifest), normalizeDeclarations(safeEntry), request)
        errors += componentErrors
        if (loaded.lspServers.isNotEmpty()) warnings += "LSP 未支持：仅保留声明，不启动服务器"
        if (loaded.monitors.isNotEmpty()) warnings += "monitors 未支持：仅保留声明，不启动后台任务"
        return PluginLoadOutcome(loaded.copy(root = root, warnings = warnings, errors = errors), warnings, errors)
    }

    private fun normalizeLegacy(manifest: JsonObject, warnings: MutableList<String>): JsonObject {
        val experiment = manifest.obj("experimental").orEmpty().toMutableMap()
        for (key in listOf("themes", "monitors")) if (key in manifest) {
            warnings += "顶层 $key 已弃用，请使用 experimental.$key"
            experiment.putIfAbsent(key, manifest.getValue(key))
        }
        return if (experiment.isEmpty()) manifest else JsonObject(manifest + ("experimental" to JsonObject(experiment)))
    }

    internal fun normalizeDeclarations(m: JsonObject?): JsonObject? {
        m ?: return null
        fun path(v: JsonElement): JsonElement = when (v) {
            is JsonPrimitive -> if (v.isString && v.content.startsWith("./")) JsonPrimitive("." + GuestPaths.normalize("/" + v.content)) else v
            is JsonArray -> JsonArray(v.map(::path))
            else -> v
        }
        val out = m.toMutableMap()
        for (key in listOf("skills", "commands", "agents", "hooks", "mcpServers", "lspServers", "outputStyles", "workflows")) {
            m[key]?.let { out[key] = path(it) }
        }
        (m["commands"] as? JsonObject)?.let { commands -> out["commands"] = JsonObject(commands.mapValues { (_, v) ->
            val command = v as? JsonObject ?: return@mapValues v
            JsonObject(command.mapValues { (key, value) -> if (key == "source") path(value) else value })
        }) }
        (m["experimental"] as? JsonObject)?.let { experiment -> out["experimental"] = JsonObject(experiment.mapValues { (key, value) ->
            // evals 是不透明实验声明，原文明确不把它当组件路径。
            if (key in setOf("themes", "monitors") && value is JsonPrimitive) path(value) else if (key == "themes") path(value) else value
        }) }
        return JsonObject(out)
    }

    internal fun validateTypes(m: JsonObject) {
        val strings = setOf("name", "displayName", "version", "description", "homepage", "repository", "license", "icon", "documentationUrl", "supportUrl", "privacyPolicyUrl", "termsOfServiceUrl", "types")
        for (key in strings) if (key in m && (m[key] !is JsonPrimitive || !(m[key] as JsonPrimitive).isString)) bug("$key 必须是字符串")
        for (key in listOf("defaultEnabled")) if (key in m && (m[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) bug("$key 必须是布尔")
        for (key in listOf("metadata", "author", "settings", "userConfig", "experimental")) if (key in m && m[key] !is JsonObject) bug("$key 必须是对象")
        for (key in listOf("channels", "dependencies", "keywords")) if (key in m && m[key] !is JsonArray) bug("$key 必须是数组")
        m.obj("author")?.let { if (it.strOrNull("name") == null) bug("author 缺 name") }
        m.strOrNull("homepage")?.let { if (!java.net.URI(it).isAbsolute) bug("homepage 必须是绝对 URL") }
    }

    // 按组件各自的追加/取代规则装配；不能用一个通用 JSON merge 掩盖差异。

    internal fun frontmatter(text: String): Map<String, String> =
        dev.min.agent.ext.config.FrontmatterParser.parse(text, "plugin").fields.mapNotNull { (k, v) ->
            (v as? dev.min.agent.ext.config.FmValue.Scalar)?.text?.let { k to it }
        }.toMap()

    internal fun markdownBody(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) return lines.drop(end + 2).joinToString("\n")
        }
        return text
    }

    internal fun pathSegments(rootName: String, raw: String): List<String> {
        val out = mutableListOf<String>()
        for (seg in ("$rootName/" + raw.removePrefix("./")).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else out += ".."
                else -> out += seg
            }
        }
        return out
    }

    internal fun collectPaths(m: JsonObject?): List<Pair<String, String>> {
        m ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        fun add(key: String, v: JsonElement?) {
            when (v) {
                null -> {}
                is JsonPrimitive -> out += key to v.content
                is JsonArray -> v.forEach { e -> if (e is JsonPrimitive) out += key to e.content }
                else -> {} // 对象形（commands object / hooks 内联 / mcp 内联）没有路径
            }
        }
        add("commands", m["commands"])
        m.obj("commands")?.forEach { (name, value) -> add("commands.$name.source", (value as? JsonObject)?.get("source")) }
        add("agents", m["agents"])
        add("skills", m["skills"])
        add("hooks", m["hooks"])
        add("mcpServers", m["mcpServers"])
        add("lspServers", m["lspServers"])
        add("outputStyles", m["outputStyles"])
        add("workflows", m["workflows"])
        add("themes", m["themes"])
        add("monitors", m["monitors"])
        m.obj("experimental")?.let {
            add("experimental.themes", it["themes"])
            add("experimental.monitors", it["monitors"])
        }
        return out
    }

    internal fun hookFileSources(m: JsonObject?): List<String> {
        m ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(v: JsonElement?) {
            when (v) {
                is JsonPrimitive -> out += v.content.removePrefix("./")
                is JsonArray -> v.forEach { walk(it) }
                else -> {}
            }
        }
        walk(m["hooks"])
        return out
    }

    internal fun mcpStringSources(m: JsonObject?): List<String> {
        m ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(v: JsonElement?) {
            when (v) {
                is JsonPrimitive -> out += v.content
                is JsonArray -> v.forEach { walk(it) }
                else -> {}
            }
        }
        walk(m["mcpServers"])
        return out
    }

    internal fun lspConfigSources(files: Map<String, String>, m: JsonObject?): List<Pair<String, JsonObject>> {
        m ?: return emptyList()
        val out = mutableListOf<Pair<String, JsonObject>>()
        fun walk(v: JsonElement?) {
            when (v) {
                is JsonObject -> v.forEach { (k, s) -> out += "lspServers.$k" to (s as? JsonObject ?: bug("lspServers.$k 不是对象")) }
                is JsonPrimitive -> {
                    val o = parseJson(files.getValue(v.content.removePrefix("./"))).jsonObject
                    o.forEach { (k, s) -> out += "$k" to (s as? JsonObject ?: bug("lspServers.$k 不是对象")) }
                }
                is JsonArray -> v.forEach { walk(it) }
                else -> {}
            }
        }
        walk(m["lspServers"])
        return out
    }

    internal fun monitorArrays(files: Map<String, String>, m: JsonObject?): List<JsonArray> {
        val out = mutableListOf<JsonArray>()
        files["monitors/monitors.json"]?.let { out += parseJson(it).jsonArray }
        val mM = m?.obj("experimental")?.get("monitors")
        when {
            mM is JsonPrimitive -> out += parseJson(files.getValue(mM.content.removePrefix("./"))).jsonArray
            mM is JsonArray -> out += mM
        }
        return out
    }

    internal fun effectiveMcpKeys(files: Map<String, String>, manifest: JsonObject?, entry: JsonObject?): Set<String> {
        val keys = mutableSetOf<String>()
        fun addMap(o: JsonObject) {
            if (o.containsKey("mcpServers")) bug("文件形式的 mcpServers 应是裸 map（PLG:311）")
            keys += o.keys
        }
        files[".mcp.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            addMap(o.obj("mcpServers") ?: o)
        }
        val src = if (manifest != null) manifest["mcpServers"] else entry?.get("mcpServers")
        when (src) {
            is JsonObject -> addMap(src)
            is JsonPrimitive -> if (!src.content.startsWith("https://") && !src.content.endsWith(".mcpb") && !src.content.endsWith(".dxt")) {
                addMap(parseJson(files.getValue(src.content.removePrefix("./"))).jsonObject)
            }
            is JsonArray -> src.forEach { s ->
                when (s) {
                    is JsonObject -> addMap(s)
                    is JsonPrimitive -> if (!s.content.startsWith("https://") && !s.content.endsWith(".mcpb") && !s.content.endsWith(".dxt")) {
                        addMap(parseJson(files.getValue(s.content.removePrefix("./"))).jsonObject)
                    }
                    else -> {}
                }
            }
            else -> {}
        }
        return keys
    }

    internal fun parseUserConfig(o: JsonObject?): List<PluginUserConfigOption> {
        o ?: return emptyList()
        return o.map { (key, v) ->
            val item = v as? JsonObject ?: bug("userConfig.$key 不是对象")
            PluginUserConfigOption(
                key = key,
                type = item.strOrNull("type") ?: bug("userConfig.$key 缺 type"),
                title = item.strOrNull("title") ?: bug("userConfig.$key 缺 title"),
                description = item.strOrNull("description") ?: bug("userConfig.$key 缺 description"),
                required = item.boolOrNull("required") ?: false,
                default = item["default"],
                options = item.arr("options")?.map { e -> e.jsonPrimitive.content },
                multiple = item.boolOrNull("multiple") ?: false,
                sensitive = item.boolOrNull("sensitive") ?: false,
                min = item["min"],
                max = item["max"],
            )
        }
    }

    internal fun parseChannels(arr: JsonArray?): List<PluginChannel> {
        arr ?: return emptyList()
        return arr.map { e ->
            val o = e as? JsonObject ?: bug("channels 条目不是对象")
            PluginChannel(
                server = o.strOrNull("server") ?: bug("channels 条目缺 server"),
                displayName = o.strOrNull("displayName"),
                userConfig = parseUserConfig(o.obj("userConfig")),
            )
        }
    }

    internal fun lspProblem(v: JsonElement): String? {
        if (v !is JsonObject) return "不是对象"
        val unknown = v.keys - LSP_FIELDS
        if (unknown.isNotEmpty()) return "未知键 $unknown（PLG:324）"
        if (v.strOrNull("command") == null) return "缺 command（PLG:328）"
        val e2l = v.obj("extensionToLanguage") ?: return "缺 extensionToLanguage（PLG:329）"
        if (e2l.isEmpty() || e2l.keys.any { !it.startsWith(".") }) return "extensionToLanguage 必须非空且键以 . 开头（PLG:329）"
        return null
    }

    internal fun nameOk(name: String): Boolean = validPluginName(name)

    internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)
    internal fun bug(message: String): Nothing = throw PluginInvalid(message)
    class Violation(val kind: Kind, val detail: String, val docFragment: String? = null, val path: String? = null)

    enum class Kind {
        NAME_REQUIRED, NAME_CHARS, PATH_PREFIX, PATH_ESCAPE, PATH_MISSING, AGENTS_NOT_MD, SKILLS_NOT_DIR,
        HOOKS_WRAPPER, MCP_BUNDLE_EXT, COMMAND_XOR, USERCONFIG_UNKNOWN, USERCONFIG_RULES,
        CHANNELS_UNKNOWN, CHANNELS_SERVER, LSP_CONFIG_RULES, LSP_FILE_INVALID, MONITOR_RULES,
        ENTRY_HOOKS_FILE, CLAUDE_MD, STRICT_CONFLICT, DEFAULT_IGNORED;

        val fatal: Boolean get() = this !in setOf(ENTRY_HOOKS_FILE, LSP_FILE_INVALID, CLAUDE_MD, DEFAULT_IGNORED,
            PATH_PREFIX, PATH_ESCAPE, PATH_MISSING, AGENTS_NOT_MD, SKILLS_NOT_DIR, HOOKS_WRAPPER, MCP_BUNDLE_EXT)
    }

    companion object {
        /** PLG:124-159 的顶层键全集（顶层 themes/monitors 是带警告的旧写法，目录别名在解析入口处理）。 */
        val KNOWN_TOP_LEVEL = setOf(
            "\$schema", "name", "displayName", "version", "description", "author", "homepage", "repository",
            "license", "keywords", "metadata", "icon", "documentationUrl", "supportUrl", "privacyPolicyUrl",
            "termsOfServiceUrl", "defaultEnabled", "dependencies", "settings", "userConfig", "types", "channels",
            "skills", "commands", "agents", "hooks", "mcpServers", "lspServers", "outputStyles", "workflows",
            "experimental", "themes", "monitors",
        )
        val USER_CONFIG_FIELDS = setOf(
            "type", "title", "description", "required", "default", "options", "multiple", "sensitive", "min", "max",
        )
        val USER_CONFIG_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*") // PLG:424
        val LSP_FIELDS = setOf(
            "command", "extensionToLanguage", "args", "transport", "env", "initializationOptions", "settings",
            "workspaceFolder", "startupTimeout", "shutdownTimeout", "restartOnCrash", "maxRestarts", "diagnostics",
        ) // PLG:326-340
    }
}

/** MCP 命名空间沿用插件清单的名字规则；不能借用普通服务器名的较窄字符集。 */
internal fun validPluginName(name: String): Boolean =
    name.isNotEmpty() && name !in setOf(".", "..") && !Regex("[\\s@:/\\\\]").containsMatchIn(name) && name.none {
        val c = it.code
        c < 0x20 || c == 0x7f || c in 0x200b..0x200f || c in 0x202a..0x202e || c in 0x2066..0x2069
    }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

internal fun primOrNull(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal class PluginInvalid(message: String) : RuntimeException(message)
