package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.GuestPaths
import kotlinx.serialization.json.*
import dev.min.agent.ext.plugins.PluginParser.Kind
import dev.min.agent.ext.plugins.PluginParser.Violation
import dev.min.agent.ext.plugins.PluginParser.Companion.USER_CONFIG_FIELDS
import dev.min.agent.ext.plugins.PluginParser.Companion.USER_CONFIG_KEY

internal fun PluginParser.validate(
        manifest: JsonObject?,
        entry: JsonObject?,
        files: Map<String, String>,
        rootName: String,
        violations: MutableList<Violation>,
    ) {
        if (manifest != null) {
            val name = manifest.strOrNull("name")
            if (name == null) violations += Violation(Kind.NAME_REQUIRED, "清单缺 name（PLG:120 唯一必填键）")
            else if (!nameOk(name)) violations += Violation(Kind.NAME_CHARS, "name \"$name\" 不合 PLG:165 的字符规则")

        }
        collectPaths(manifest).forEach { (key, raw) -> checkPath(key, raw, files, rootName, violations) }
        if (entry != null) {
            // 条目即清单（PLG:674）：条目里的组件路径按同样规则校验
            collectPaths(entry).filterNot { (key, _) -> key == "hooks" && entry["hooks"] !is JsonObject }.forEach { (key, raw) -> checkPath(key, raw, files, rootName, violations) }
        }
        if (violations.any { it.kind.fatal }) return
        val mcpKeys = effectiveMcpKeys(files, manifest, entry)
        files["hooks/hooks.json"]?.let { checkWrapped(it, "hooks/hooks.json", violations) }
        hookFileSources(manifest).forEach { rel ->
            files[rel]?.let { checkWrapped(it, rel, violations) }
        }
        if (manifest == null && entry != null) hookFileSources(entry).forEach { rel ->
            files[rel]?.let { checkWrapped(it, rel, violations) }
        }
        mcpStringSources(manifest).forEach { raw ->
            if (!raw.startsWith("https://") && !raw.endsWith(".json") && !raw.endsWith(".mcpb") && !raw.endsWith(".dxt")) {
                violations += Violation(Kind.MCP_BUNDLE_EXT, "mcpServers 的 \"$raw\" 既不是 .json 配置也不是 .mcpb/.dxt bundle，也不是 https URL（PLG:309-316）", path = raw)
            }
        }
        if (manifest == null && entry != null) mcpStringSources(entry).forEach { raw ->
            if (!raw.startsWith("https://") && !raw.endsWith(".json") && !raw.endsWith(".mcpb") && !raw.endsWith(".dxt")) {
                violations += Violation(Kind.MCP_BUNDLE_EXT, "条目 mcpServers 的 \"$raw\" 不是合法形状（PLG:309-316）", path = raw)
            }
        }
        checkCommandsXor(manifest?.get("commands"), "commands", violations)
        checkUserConfig(manifest?.get("userConfig"), violations)
        checkChannels(manifest?.get("channels"), mcpKeys, violations)
        (manifest?.get("channels") as? JsonArray)?.forEach { checkUserConfig((it as? JsonObject)?.get("userConfig"), violations) }
        if (manifest == null && entry != null) {
            checkCommandsXor(entry["commands"], "entry.commands", violations)
            checkUserConfig(entry["userConfig"], violations)
            checkChannels(entry["channels"], mcpKeys, violations)
        }
        lspConfigSources(files, manifest).forEach { (label, config) ->
            lspProblem(config)?.let { violations += Violation(Kind.LSP_CONFIG_RULES, "清单 lspServers 的 $label 非法：$it（PLG:324）") }
        }
        files[".lsp.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            o.forEach { (key, config) ->
                lspProblem(config)?.let {
                    violations += Violation(
                        Kind.LSP_FILE_INVALID,
                        ".lsp.json 的 $key 非法：$it —— 整个文件在装载时跳过（CMP:869）",
                        docFragment = "Invalid LSP server config",
                    )
                    return@forEach
                }
            }
        }
        monitorArrays(files, manifest).forEach { arr ->
            arr.forEach { e ->
                val o = e as? JsonObject ?: bug("monitors 条目不是对象")
                val unknown = o.keys - setOf("name", "command", "description", "when")
                val missing = listOf("name", "command", "description").filter { o.strOrNull(it) == null }
                val whenBad = o.strOrNull("when")?.let { it != "always" && !it.startsWith("on-skill-invoke:") } == true
                if (unknown.isNotEmpty() || missing.isNotEmpty() || whenBad) {
                    violations += Violation(
                        Kind.MONITOR_RULES,
                        "monitors 条目非法（未知键=$unknown 缺=$missing when 形状错=$whenBad，PLG:362-369/:97）",
                    )
                }
            }
        }
        if (manifest != null) {
            // PLG:416：取代型键把默认目录晾在一边时警告「Default <folder>/ folder is ignored …」；
            // 把默认目录显式列进键里就不警告（PLG:418）。对象形（如 commands object）永远算「晾着」
            val replaceKeys = listOf(
                "commands" to "commands", "agents" to "agents",
                "outputStyles" to "output-styles", "workflows" to "workflows",
                "experimental.themes" to "themes",
            )
            for ((key, defaultDir) in replaceKeys) {
                val value: JsonElement? = if (key == "experimental.themes") manifest.obj("experimental")?.get("themes") else manifest[key]
                if (value == null) continue
                if (!files.keys.any { it.startsWith("$defaultDir/") }) continue
                val paths = when (value) {
                    is JsonPrimitive -> listOf(value.content)
                    is JsonArray -> value.map { e -> e.jsonPrimitive.content }
                    else -> emptyList()
                }
                val inside = paths.any { raw ->
                    raw.startsWith("./") && pathSegments(rootName, raw).drop(1).joinToString("/")
                        .let { it == defaultDir || it.startsWith("$defaultDir/") }
                }
                if (!inside) violations += Violation(
                    Kind.DEFAULT_IGNORED,
                    "Default $defaultDir/ folder is ignored because the manifest sets \"$key\"（PLG:416）",
                    docFragment = "folder is ignored",
                )
            }
        }
        if (files.containsKey("CLAUDE.md")) {
            violations += Violation(Kind.CLAUDE_MD, "插件根的 CLAUDE.md 不作为上下文装载（CMP:670）", docFragment = "not loaded as project context")
        }
        if (entry != null) {
            val eh = entry["hooks"]
            if (eh is JsonPrimitive || eh is JsonArray) {
                violations += Violation(
                    Kind.ENTRY_HOOKS_FILE,
                    "条目 hooks 用了文件/数组形式，只支持内联对象（PLG:674）",
                    docFragment = "not yet supported in a marketplace entry",
                )
            }
            if (manifest != null && entry.boolOrNull("strict") == false &&
                listOf("commands", "agents", "skills", "hooks", "outputStyles", "themes").any { entry.containsKey(it) }
            ) {
                violations += Violation(
                    Kind.STRICT_CONFLICT,
                    "strict=false 的条目声明了组件（PLG:676）",
                    docFragment = "conflicting manifests",
                )
            }
            if (manifest != null && listOf("mcpServers", "lspServers", "workflows", "userConfig", "channels", "settings").any { entry.containsKey(it) }) {
                bug("条目声明了 ${entry.keys} 里的非追加键：PLG:675 的追加清单只有 commands/agents/skills/outputStyles/themes，其余怎么处理原文未说明——本期拒绝此歧义组合")
            }
        }
    }

internal fun PluginParser.checkPath(
        key: String,
        raw: String,
        files: Map<String, String>,
        rootName: String,
        violations: MutableList<Violation>,
    ) {
        if (key == "skills" && (raw == "." || raw == "./")) return // PLG:394：指插件根
        if (key == "mcpServers" && raw.startsWith("https://")) return // PLG:394：bundle URL
        if (!raw.startsWith("./")) {
            violations += Violation(Kind.PATH_PREFIX, "$key 路径 \"$raw\" 没有 ./ 前缀（PLG:392）", path = raw)
            return
        }
        val segs = pathSegments(rootName, raw)
        if (segs.firstOrNull() != rootName) {
            violations += Violation(Kind.PATH_ESCAPE, "$key 路径 \"$raw\" 解析到插件根之外（PLG:403）", docFragment = "escapes plugin directory", path = raw)
            return
        }
        val rel = segs.drop(1).joinToString("/")
        val exists = rel in directories || files.containsKey(rel)
        if (!exists) {
            violations += Violation(Kind.PATH_MISSING, "$key 路径 \"$raw\" 不存在（PLG:404）", docFragment = "not found", path = raw)
            return
        }
        if (key == "agents" && !(files.containsKey(rel) && rel.endsWith(".md"))) {
            violations += Violation(Kind.AGENTS_NOT_MD, "agents 条目 \"$raw\" 必须是 .md 文件（PLG:222）", path = raw)
        }
        if (key == "skills" && rel !in directories) {
            violations += Violation(Kind.SKILLS_NOT_DIR, "skills 条目 \"$raw\" 必须是目录（PLG:222）", path = raw)
        }
    }

internal fun PluginParser.checkWrapped(f: String, label: String, violations: MutableList<Violation>) {
        val o = parseJson(f).jsonObject
        if (o["hooks"] !is JsonObject) {
            violations += Violation(Kind.HOOKS_WRAPPER, "$label 缺顶层 \"hooks\" 包裹，装载失败（PLG:262）")
        }
    }

internal fun PluginParser.checkCommandsXor(value: JsonElement?, label: String, violations: MutableList<Violation>) {
        val o = value as? JsonObject ?: return
        o.forEach { (k, v) ->
            val item = v as? JsonObject ?: return@forEach
            if (item.containsKey("source") == item.containsKey("content")) {
                violations += Violation(Kind.COMMAND_XOR, "$label.$k 的 source/content 必须二选一（PLG:236）")
            }
        }
    }

internal fun PluginParser.checkUserConfig(value: JsonElement?, violations: MutableList<Violation>) {
        val o = value as? JsonObject ?: return
        o.forEach { (key, v) ->
            val item = v as? JsonObject ?: bug("userConfig.$key 不是对象")
            val unknown = item.keys - USER_CONFIG_FIELDS
            if (unknown.isNotEmpty()) violations += Violation(Kind.USERCONFIG_UNKNOWN, "userConfig.$key 有未知键 $unknown（PLG:97/:426）")
            if (!USER_CONFIG_KEY.matches(key)) violations += Violation(Kind.USERCONFIG_RULES, "userConfig 键 \"$key\" 不合 PLG:424 的规则")
            val type = (item["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type == null || type !in setOf("string", "number", "boolean", "directory", "file")) {
                violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 type 缺失或不在五值集里（PLG:430）")
            }
            listOf("title", "description").forEach {
                if ((item[it] as? JsonPrimitive)?.isString != true) violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 缺 $it（PLG:431-432）")
            }
            for (flag in listOf("required", "multiple", "sensitive")) {
                if (flag in item && (item[flag] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null)
                    violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 $flag 必须是布尔值")
            }
            for (bound in listOf("min", "max")) {
                if (bound in item && (item[bound] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null)
                    violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 $bound 必须是数字")
            }
            item["default"]?.takeIf { it !is JsonNull }?.let { value ->
                val valid = when (type) {
                    "number" -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull != null
                    "boolean" -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
                    "string" -> if (item.boolOrNull("multiple") == true) value is JsonArray && value.all { it is JsonPrimitive && it.isString } else value is JsonPrimitive && value.isString
                    "file", "directory" -> value is JsonPrimitive && value.isString
                    else -> false
                }
                if (!valid) violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 default 类型不符")
            }
            if (item.containsKey("options") && item["options"] !is JsonArray) {
                violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 options 应是数组（PLG:435）")
            }
            (item["options"] as? JsonArray)?.let { opts ->
                if (type != "string" || item.boolOrNull("multiple") == true || item.boolOrNull("sensitive") == true) {
                    violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 options 只能用于非 multiple、非 sensitive 的 string（PLG:484）")
                }
                val labels = opts.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bug("userConfig options 必须是字符串数组") }
                if (labels.any { it.isEmpty() || it.length > 64 }) violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的选项必须是 1-64 字符（PLG:484）")
                val d = item["default"]
                if (d != null && d !is JsonNull && item.boolOrNull("required") != true && d.jsonPrimitive.content !in labels) {
                    violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 default 必须取自 options 或 required:true（PLG:484）")
                }
            }
            if (type != "number" && (item.containsKey("min") || item.containsKey("max"))) {
                violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 min/max 只用于 number（PLG:438）")
            }
        }
    }

internal fun PluginParser.checkChannels(value: JsonElement?, mcpKeys: Set<String>, violations: MutableList<Violation>) {
        val arr = value as? JsonArray ?: return
        arr.forEach { e ->
            val o = e as? JsonObject ?: bug("channels 条目不是对象")
            val unknown = o.keys - setOf("server", "displayName", "userConfig")
            if ("userConfig" in o && o["userConfig"] !is JsonObject) bug("channels.userConfig 必须是对象")
            if ("displayName" in o && (o["displayName"] as? JsonPrimitive)?.isString != true) bug("channels.displayName 必须是字符串")
            if (unknown.isNotEmpty()) violations += Violation(Kind.CHANNELS_UNKNOWN, "channels 条目有未知键 $unknown（PLG:97/:513-518）")
            val server = (o["server"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (server == null) violations += Violation(Kind.CHANNELS_SERVER, "channels 条目缺 server（PLG:515）")
            else if (server !in mcpKeys) violations += Violation(Kind.CHANNELS_SERVER, "channels 的 server \"$server\" 不是本插件 mcpServers 的键（CMP:977）")
        }
    }
