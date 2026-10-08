package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.GuestPaths
import kotlinx.serialization.json.*
import dev.min.agent.ext.plugins.PluginParser.Companion.KNOWN_TOP_LEVEL

internal fun PluginParser.discover(
        rootName: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        request: PluginRequest,
    ): LoadedPlugin {
        val marketplaceName = request.ref?.marketplace
        val install = if (marketplaceName == null) "pluginDir" else "marketplace"
        val entryIsManifest = manifest == null && entry != null
        val ns: String
        val nameSource: PluginNameSource
        when {
            manifest != null -> {
                ns = manifest.strOrNull("name") ?: bug("装载成功但清单缺 name")
                nameSource = PluginNameSource.MANIFEST
            }
            entry != null -> {
                ns = entry.strOrNull("name") ?: bug("条目缺 name")
                nameSource = PluginNameSource.MARKETPLACE_ENTRY
            }
            else -> {
                ns = rootName
                if (!validPluginName(ns)) bug("directory name \"$ns\" 不符合插件 name 字符规则")
                nameSource = PluginNameSource.DIRECTORY_NAME
            }
        }
        val refName = if (install == "marketplace") entry?.strOrNull("name") ?: request.ref?.name ?: ns else ns
        val ref = PluginRef(refName, marketplaceName)
        val manifestObj = (manifest ?: entry)?.let { m ->
            LoadedPluginManifest(
                ref = ref,
                displayName = m.strOrNull("displayName"),
                version = m.strOrNull("version"),
                description = m.strOrNull("description"),
                authorName = m.obj("author")?.strOrNull("name"),
                authorEmail = m.obj("author")?.strOrNull("email"),
                authorUrl = m.obj("author")?.strOrNull("url"),
                homepage = m.strOrNull("homepage"),
                repository = m.strOrNull("repository"),
                license = m.strOrNull("license"),
                keywords = m.arr("keywords")?.map { e -> e.jsonPrimitive.content } ?: emptyList(),
                metadata = m.obj("metadata"),
                defaultEnabled = m.boolOrNull("defaultEnabled") ?: true,
                dependencies = m.arr("dependencies")?.map { d ->
                    when (d) {
                        is JsonPrimitive -> PluginDependency(d.content)
                        is JsonObject -> PluginDependency(
                            d.strOrNull("name") ?: bug("依赖对象缺 name"),
                            d.strOrNull("marketplace"), d.strOrNull("version"),
                        )
                        else -> bug("依赖条目形状不认识")
                    }
                } ?: emptyList(),
                settings = m.obj("settings"),
                userConfig = parseUserConfig(m.obj("userConfig")),
                channels = parseChannels(m.arr("channels")),
                strippedUnknownTopLevel = m.keys.filter { it !in KNOWN_TOP_LEVEL }.sorted(),
            )
        }
        val displayName = if (install == "marketplace") entry?.strOrNull("displayName") ?: manifestObj?.displayName else manifestObj?.displayName
        val version = manifestObj?.version ?: entry?.strOrNull("version")
        val defaultEnabled = entry?.boolOrNull("defaultEnabled") ?: manifestObj?.defaultEnabled ?: true
        val enabledId = if (install == "marketplace") "$refName@$marketplaceName" else refName
        val enabled = request.enabledPlugins[enabledId] ?: defaultEnabled

        return LoadedPlugin(
            ref = ref,
            namespace = ns,
            nameSource = nameSource,
            root = rootName,
            enabled = enabled,
            displayName = displayName,
            manifest = manifestObj?.copy(version = version, defaultEnabled = defaultEnabled),
            warnings = if (files.containsKey("CLAUDE.md")) listOf("CLAUDE.md at the plugin root is not loaded as project context") else emptyList(),
            skills = discoverSkills(rootName, ns, files, manifest, entry, entryIsManifest),
            commands = discoverCommands(ns, files, manifest, entry, entryIsManifest),
            agents = discoverAgents(ns, files, manifest, entry, entryIsManifest),
            hooks = discoverHooks(files, manifest, entry, entryIsManifest),
            mcpServers = discoverMcp(files, manifest, entry, entryIsManifest).map { (k, cfg) -> PluginMcpServer(k, "plugin:$ns:$k", cfg) },
            lspServers = discoverLsp(files, manifest, entry, entryIsManifest).map { (k, cfg) -> PluginLspServer(k, cfg) },
            outputStyles = discoverStyles(ns, files, manifest, entry, entryIsManifest),
            themes = discoverThemes(ns, files, manifest, entry, entryIsManifest),
            monitors = discoverMonitors(files, manifest ?: entry),
            workflows = discoverWorkflows(files, manifest, entry, entryIsManifest),
            binOnPath = files.keys.filter { it.startsWith("bin/") && it.count { c -> c == '/' } == 1 }.map { it.substringAfterLast('/') }.sorted(),
            settings = effectiveSettings(files, manifest ?: entry),
            userConfig = manifestObj?.userConfig ?: emptyList(),
            channels = manifestObj?.channels ?: emptyList(),
        )
    }

internal fun PluginParser.discoverSkills(
        rootName: String,
        ns: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginSkill> {
        val skills = mutableListOf<PluginSkill>()
        val seen = mutableSetOf<String>()
        fun add(rel: String, baseName: String) {
            val fm = frontmatter(files.getValue(rel))
            val name = "$ns:" + (fm["name"] ?: baseName)
            if (!seen.add(name)) {
                if (skills.any { it.name == name && it.skillMd == rel }) return
                componentErrors += "技能名重复：$name（$rel），跳过该组件"
                return
            }
            skills += PluginSkill(name, rel)
        }
        fun addRootSkill() {
            val fm = frontmatter(files.getValue("SKILL.md"))
            if (fm["name"] == null) bug("根级 SKILL.md 缺 frontmatter name：缺省命名原文未说明（CMP:668）")
            add("SKILL.md", fm["name"]!!)
        }
        fun scanDir(rawDir: String) {
            // "."/"./" 指插件根（PLG:394）；空串也是根
            val dirRel = when (rawDir) {
                ".", "./", "" -> ""
                else -> rawDir.removeSuffix("/")
            }
            if (dirRel.isEmpty()) {
                if (files.containsKey("SKILL.md")) addRootSkill()
                return
            }
            if (files.containsKey("$dirRel/SKILL.md")) {
                add("$dirRel/SKILL.md", dirRel.substringAfterLast('/'))
                return
            }
            files.keys.filter { it.startsWith("$dirRel/") && it.endsWith("/SKILL.md") }.sorted().forEach {
                add(it, it.removeSuffix("/SKILL.md").substringAfterLast('/'))
            }
        }
        fun pathsOf(v: JsonElement?): List<String> = when (v) {
            null -> emptyList()
            is JsonPrimitive -> listOf(v.content)
            is JsonArray -> v.map { e -> e.jsonPrimitive.content }
            else -> bug("skills 的形状不认识")
        }
        val mSkills = manifest?.get("skills")
        val eSkills = entry?.get("skills")
        val entryRootSource = entry?.strOrNull("source") == "./" || entry?.strOrNull("source") == "."
        if (eSkills != null && entryRootSource) {
            // PLG:678：条目（source=marketplace 根）列出子目录时只装载列出的，默认 skills/ 不扫描
            if (mSkills != null) bug("条目按 marketplace 根列 skills、清单又有 skills 键：两者怎么叠加原文未说明")
            pathsOf(eSkills).forEach { scanDir(it.removePrefix("./")) }
        } else if (mSkills == null && eSkills == null) {
            if (files.keys.any { it.startsWith("skills/") }) scanDir("skills")
            else if (files.containsKey("SKILL.md")) addRootSkill() // PLG:615
        } else {
            if (files.keys.any { it.startsWith("skills/") }) scanDir("skills") // PLG:413 默认照扫
            pathsOf(if (entryIsManifest) eSkills else mSkills).forEach { scanDir(it.removePrefix("./")) }
            if (!entryIsManifest) pathsOf(eSkills).forEach { scanDir(it.removePrefix("./")) }
        }
        return skills
    }

internal fun PluginParser.discoverCommands(
        ns: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginCommand> {
        val commands = mutableListOf<PluginCommand>()
        val seen = mutableSetOf<String>()
        fun add(c: PluginCommand) {
            if (!seen.add(c.name)) bug("命令名重复：${c.name}")
            commands += c
        }
        fun scanDir(dirRel: String) {
            files.keys.filter { it.startsWith("$dirRel/") && it.endsWith(".md") }.sorted().forEach { rel ->
                val fm = frontmatter(files.getValue(rel))
                val segs = rel.removePrefix("$dirRel/").removeSuffix(".md").split('/')
                add(PluginCommand((listOf(ns) + segs).joinToString(":"), sourcePath = rel, description = fm["description"]))
            }
        }
        fun addFile(rel: String) {
            val fm = frontmatter(files.getValue(rel))
            add(PluginCommand("$ns:" + rel.removeSuffix(".md").substringAfterLast('/'), sourcePath = rel, description = fm["description"]))
        }
        fun addValue(v: JsonElement) {
            when (v) {
                is JsonObject -> v.forEach { (k, item) ->
                    item as? JsonObject ?: bug("commands.$k 的值不是对象")
                    add(
                        PluginCommand(
                            "$ns:$k",
                            sourcePath = item.strOrNull("source")?.removePrefix("./"),
                            content = item.strOrNull("content"),
                            description = item.strOrNull("description"),
                            argumentHint = item.strOrNull("argumentHint"),
                            model = item.strOrNull("model"),
                            allowedTools = item.arr("allowedTools")?.map { e -> e.jsonPrimitive.content } ?: emptyList(),
                        ),
                    )
                }
                is JsonPrimitive -> {
                    val rel = v.content.removePrefix("./").removeSuffix("/")
                    if (files.containsKey(rel)) addFile(rel) else scanDir(rel)
                }
                is JsonArray -> v.forEach { addValue(it) }
                else -> bug("commands 的形状不认识")
            }
        }
        val mCmds = manifest?.get("commands")
        if (entryIsManifest) {
            if (entry?.containsKey("commands") == true) addValue(entry.getValue("commands")) else scanDir("commands")
        } else {
            when {
                mCmds != null -> addValue(mCmds) // PLG:149 取代默认
                files.keys.any { it.startsWith("commands/") } -> scanDir("commands")
            }
            if (entry?.containsKey("commands") == true) addValue(entry.getValue("commands")) // PLG:675 追加
        }
        return commands
    }

internal fun PluginParser.discoverAgents(
        ns: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginAgent> {
        val agents = mutableListOf<PluginAgent>()
        val seen = mutableSetOf<String>()
        fun addFile(rel: String, withSubfolders: Boolean) {
            val fm = frontmatter(files.getValue(rel))
            val base = rel.removeSuffix(".md").removePrefix("agents/")
            val lastSeg = base.substringAfterLast('/')
            val segs = (if (withSubfolders) base.split('/') else listOf(lastSeg)).toMutableList()
            segs[segs.lastIndex] = fm["name"] ?: lastSeg // CMP:732 frontmatter name 只替换文件名段
            val name = (listOf(ns) + segs).joinToString(":")
            if (!seen.add(name)) bug("agent 名重复：$name")
            agents += PluginAgent(name, rel, fm["model"])
        }
        fun valueOf(v: JsonElement?): List<String> = when (v) {
            null -> emptyList()
            is JsonPrimitive -> listOf(v.content)
            is JsonArray -> v.map { e -> e.jsonPrimitive.content }
            else -> bug("agents 的形状不认识")
        }
        val mA = manifest?.get("agents")
        val eA = entry?.get("agents")
        if (entryIsManifest && eA != null) {
            valueOf(eA).forEach { addFile(it.removePrefix("./"), withSubfolders = false) }
        } else if (mA != null) {
            valueOf(mA).forEach { addFile(it.removePrefix("./"), withSubfolders = false) } // PLG:150/:733
        } else {
            files.keys.filter { it.startsWith("agents/") && it.endsWith(".md") }.sorted()
                .forEach { addFile(it, withSubfolders = true) } // CMP:730
        }
        if (!entryIsManifest) valueOf(eA).forEach { addFile(it.removePrefix("./"), withSubfolders = false) } // PLG:675
        return agents
    }

internal fun PluginParser.discoverHooks(
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): Map<String, List<PluginHookHandler>> {
        val hooks = linkedMapOf<String, MutableList<PluginHookHandler>>()
        fun flatten(v: JsonElement, from: String): List<PluginHookHandler> {
            val arr = v as? JsonArray ?: bug("$from 的值不是数组")
            val out = mutableListOf<PluginHookHandler>()
            arr.forEach { e ->
                val o = e as? JsonObject ?: bug("$from 条目不是对象")
                val inner = o.arr("hooks") ?: bug("$from 缺 hooks 数组")
                inner.forEach { h ->
                    val ho = h as? JsonObject ?: bug("$from 的 handler 不是对象")
                    if (ho.strOrNull("type") != "command") return@forEach
                    out += PluginHookHandler(o.strOrNull("matcher"), ho.strOrNull("command"), ho.arr("args")?.map { it.jsonPrimitive.content } ?: emptyList(), ho)
                }
            }
            return out
        }
        fun eventMap(o: JsonObject, from: String) {
            o.forEach { (event, v) ->
                // 原文只规定合并，不规定同事件内部顺序；按默认文件→清单的声明顺序追加。
                hooks.getOrPut(event) { mutableListOf() } += flatten(v, "$from/$event")
            }
        }
        files["hooks/hooks.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            eventMap(o.obj("hooks") ?: bug("hooks/hooks.json 缺 \"hooks\" 包裹"), "hooks/hooks.json")
        }
        fun addValue(v: JsonElement, from: String) {
            when (v) {
                is JsonPrimitive -> {
                    val rel = v.content.removePrefix("./")
                    val f = files[rel] ?: bug("hooks 文件不存在 $rel")
                    eventMap(parseJson(f).jsonObject.obj("hooks") ?: bug("$rel 缺 \"hooks\" 包裹"), rel)
                }
                is JsonObject -> eventMap(v, from)
                is JsonArray -> v.forEach { addValue(it, from) }
                else -> bug("hooks 的形状不认识")
            }
        }
        manifest?.get("hooks")?.let { addValue(it, "manifest") }
        if (entryIsManifest && entry?.containsKey("hooks") == true) {
            // 条目即清单：hooks 只吃内联事件表（PLG:674）；文件/数组形式在 validate 里已记 ENTRY_HOOKS_FILE
            (entry["hooks"] as? JsonObject)?.let { eventMap(it, "entry") }
        } else if (entry?.containsKey("hooks") == true) {
            val eh = entry.obj("hooks") ?: bug("strict 追加的条目 hooks 必须是内联事件表（PLG:674）")
            eh.forEach { (event, v) -> hooks[event] = flatten(v, "entry/$event").toMutableList() } // PLG:675 事件级替换
        }
        return hooks.mapValues { it.value.toList() }
    }

internal fun PluginParser.discoverMcp(
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): LinkedHashMap<String, JsonObject> {
        val mcp = linkedMapOf<String, JsonObject>()
        fun addMap(o: JsonObject, from: String) {
            if (o.containsKey("mcpServers")) bug("$from 应是裸的「服务器名 → 配置」map（PLG:311）")
            o.forEach { (k, s) ->
                s as? JsonObject ?: bug("mcpServers.$k 不是对象")
                mcp[k] = s // PLG:305 同名后到取代
            }
        }
        fun addValue(v: JsonElement, from: String) {
            when (v) {
                is JsonObject -> addMap(v, from)
                is JsonPrimitive -> {
                    val raw = v.content
                    if (raw.startsWith("https://")) return // 安装 bundle 不属于本地 JSON 加载；不能在解析时偷偷下载。
                    val rel = raw.removePrefix("./")
                    if (rel.endsWith(".mcpb") || rel.endsWith(".dxt")) return // bundle 只验扩展名（PLG:316）
                    addMap(parseJson(files.getValue(rel)).jsonObject, rel)
                }
                is JsonArray -> v.forEach { addValue(it, from) }
                else -> bug("mcpServers 的形状不认识")
            }
        }
        files[".mcp.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            addMap(o.obj("mcpServers") ?: o, ".mcp.json") // CMP:806 包裹可省
        }
        if (entryIsManifest && entry?.containsKey("mcpServers") == true) addValue(entry.getValue("mcpServers"), "entry")
        else manifest?.get("mcpServers")?.let { addValue(it, "manifest") }
        return mcp
    }

internal fun PluginParser.discoverLsp(
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): LinkedHashMap<String, JsonObject> {
        val lsp = linkedMapOf<String, JsonObject>()
        fun addMap(o: JsonObject) {
            o.forEach { (k, s) ->
                s as? JsonObject ?: bug("lspServers.$k 不是对象")
                lsp[k] = s // PLG:322-323 同名后到取代
            }
        }
        fun addValue(v: JsonElement) {
            when (v) {
                is JsonObject -> addMap(v)
                is JsonPrimitive -> addMap(parseJson(files.getValue(v.content.removePrefix("./"))).jsonObject)
                is JsonArray -> v.forEach { addValue(it) }
                else -> bug("lspServers 的形状不认识")
            }
        }
        files[".lsp.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            // CMP:869：任一条目非法，整个文件在装载时跳过
            if (o.entries.none { lspProblem(it.value) != null }) addMap(o)
        }
        if (entryIsManifest && entry?.containsKey("lspServers") == true) addValue(entry.getValue("lspServers"))
        else manifest?.get("lspServers")?.let { addValue(it) }
        return lsp
    }

internal fun PluginParser.discoverStyles(
        ns: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginOutputStyle> {
        val styles = mutableListOf<PluginOutputStyle>()
        fun addFile(rel: String) {
            val fm = frontmatter(files.getValue(rel))
            styles += PluginOutputStyle("$ns:" + (fm["name"] ?: rel.removeSuffix(".md").substringAfterLast('/')), rel)
        }
        fun addValue(v: JsonElement) {
            val items = when (v) {
                is JsonPrimitive -> listOf(v.content)
                is JsonArray -> v.map { e -> e.jsonPrimitive.content }
                else -> bug("outputStyles 的形状不认识")
            }
            items.forEach { raw ->
                val rel = raw.removePrefix("./").removeSuffix("/")
                if (files.containsKey(rel)) addFile(rel)
                else files.keys.filter { it.startsWith("$rel/") && it.endsWith(".md") }.sorted().forEach(::addFile)
            }
        }
        val mS = manifest?.get("outputStyles")
        if (entryIsManifest && entry?.containsKey("outputStyles") == true) addValue(entry.getValue("outputStyles"))
        else {
            if (mS != null) addValue(mS) // PLG:154/:412 取代默认
            else files.keys.filter { it.startsWith("output-styles/") && it.endsWith(".md") }.sorted().forEach(::addFile)
            if (!entryIsManifest && entry?.containsKey("outputStyles") == true) addValue(entry.getValue("outputStyles")) // PLG:675
        }
        return styles
    }

internal fun PluginParser.discoverThemes(
        ns: String,
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginTheme> {
        val themes = mutableListOf<PluginTheme>()
        fun addFile(rel: String) {
            val name = parseJson(files.getValue(rel)).jsonObject.strOrNull("name")
                ?: bug("主题 $rel 缺 name 字段（CMP:929 主题按文件里的 name 出现）")
            themes += PluginTheme(name, rel)
        }
        fun addValue(v: JsonElement) {
            val items = when (v) {
                is JsonPrimitive -> listOf(v.content)
                is JsonArray -> v.map { e -> e.jsonPrimitive.content }
                else -> bug("themes 的形状不认识")
            }
            items.forEach { raw ->
                val rel = raw.removePrefix("./").removeSuffix("/")
                if (files.containsKey(rel)) addFile(rel)
                else files.keys.filter { it.startsWith("$rel/") && it.endsWith(".json") }.sorted().forEach(::addFile)
            }
        }
        val mT = manifest?.obj("experimental")?.get("themes")
        if (entryIsManifest && entry?.containsKey("themes") == true) addValue(entry.getValue("themes"))
        else {
            if (mT != null) addValue(mT) // PLG:157 取代默认
            else files.keys.filter { it.startsWith("themes/") && it.endsWith(".json") }.sorted().forEach(::addFile)
            if (!entryIsManifest && entry?.containsKey("themes") == true) addValue(entry.getValue("themes")) // PLG:675
        }
        return themes
    }

internal fun PluginParser.discoverMonitors(files: Map<String, String>, manifest: JsonObject?): List<PluginMonitor> {
        val monitors = mutableListOf<PluginMonitor>()
        fun addArray(a: JsonArray) {
            a.forEach { e ->
                val o = e as? JsonObject ?: bug("monitors 条目不是对象")
                monitors += PluginMonitor(
                    o.strOrNull("name") ?: bug("监视器缺 name"),
                    o.strOrNull("command") ?: bug("监视器缺 command"),
                    o.strOrNull("description") ?: bug("监视器缺 description"),
                    o.strOrNull("when"),
                )
            }
        }
        val mM = manifest?.obj("experimental")?.get("monitors")
        when {
            mM is JsonPrimitive -> addArray(parseJson(files.getValue(mM.content.removePrefix("./"))).jsonArray)
            mM is JsonArray -> addArray(mM)
            mM == null -> files["monitors/monitors.json"]?.let { addArray(parseJson(it).jsonArray) } // PLG:360
        }
        return monitors
    }

internal fun PluginParser.discoverWorkflows(
        files: Map<String, String>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginWorkflow> {
        val workflows = mutableListOf<PluginWorkflow>()
        fun addValue(v: JsonElement) {
            val items = when (v) {
                is JsonPrimitive -> listOf(v.content)
                is JsonArray -> v.map { e -> e.jsonPrimitive.content }
                else -> bug("workflows 的形状不认识")
            }
            items.forEach { raw ->
                val rel = raw.removePrefix("./").removeSuffix("/")
                if (files.containsKey(rel)) workflows += PluginWorkflow(rel)
                else files.keys.filter { it.startsWith("$rel/") && it.endsWith(".js") }.sorted()
                    .forEach { workflows += PluginWorkflow(it) }
            }
        }
        val mW = manifest?.get("workflows")
        if (entryIsManifest && entry?.containsKey("workflows") == true) addValue(entry.getValue("workflows"))
        else {
            if (mW != null) addValue(mW) // PLG:155 取代默认
            else files.keys.filter { it.startsWith("workflows/") && it.endsWith(".js") }.sorted()
                .forEach { workflows += PluginWorkflow(it) }
        }
        return workflows
    }

internal fun PluginParser.effectiveSettings(files: Map<String, String>, manifest: JsonObject?): JsonObject? {
        val supported = setOf("agent", "subagentStatusLine") // PLG:214/:901 只有这两个键生效
        val sj = files["settings.json"]?.let { parseJson(it).jsonObject }
        return when {
            // CMP:917 settings.json 设了任一生效键 → 整个文件优先
            sj != null && sj.keys.any { it in supported } -> JsonObject(sj.filterKeys { it in supported })
            manifest?.obj("settings") != null -> JsonObject(manifest.getValue("settings").jsonObject.filterKeys { it in supported })
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }
