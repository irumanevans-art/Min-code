package dev.min.agent.ext.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插件兼容样本集（`src/test/resources/plugin-compat/`，46 个样本、七个面：清单 / 组件发现 / 路径规则 /
 * 用户配置 / 环境变量 / marketplace / 命名空间）。
 *
 * Min 还没有插件加载器，所以这里没有「装载后比对」可做，测试做的是两件事：
 *  1. 探针：断言 `dev.min.agent.ext.plugins.PluginLoader` 还不存在。它落地的那天本测试会红，
 *     提醒实现者把样本接上真加载器、逐条复核 expected、摘掉 pendingFix（契约见 PluginLoaderContract.kt）。
 *  2. 自洽校验（样本即规格，规格自身必须站得住）：按原文实现的规则重算一遍——路径的 `./` 前缀/包含/存在、
 *     名字字符集、严格对象的键集、命名空间命名、hooks/mcp/lsp 的合并与取代、marketplace 条目的追加与
 *     事件级替换、${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA}/${CLAUDE_PROJECT_DIR}/${user_config.*} 的替换——
 *     任何一条与 expected.json 对不上都算样本错误。装载成功的样本会顺便把 expected 装进 PluginLoaderContract
 *     的数据类走一遍，证明 expected 与接口草案对得上。
 *
 * 每个样本的 expected.json 必须带 `pendingFix`（没有加载器的现状）和带行号的 `basis`（或「原文未说明」）。
 */
class PluginCompatTest {
    private val json = Json

    /** [设计] 探针的类名是加载器最可能的落点；实现者换了名字的话请更新这里与样本的 pendingFix 文案。 */
    @Test
    fun `no plugin loader exists yet`() {
        val landed = try {
            Class.forName("dev.min.agent.ext.plugins.PluginLoader")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertTrue(
            "插件加载器已经落地——把 plugin-compat 样本接上真加载器复核 expected、摘掉 pendingFix，" +
                "并按 PluginLoaderContract 的形状对齐接口",
            !landed,
        )
    }

    @Test
    fun `every plugin sample is self-consistent and marked pendingFix`() {
        val samples = sampleRoot().listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("plugin-compat 下没有样本目录", samples.isNotEmpty())
        val failures = mutableListOf<String>()
        for (sample in samples) {
            try {
                runSample(sample)
            } catch (e: SampleBug) {
                failures += "${sample.name}: ${e.message}"
            }
        }
        assertTrue(
            "${samples.size} 个样本，${failures.size} 个样本自身不合法（样本即规格，规格错了算测试失败）：\n" +
                failures.joinToString("\n\n"),
            failures.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- 一只样本的完整校验

    private fun runSample(dir: File) {
        val case = parseJson(File(dir, "case.json")).jsonObject
        val expected = parseJson(File(dir, "expected.json")).jsonObject
        val sample = dir.name

        val basis = expected.strOrNull("basis") ?: bug("缺 basis")
        if (!basis.contains("原文未说明") && !Regex("[:：]\\s*\\d+").containsMatchIn(basis)) {
            bug("basis 既没有行号也没有「原文未说明」：$basis")
        }
        val pending = expected.strOrNull("pendingFix")
        if (pending.isNullOrBlank()) bug("缺 pendingFix——Min 现在没有插件加载器，每个样本都必须带着这枚标记")

        val rootName = case.strOrNull("root") ?: bug("case.json 缺 root")
        val rootDir = File(dir, rootName)
        if (!rootDir.isDirectory) bug("插件根目录 $rootName 不存在")
        dir.listFiles()!!.filter { it.name !in setOf("case.json", "expected.json", rootName) }
            .forEach { bug("样本目录里混进了 ${it.name}（只允许 case.json、expected.json 和插件根 $rootName）") }
        val files = rootDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(rootDir).invariantSeparatorsPath to it }

        val manifest = files[".claude-plugin/plugin.json"]?.let { parseJson(it).jsonObject }
        val entry = case["entry"] as? JsonObject
        val violations = mutableListOf<Violation>()
        validate(sample, manifest, entry, case, files, rootName, violations)

        val invalid = case.arr("invalidPaths")?.map { it.jsonPrimitive.content } ?: emptyList()
        val pathKinds = setOf(Kind.PATH_PREFIX, Kind.PATH_ESCAPE, Kind.PATH_MISSING, Kind.AGENTS_NOT_MD, Kind.SKILLS_NOT_DIR)
        violations.filter { it.kind in pathKinds }.forEach {
            if (it.path !in invalid) bug("路径违规没在 case.json 的 invalidPaths 里声明：${it.path}（${it.detail}）")
        }
        invalid.forEach { p ->
            if (violations.none { it.path == p }) bug("invalidPaths 里的 \"$p\" 在清单里找不到对应的路径违规")
        }

        val loads = expected.boolOrNull("loads") ?: bug("expected 缺 loads")
        val fatal = violations.filter { it.kind.fatal }
        if (!loads) {
            if (fatal.isEmpty()) bug("expected 说装载失败，但清单与目录树里没有任何致命违规")
            val claimed = listOf(
                "name", "nameSource", "skills", "commands", "agents", "hooks", "mcpServers", "lspServers",
                "outputStyles", "themes", "monitors", "workflows", "bin", "settings", "userConfig", "channels",
            )
            claimed.forEach { if (expected.containsKey(it)) bug("装载失败的样本不该断言 $it") }
        } else {
            if (fatal.isNotEmpty()) bug("清单与目录树里有致命违规，expected 却说装载成功：\n" + fatal.joinToString("\n") { it.detail })
            val got = discover(rootName, files, manifest, entry, case)
            val diffs = mutableListOf<String>()
            compareLoaded(sample, got, expected, case, files, diffs)
            if (diffs.isNotEmpty()) bug(diffs.joinToString("\n"))
        }
        val effective = if (loads) violations
        else violations.filter { it.kind != Kind.CLAUDE_MD && it.kind != Kind.DEFAULT_IGNORED }
        checkFragments(sample, effective, expected)
    }

    // ---------------------------------------------------------------- 静态校验（validate）

    private fun validate(
        sample: String,
        manifest: JsonObject?,
        entry: JsonObject?,
        case: JsonObject,
        files: Map<String, File>,
        rootName: String,
        violations: MutableList<Violation>,
    ) {
        if (manifest != null) {
            val name = manifest.strOrNull("name")
            if (name == null) violations += Violation(Kind.NAME_REQUIRED, "清单缺 name（PLG:120 唯一必填键）")
            else if (!nameOk(name)) violations += Violation(Kind.NAME_CHARS, "name \"$name\" 不合 PLG:165 的字符规则")
            manifest.keys.filter { it == "themes" || it == "monitors" }
                .forEach { bug("顶层 $it 键是带 validate 警告的旧写法（PLG:157-158），样本集不覆盖，请用 experimental.*") }
        }
        val mcpKeys = effectiveMcpKeys(files, manifest, entry)
        collectPaths(manifest).forEach { (key, raw) -> checkPath(key, raw, case, files, rootName, violations) }
        if (manifest == null && entry != null) {
            // 条目即清单（PLG:674）：条目里的组件路径按同样规则校验
            collectPaths(entry).forEach { (key, raw) -> checkPath(key, raw, case, files, rootName, violations) }
        }
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
                bug("条目声明了 ${entry.keys} 里的非追加键：PLG:675 的追加清单只有 commands/agents/skills/outputStyles/themes，其余怎么处理原文未说明——样本别造这种形状")
            }
        }
    }

    private fun checkPath(
        key: String,
        raw: String,
        case: JsonObject,
        files: Map<String, File>,
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
        val exists = rel.isEmpty() || files.containsKey(rel) || files.keys.any { it.startsWith("$rel/") }
        if (!exists) {
            violations += Violation(Kind.PATH_MISSING, "$key 路径 \"$raw\" 不存在（PLG:404）", docFragment = "not found", path = raw)
            return
        }
        if (key == "agents" && !(files.containsKey(rel) && rel.endsWith(".md"))) {
            violations += Violation(Kind.AGENTS_NOT_MD, "agents 条目 \"$raw\" 必须是 .md 文件（PLG:222）", path = raw)
        }
        if (key == "skills" && !files.keys.any { it.startsWith("$rel/") }) {
            violations += Violation(Kind.SKILLS_NOT_DIR, "skills 条目 \"$raw\" 必须是目录（PLG:222）", path = raw)
        }
    }

    private fun checkWrapped(f: File, label: String, violations: MutableList<Violation>) {
        val o = parseJson(f).jsonObject
        if (o["hooks"] !is JsonObject) {
            violations += Violation(Kind.HOOKS_WRAPPER, "$label 缺顶层 \"hooks\" 包裹，装载失败（PLG:262）")
        }
    }

    private fun checkCommandsXor(value: JsonElement?, label: String, violations: MutableList<Violation>) {
        val o = value as? JsonObject ?: return
        o.forEach { (k, v) ->
            val item = v as? JsonObject ?: return@forEach
            if (item.containsKey("source") == item.containsKey("content")) {
                violations += Violation(Kind.COMMAND_XOR, "$label.$k 的 source/content 必须二选一（PLG:236）")
            }
        }
    }

    private fun checkUserConfig(value: JsonElement?, violations: MutableList<Violation>) {
        val o = value as? JsonObject ?: return
        o.forEach { (key, v) ->
            val item = v as? JsonObject ?: bug("userConfig.$key 不是对象")
            val unknown = item.keys - USER_CONFIG_FIELDS
            if (unknown.isNotEmpty()) violations += Violation(Kind.USERCONFIG_UNKNOWN, "userConfig.$key 有未知键 $unknown（PLG:97/:426）")
            if (!USER_CONFIG_KEY.matches(key)) violations += Violation(Kind.USERCONFIG_RULES, "userConfig 键 \"$key\" 不合 PLG:424 的规则")
            val type = item.strOrNull("type")
            if (type == null || type !in setOf("string", "number", "boolean", "directory", "file")) {
                violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 type 缺失或不在五值集里（PLG:430）")
            }
            listOf("title", "description").forEach {
                if (item.strOrNull(it) == null) violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 缺 $it（PLG:431-432）")
            }
            if (item.containsKey("options") && item["options"] !is JsonArray) {
                violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 options 应是数组（PLG:435）")
            }
            (item["options"] as? JsonArray)?.let { opts ->
                if (type != "string" || item.boolOrNull("multiple") == true || item.boolOrNull("sensitive") == true) {
                    violations += Violation(Kind.USERCONFIG_RULES, "userConfig.$key 的 options 只能用于非 multiple、非 sensitive 的 string（PLG:484）")
                }
                val labels = opts.map { e -> e.jsonPrimitive.content }
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

    private fun checkChannels(value: JsonElement?, mcpKeys: Set<String>, violations: MutableList<Violation>) {
        val arr = value as? JsonArray ?: return
        arr.forEach { e ->
            val o = e as? JsonObject ?: bug("channels 条目不是对象")
            val unknown = o.keys - setOf("server", "displayName", "userConfig")
            if (unknown.isNotEmpty()) violations += Violation(Kind.CHANNELS_UNKNOWN, "channels 条目有未知键 $unknown（PLG:97/:513-518）")
            val server = o.strOrNull("server")
            if (server == null) violations += Violation(Kind.CHANNELS_SERVER, "channels 条目缺 server（PLG:515）")
            else if (server !in mcpKeys) violations += Violation(Kind.CHANNELS_SERVER, "channels 的 server \"$server\" 不是本插件 mcpServers 的键（CMP:977）")
        }
    }

    // ---------------------------------------------------------------- 发现（discover）：把 expected 应许的东西按原文规则算出来

    private fun discover(
        rootName: String,
        files: Map<String, File>,
        manifest: JsonObject?,
        entry: JsonObject?,
        case: JsonObject,
    ): LoadedPlugin {
        val marketplaceName = case.strOrNull("marketplaceName")
        val install = case.strOrNull("install") ?: "pluginDir"
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
                nameSource = PluginNameSource.DIRECTORY_NAME
            }
        }
        val refName = if (install == "marketplace") entry?.strOrNull("name") ?: ns else ns
        val ref = PluginRef(refName, marketplaceName)
        val manifestObj = manifest?.let { m ->
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
        val enabled = case.obj("enabledPlugins")?.get(enabledId)?.jsonPrimitive?.booleanOrNull ?: defaultEnabled

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
            monitors = discoverMonitors(files, manifest),
            workflows = discoverWorkflows(files, manifest, entry, entryIsManifest),
            binOnPath = files.keys.filter { it.startsWith("bin/") && it.count { c -> c == '/' } == 1 }.map { it.substringAfterLast('/') }.sorted(),
            settings = effectiveSettings(files, manifest),
            userConfig = manifestObj?.userConfig ?: emptyList(),
            channels = manifestObj?.channels ?: emptyList(),
        )
    }

    private fun discoverSkills(
        rootName: String,
        ns: String,
        files: Map<String, File>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginSkill> {
        val skills = mutableListOf<PluginSkill>()
        val seen = mutableSetOf<String>()
        fun add(rel: String, baseName: String) {
            val fm = frontmatter(files.getValue(rel).readText())
            val name = "$ns:" + (fm["name"] ?: baseName)
            if (!seen.add(name)) bug("技能名重复：$name")
            skills += PluginSkill(name, rel)
        }
        fun addRootSkill() {
            val fm = frontmatter(files.getValue("SKILL.md").readText())
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
            pathsOf(mSkills).forEach { scanDir(it.removePrefix("./")) }
            if (!entryIsManifest) pathsOf(eSkills).forEach { scanDir(it.removePrefix("./")) }
        }
        return skills
    }

    private fun discoverCommands(
        ns: String,
        files: Map<String, File>,
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
                val fm = frontmatter(files.getValue(rel).readText())
                val segs = rel.removePrefix("$dirRel/").removeSuffix(".md").split('/')
                add(PluginCommand((listOf(ns) + segs).joinToString(":"), sourcePath = rel, description = fm["description"]))
            }
        }
        fun addFile(rel: String) {
            val fm = frontmatter(files.getValue(rel).readText())
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

    private fun discoverAgents(
        ns: String,
        files: Map<String, File>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginAgent> {
        val agents = mutableListOf<PluginAgent>()
        val seen = mutableSetOf<String>()
        fun addFile(rel: String, withSubfolders: Boolean) {
            val fm = frontmatter(files.getValue(rel).readText())
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

    private fun discoverHooks(
        files: Map<String, File>,
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
                    if (ho.strOrNull("type") != "command") bug("样本集只钉 command 型 hook，$from 出现 ${ho.strOrNull("type")}")
                    out += PluginHookHandler(o.strOrNull("matcher"), ho.strOrNull("command"), ho.arr("args")?.map { it.jsonPrimitive.content } ?: emptyList())
                }
            }
            return out
        }
        fun eventMap(o: JsonObject, from: String, allowOverlap: Boolean) {
            o.forEach { (event, v) ->
                if (!allowOverlap && hooks.containsKey(event)) bug("事件 $event 同时来自多个 hooks 来源：同事件合并顺序原文未说明，样本别这么写")
                hooks.getOrPut(event) { mutableListOf() } += flatten(v, "$from/$event")
            }
        }
        files["hooks/hooks.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            eventMap(o.obj("hooks") ?: bug("hooks/hooks.json 缺 \"hooks\" 包裹"), "hooks/hooks.json", allowOverlap = true)
        }
        fun addValue(v: JsonElement, from: String) {
            when (v) {
                is JsonPrimitive -> {
                    val rel = v.content.removePrefix("./")
                    val f = files[rel] ?: bug("hooks 文件不存在 $rel")
                    eventMap(parseJson(f).jsonObject.obj("hooks") ?: bug("$rel 缺 \"hooks\" 包裹"), rel, allowOverlap = false)
                }
                is JsonObject -> eventMap(v, from, allowOverlap = false)
                is JsonArray -> v.forEach { addValue(it, from) }
                else -> bug("hooks 的形状不认识")
            }
        }
        manifest?.get("hooks")?.let { addValue(it, "manifest") }
        if (entryIsManifest && entry?.containsKey("hooks") == true) {
            // 条目即清单：hooks 只吃内联事件表（PLG:674）；文件/数组形式在 validate 里已记 ENTRY_HOOKS_FILE
            (entry["hooks"] as? JsonObject)?.let { eventMap(it, "entry", allowOverlap = true) }
        } else if (entry?.containsKey("hooks") == true) {
            val eh = entry.obj("hooks") ?: bug("strict 追加的条目 hooks 必须是内联事件表（PLG:674）")
            eh.forEach { (event, v) -> hooks[event] = flatten(v, "entry/$event").toMutableList() } // PLG:675 事件级替换
        }
        return hooks.mapValues { it.value.toList() }
    }

    private fun discoverMcp(
        files: Map<String, File>,
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
                    if (raw.startsWith("https://")) return // bundle URL：内容不在树里，样本集不钉
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

    private fun discoverLsp(
        files: Map<String, File>,
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

    private fun discoverStyles(
        ns: String,
        files: Map<String, File>,
        manifest: JsonObject?,
        entry: JsonObject?,
        entryIsManifest: Boolean,
    ): List<PluginOutputStyle> {
        val styles = mutableListOf<PluginOutputStyle>()
        fun addFile(rel: String) {
            val fm = frontmatter(files.getValue(rel).readText())
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

    private fun discoverThemes(
        ns: String,
        files: Map<String, File>,
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

    private fun discoverMonitors(files: Map<String, File>, manifest: JsonObject?): List<PluginMonitor> {
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

    private fun discoverWorkflows(
        files: Map<String, File>,
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

    private fun effectiveSettings(files: Map<String, File>, manifest: JsonObject?): JsonObject? {
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

    // ---------------------------------------------------------------- 比对（compare）：expected ↔ 发现结果

    private fun compareLoaded(
        sample: String,
        got: LoadedPlugin,
        expected: JsonObject,
        case: JsonObject,
        files: Map<String, File>,
        diffs: MutableList<String>,
    ) {
        fun diff(what: String, want: Any?, actual: Any?) {
            diffs += "$sample $what：期望=$want 实际=$actual"
        }
        if (expected.containsKey("name") && expected.strOrNull("name") != got.namespace) diff("name", expected.strOrNull("name"), got.namespace)
        if (expected.containsKey("nameSource")) {
            val want = expected.strOrNull("nameSource")
            val actual = when (got.nameSource) {
                PluginNameSource.MANIFEST -> "manifest"
                PluginNameSource.MARKETPLACE_ENTRY -> "marketplace"
                PluginNameSource.DIRECTORY_NAME -> "directory"
            }
            if (want != actual) diff("nameSource", want, actual)
        }
        expected.strOrNull("displayName")?.let { if (it != got.displayName) diff("displayName", it, got.displayName) }
        expected.strOrNull("version")?.let { if (it != got.manifest?.version) diff("version", it, got.manifest?.version) }
        expected.boolOrNull("defaultEnabled")?.let { if (it != got.manifest?.defaultEnabled) diff("defaultEnabled", it, got.manifest?.defaultEnabled) }
        expected.strOrNull("authorName")?.let { if (it != got.manifest?.authorName) diff("authorName", it, got.manifest?.authorName) }
        expected.obj("metadata")?.let { if (it != got.manifest?.metadata) diff("metadata", it, got.manifest?.metadata) }
        (expected["dependencies"] as? JsonArray)?.let { wantList ->
            val gotList = got.manifest?.dependencies ?: emptyList()
            if (wantList.size != gotList.size) diff("dependencies 条数", wantList.size, gotList.size)
            wantList.forEachIndexed { i, d ->
                val item = d.jsonObject
                val g = gotList.getOrNull(i) ?: return@forEachIndexed
                item.strOrNull("name")?.let { if (it != g.name) diff("dependencies[$i].name", it, g.name) }
                item.strOrNull("marketplace")?.let { if (it != g.marketplace) diff("dependencies[$i].marketplace", it, g.marketplace) }
                item.strOrNull("version")?.let { if (it != g.version) diff("dependencies[$i].version", it, g.version) }
            }
        }
        expected.boolOrNull("enabled")?.let { if (it != got.enabled) diff("enabled", it, got.enabled) }
        expected.strOrNull("enabledId")?.let { if (it != got.ref.id) diff("enabledId", it, got.ref.id) }
        (expected["strippedUnknownTopLevel"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let {
            val actual = got.manifest?.strippedUnknownTopLevel ?: emptyList()
            if (it != actual) diff("strippedUnknownTopLevel", it, actual)
        }

        matchByName(sample, "skills", expected, "name", got.skills.map { it.name }, diffs)
        (expected["skills"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.skills.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("path")?.let { if (it != g.skillMd) diff("skills ${g.name} path", it, g.skillMd) }
            checkResolvedBody(sample, item, g, case, files, got, diffs)
        }
        matchByName(sample, "commands", expected, "name", got.commands.map { it.name }, diffs)
        (expected["commands"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.commands.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("source")?.let { if (it != g.sourcePath) diff("commands ${g.name} source", it, g.sourcePath) }
            item.strOrNull("content")?.let { if (it != g.content) diff("commands ${g.name} content", it, g.content) }
            item.strOrNull("description")?.let { if (it != g.description) diff("commands ${g.name} description", it, g.description) }
            item.strOrNull("argumentHint")?.let { if (it != g.argumentHint) diff("commands ${g.name} argumentHint", it, g.argumentHint) }
            item.strOrNull("model")?.let { if (it != g.model) diff("commands ${g.name} model", it, g.model) }
            (item["allowedTools"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                if (it != g.allowedTools) diff("commands ${g.name} allowedTools", it, g.allowedTools)
            }
        }
        matchByName(sample, "agents", expected, "name", got.agents.map { it.name }, diffs)
        (expected["agents"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.agents.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("file")?.let { if (it != g.filePath) diff("agents ${g.name} file", it, g.filePath) }
            if (item.containsKey("model")) {
                val want = primOrNull(item["model"])
                if (want != g.model) diff("agents ${g.name} model", want, g.model)
            }
        }
        (expected["hooks"] as? JsonObject)?.let { wantHooks ->
            if (wantHooks.keys.toSet() != got.hooks.keys.toSet()) diff("hooks 事件集", wantHooks.keys, got.hooks.keys)
            val exports = hookExports(got.userConfig.map { it.key })
            wantHooks.forEach { (event, list) ->
                val wantList = (list as? JsonArray)?.map { it.jsonObject } ?: return@forEach
                val gotList = got.hooks[event] ?: return@forEach
                if (wantList.size != gotList.size) diff("hooks $event 条数", wantList.size, gotList.size)
                wantList.forEachIndexed { i, item ->
                    val g = gotList.getOrNull(i) ?: return@forEachIndexed
                    if (item.containsKey("matcher")) {
                        val want = primOrNull(item["matcher"])
                        if (want != g.matcher) diff("hooks $event[$i] matcher", want, g.matcher)
                    }
                    item.strOrNull("command")?.let { if (it != g.command) diff("hooks $event[$i] command", it, g.command) }
                    (item["args"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                        if (it != g.args) diff("hooks $event[$i] args", it, g.args)
                    }
                    item.strOrNull("resolvedCommand")?.let {
                        val actual = substitutePaths(g.command ?: "", case, got.namespace)
                        if (it != actual) diff("hooks $event[$i] resolvedCommand", it, actual)
                    }
                    (item["resolvedArgs"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let { want ->
                        val actual = g.args.map { substituteUserConfig(substitutePaths(it, case, got.namespace), case, got, allowSensitive = true) }
                        if (want != actual) diff("hooks $event[$i] resolvedArgs", want, actual)
                    }
                    (item["exportSet"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                        if (it != exports) diff("hooks $event[$i] exportSet", it, exports)
                    }
                }
            }
        }
        matchByKey(sample, "mcpServers", expected, "key", got.mcpServers.map { it.key }, diffs)
        (expected["mcpServers"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.mcpServers.firstOrNull { it.key == item.strOrNull("key") } ?: return@forEach
            item.strOrNull("serverName")?.let { if (it != g.serverName) diff("mcp ${g.key} serverName", it, g.serverName) }
            item.strOrNull("command")?.let { if (it != g.config.strOrNull("command")) diff("mcp ${g.key} command", it, g.config.strOrNull("command")) }
            (item["args"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                val actual = g.config.arr("args")?.map { a -> a.jsonPrimitive.content } ?: emptyList()
                if (it != actual) diff("mcp ${g.key} args", it, actual)
            }
            item.obj("env")?.let {
                val actual = g.config.obj("env") ?: JsonObject(emptyMap())
                if (it != actual) diff("mcp ${g.key} env", it, actual)
            }
            item.obj("toolName")?.let { want ->
                val tool = want.strOrNull("tool") ?: bug("toolName 缺 tool")
                val actual = "mcp__plugin_${got.namespace}_${g.key}__$tool" // CMP:825
                if (want.strOrNull("name") != actual) diff("mcp ${g.key} toolName", want.strOrNull("name"), actual)
            }
            (item["resolvedArgs"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let { want ->
                val actual = (g.config.arr("args")?.map { a -> a.jsonPrimitive.content } ?: emptyList())
                    .map { substituteUserConfig(substitutePaths(it, case, got.namespace), case, got, allowSensitive = true) }
                if (want != actual) diff("mcp ${g.key} resolvedArgs", want, actual)
            }
            item.obj("resolvedEnv")?.let { want ->
                val src = g.config.obj("env") ?: JsonObject(emptyMap())
                val actual = JsonObject(src.mapValues { (_, v) ->
                    JsonPrimitive(substituteUserConfig(substitutePaths(v.jsonPrimitive.content, case, got.namespace), case, got, allowSensitive = true))
                })
                if (want != actual) diff("mcp ${g.key} resolvedEnv", want, actual)
            }
            (item["exportSet"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                val exports = listOf("CLAUDE_PLUGIN_ROOT", "CLAUDE_PLUGIN_DATA") // PLG:571
                if (it != exports) diff("mcp ${g.key} exportSet", it, exports)
            }
        }
        matchByKey(sample, "lspServers", expected, "key", got.lspServers.map { it.key }, diffs)
        (expected["lspServers"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.lspServers.firstOrNull { it.key == item.strOrNull("key") } ?: return@forEach
            item.strOrNull("command")?.let { if (it != g.config.strOrNull("command")) diff("lsp ${g.key} command", it, g.config.strOrNull("command")) }
            (item["args"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                val actual = g.config.arr("args")?.map { a -> a.jsonPrimitive.content } ?: emptyList()
                if (it != actual) diff("lsp ${g.key} args", it, actual)
            }
            item.obj("extensionToLanguage")?.let {
                val actual = g.config.obj("extensionToLanguage") ?: JsonObject(emptyMap())
                if (it != actual) diff("lsp ${g.key} extensionToLanguage", it, actual)
            }
            if (item.containsKey("workspaceFolder")) {
                val want = primOrNull(item["workspaceFolder"])
                val actual = g.config.strOrNull("workspaceFolder")
                if (want != actual) diff("lsp ${g.key} workspaceFolder", want, actual)
            }
            item.strOrNull("resolvedWorkspaceFolder")?.let {
                val actual = g.config.strOrNull("workspaceFolder")?.let { w -> substitutePaths(w, case, got.namespace) }
                if (it != actual) diff("lsp ${g.key} resolvedWorkspaceFolder", it, actual)
            }
            (item["exportSet"] as? JsonArray)?.map { a -> a.jsonPrimitive.content }?.let {
                val exports = listOf("CLAUDE_PLUGIN_ROOT", "CLAUDE_PLUGIN_DATA", "CLAUDE_PROJECT_DIR") // PLG:573
                if (it != exports) diff("lsp ${g.key} exportSet", it, exports)
            }
        }
        matchByName(sample, "outputStyles", expected, "name", got.outputStyles.map { it.name }, diffs)
        (expected["outputStyles"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.outputStyles.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("file")?.let { if (it != g.filePath) diff("outputStyles ${g.name} file", it, g.filePath) }
        }
        matchByName(sample, "themes", expected, "name", got.themes.map { it.name }, diffs)
        (expected["themes"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.themes.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("file")?.let { if (it != g.filePath) diff("themes ${g.name} file", it, g.filePath) }
        }
        matchByName(sample, "monitors", expected, "name", got.monitors.map { it.name }, diffs)
        (expected["monitors"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.monitors.firstOrNull { it.name == item.strOrNull("name") } ?: return@forEach
            item.strOrNull("command")?.let { if (it != g.command) diff("monitors ${g.name} command", it, g.command) }
            item.strOrNull("description")?.let { if (it != g.description) diff("monitors ${g.name} description", it, g.description) }
            if (item.containsKey("when")) {
                val want = primOrNull(item["when"])
                if (want != g.whenTrigger) diff("monitors ${g.name} when", want, g.whenTrigger)
            }
            item.boolOrNull("starts")?.let {
                // PLG:388/:499 命令里引用 ${user_config.*} 的监视器报错不启动；CMP:1000 也拿不到 OPTION 环境变量
                val actual = !g.command.contains("\${user_config.")
                if (it != actual) diff("monitors ${g.name} starts", it, actual)
            }
        }
        (expected["workflows"] as? JsonArray)?.map { it.jsonObject.strOrNull("file") }?.let { want ->
            val actual = got.workflows.map { it.filePath }
            if (want != actual) diff("workflows", want, actual)
        }
        (expected["bin"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let {
            if (it != got.binOnPath) diff("bin", it, got.binOnPath)
        }
        expected.obj("settings")?.forEach { (k, v) ->
            val actual = got.settings?.get(k)
            if (v != actual) diff("settings.$k", v, actual)
        }
        matchByName(sample, "userConfig", expected, "key", got.userConfig.map { it.key }, diffs)
        (expected["userConfig"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.userConfig.firstOrNull { it.key == item.strOrNull("key") } ?: return@forEach
            item.strOrNull("type")?.let { if (it != g.type) diff("userConfig ${g.key} type", it, g.type) }
            item.strOrNull("title")?.let { if (it != g.title) diff("userConfig ${g.key} title", it, g.title) }
            item.strOrNull("description")?.let { if (it != g.description) diff("userConfig ${g.key} description", it, g.description) }
            item.boolOrNull("required")?.let { if (it != g.required) diff("userConfig ${g.key} required", it, g.required) }
            item.boolOrNull("multiple")?.let { if (it != g.multiple) diff("userConfig ${g.key} multiple", it, g.multiple) }
            item.boolOrNull("sensitive")?.let { if (it != g.sensitive) diff("userConfig ${g.key} sensitive", it, g.sensitive) }
            if (item.containsKey("default")) {
                val want = primOrNull(item["default"]) ?: (item["default"] as? JsonArray)?.map { x -> x.jsonPrimitive.content }
                val actual = when (val d = g.default) {
                    null -> null
                    is JsonArray -> d.map { x -> x.jsonPrimitive.content }
                    else -> d.jsonPrimitive.content
                }
                if (want != actual) diff("userConfig ${g.key} default", want, actual)
            }
            (item["options"] as? JsonArray)?.map { x -> x.jsonPrimitive.content }?.let {
                if (it != g.options) diff("userConfig ${g.key} options", it, g.options)
            }
            item.strOrNull("stored")?.let {
                // PLG:437/:488 敏感值进安全存储，其余进用户 settings 的 pluginConfigs
                val actual = if (g.sensitive) "secureStorage" else "pluginConfigs"
                if (it != actual) diff("userConfig ${g.key} stored", it, actual)
            }
        }
        matchByName(sample, "channels", expected, "server", got.channels.map { it.server }, diffs)
        (expected["channels"] as? JsonArray)?.forEach { e ->
            val item = e.jsonObject
            val g = got.channels.firstOrNull { it.server == item.strOrNull("server") } ?: return@forEach
            if (item.containsKey("displayName")) {
                val want = primOrNull(item["displayName"])
                if (want != g.displayName) diff("channels ${g.server} displayName", want, g.displayName)
            }
        }
    }

    /** skill 正文的变量替换：${CLAUDE_PLUGIN_ROOT} 等就地替换（PLG:574），${user_config.*} 非敏感换成保存值、
     *  敏感的变成未知占位符（PLG:494 只说「placeholder」，文本未说明 → 只断言原值不出现）。 */
    private fun checkResolvedBody(
        sample: String,
        item: JsonObject,
        skill: PluginSkill,
        case: JsonObject,
        files: Map<String, File>,
        got: LoadedPlugin,
        diffs: MutableList<String>,
    ) {
        val raw = markdownBody(files.getValue(skill.skillMd).readText())
        var body = substitutePaths(raw, case, got.namespace)
        body = substituteUserConfig(body, case, got, allowSensitive = false)
        (item["resolvedBodyContains"] as? JsonArray)?.map { e -> e.jsonPrimitive.content }?.forEach { frag ->
            if (frag !in body) diffs += "$sample skill ${skill.name} 正文应含「$frag」，实际=$body"
        }
        (item["resolvedBodyExcludes"] as? JsonArray)?.map { e -> e.jsonPrimitive.content }?.forEach { frag ->
            if (frag in body) diffs += "$sample skill ${skill.name} 正文不应出现敏感原值「$frag」"
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun matchByName(sample: String, kind: String, expected: JsonObject, key: String, actualNames: List<String>, diffs: MutableList<String>) {
        val want = (expected[kind] as? JsonArray)?.mapNotNull { it.jsonObject.strOrNull(key) } ?: return
        val missing = want.filter { it !in actualNames }
        val extra = actualNames.filter { it !in want }
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            diffs += "$sample $kind 名字集合不一致：缺=$missing 多=$extra（实际=$actualNames）"
        }
    }

    private fun matchByKey(sample: String, kind: String, expected: JsonObject, key: String, actualKeys: List<String>, diffs: MutableList<String>) =
        matchByName(sample, kind, expected, key, actualKeys, diffs)

    private fun checkFragments(sample: String, violations: List<Violation>, expected: JsonObject) {
        val errors = expected.arr("errors")?.map { it.jsonPrimitive.content } ?: emptyList()
        val warnings = expected.arr("warnings")?.map { it.jsonPrimitive.content } ?: emptyList()
        violations.forEach { v ->
            val frag = v.docFragment ?: return@forEach
            val isWarning = v.kind == Kind.CLAUDE_MD || v.kind == Kind.DEFAULT_IGNORED
            val sink = if (isWarning) warnings else errors
            if (sink.none { frag in it }) {
                bug("违规「${v.detail}」的原文引文「$frag」没写进 expected.${if (isWarning) "warnings" else "errors"}")
            }
        }
        errors.forEach { frag ->
            val hit = violations.any { v -> v.kind != Kind.CLAUDE_MD && v.kind != Kind.DEFAULT_IGNORED && v.docFragment != null && frag.contains(v.docFragment!!) }
            if (!hit) bug("expected.errors 的「$frag」找不到对应的原文引文违规")
        }
        warnings.forEach { frag ->
            val hit = violations.any { v -> v.docFragment != null && frag.contains(v.docFragment!!) }
            if (!hit) bug("expected.warnings 的「$frag」找不到对应的原文引文违规")
        }
    }

    private fun substitutePaths(raw: String, case: JsonObject, ns: String): String {
        var s = raw
        if ("\${CLAUDE_PLUGIN_ROOT}" in s) {
            s = s.replace("\${CLAUDE_PLUGIN_ROOT}", case.strOrNull("installRoot") ?: bug("样本用了 CLAUDE_PLUGIN_ROOT 但 case.json 没给 installRoot"))
        }
        if ("\${CLAUDE_PLUGIN_DATA}" in s) {
            val data = case.strOrNull("dataRoot") ?: "~/.claude/plugins/data/" + dataId(case, ns)
            s = s.replace("\${CLAUDE_PLUGIN_DATA}", data)
        }
        if ("\${CLAUDE_PROJECT_DIR}" in s) {
            s = s.replace("\${CLAUDE_PROJECT_DIR}", case.strOrNull("projectDir") ?: bug("样本用了 CLAUDE_PROJECT_DIR 但 case.json 没给 projectDir"))
        }
        return s
    }

    /** PLG:556/:1064：<id> 是插件标识里字母数字_－之外的每个字符换成 -。 */
    private fun dataId(case: JsonObject, ns: String): String {
        val mp = case.strOrNull("marketplaceName")
        val id = if (mp != null) "$ns@$mp" else ns
        return String(id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.toCharArray())
    }

    private fun substituteUserConfig(raw: String, case: JsonObject, got: LoadedPlugin, allowSensitive: Boolean): String {
        if (!raw.contains("\${user_config.")) return raw
        val enabledId = got.ref.id
        val options = case.obj("pluginConfigs")?.obj(enabledId)?.obj("options")
        val sensitive = got.userConfig.filter { it.sensitive }.map { it.key }.toSet()
        var s = raw
        Regex("\\$\\{user_config\\.([A-Za-z_][A-Za-z0-9_]*)\\}").findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            val value = if (key in sensitive && !allowSensitive) "\u0000" else options?.get(key)?.jsonPrimitive?.contentOrNull
                ?: bug("user_config.$key 没有保存值（case.json 的 pluginConfigs）")
            s = s.replace(m.value, value)
        }
        return s
    }

    private fun hookExports(userConfigKeys: List<String>): List<String> =
        listOf("CLAUDE_PLUGIN_ROOT", "CLAUDE_PLUGIN_DATA", "CLAUDE_PROJECT_DIR") +
            userConfigKeys.map { "CLAUDE_PLUGIN_OPTION_${it.uppercase()}" } // PLG:495 键大写

    private fun frontmatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val meta = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.trim() == "---") break
            val m = Regex("^([A-Za-z][A-Za-z0-9-]*):\\s*(.*)$").find(line) ?: continue
            meta[m.groupValues[1]] = m.groupValues[2].trim()
        }
        return meta
    }

    private fun markdownBody(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) return lines.drop(end + 2).joinToString("\n")
        }
        return text
    }

    private fun pathSegments(rootName: String, raw: String): List<String> {
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

    private fun collectPaths(m: JsonObject?): List<Pair<String, String>> {
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
        add("agents", m["agents"])
        add("skills", m["skills"])
        add("hooks", m["hooks"])
        add("mcpServers", m["mcpServers"])
        add("lspServers", m["lspServers"])
        add("outputStyles", m["outputStyles"])
        add("workflows", m["workflows"])
        m.obj("experimental")?.let {
            add("experimental.themes", it["themes"])
            add("experimental.monitors", it["monitors"])
        }
        return out
    }

    private fun hookFileSources(m: JsonObject?): List<String> {
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

    private fun mcpStringSources(m: JsonObject?): List<String> {
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

    private fun lspConfigSources(files: Map<String, File>, m: JsonObject?): List<Pair<String, JsonObject>> {
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

    private fun monitorArrays(files: Map<String, File>, m: JsonObject?): List<JsonArray> {
        val out = mutableListOf<JsonArray>()
        files["monitors/monitors.json"]?.let { out += parseJson(it).jsonArray }
        val mM = m?.obj("experimental")?.get("monitors")
        when {
            mM is JsonPrimitive -> out += parseJson(files.getValue(mM.content.removePrefix("./"))).jsonArray
            mM is JsonArray -> out += mM
        }
        return out
    }

    private fun effectiveMcpKeys(files: Map<String, File>, manifest: JsonObject?, entry: JsonObject?): Set<String> {
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

    private fun parseUserConfig(o: JsonObject?): List<PluginUserConfigOption> {
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

    private fun parseChannels(arr: JsonArray?): List<PluginChannel> {
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

    private fun lspProblem(v: JsonElement): String? {
        if (v !is JsonObject) return "不是对象"
        val unknown = v.keys - LSP_FIELDS
        if (unknown.isNotEmpty()) return "未知键 $unknown（PLG:324）"
        if (v.strOrNull("command") == null) return "缺 command（PLG:328）"
        val e2l = v.obj("extensionToLanguage") ?: return "缺 extensionToLanguage（PLG:329）"
        if (e2l.isEmpty() || e2l.keys.any { !it.startsWith(".") }) return "extensionToLanguage 必须非空且键以 . 开头（PLG:329）"
        return null
    }

    private fun nameOk(name: String): Boolean =
        name.isNotEmpty() && !Regex("[\\s@:/\\\\]").containsMatchIn(name) &&
            name.none {
                val c = it.code
                c < 0x20 || c == 0x7f || c in 0x200b..0x200f || c in 0x202a..0x202e || c in 0x2066..0x2069
            }

    private fun parseJson(f: File): JsonElement =
        try {
            json.parseToJsonElement(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            bug("${f.invariantSeparatorsPath} 不是合法 JSON：${e.message}")
        }

    private fun bug(message: String): Nothing = throw SampleBug(message)

    private fun sampleRoot(): File {
        val url = javaClass.classLoader.getResource("plugin-compat") ?: error("plugin-compat 不在测试 classpath 上")
        return File(url.toURI())
    }

    private class SampleBug(message: String) : RuntimeException(message)

    /** 一处与原文不符的静态违规。fatal=插件装载失败；非 fatal 的三类照常装载（PLG:96/:674、CMP:869）。 */
    private class Violation(val kind: Kind, val detail: String, val docFragment: String? = null, val path: String? = null)

    private enum class Kind {
        NAME_REQUIRED, NAME_CHARS, PATH_PREFIX, PATH_ESCAPE, PATH_MISSING, AGENTS_NOT_MD, SKILLS_NOT_DIR,
        HOOKS_WRAPPER, MCP_BUNDLE_EXT, COMMAND_XOR, USERCONFIG_UNKNOWN, USERCONFIG_RULES,
        CHANNELS_UNKNOWN, CHANNELS_SERVER, LSP_CONFIG_RULES, LSP_FILE_INVALID, MONITOR_RULES,
        ENTRY_HOOKS_FILE, CLAUDE_MD, STRICT_CONFLICT, DEFAULT_IGNORED;

        val fatal: Boolean get() = this !in setOf(ENTRY_HOOKS_FILE, LSP_FILE_INVALID, CLAUDE_MD, DEFAULT_IGNORED)
    }

    private companion object {
        /** PLG:124-159 的顶层键全集（顶层 themes/monitors 是带警告的旧写法，样本集不收）。 */
        val KNOWN_TOP_LEVEL = setOf(
            "\$schema", "name", "displayName", "version", "description", "author", "homepage", "repository",
            "license", "keywords", "metadata", "icon", "documentationUrl", "supportUrl", "privacyPolicyUrl",
            "termsOfServiceUrl", "defaultEnabled", "dependencies", "settings", "userConfig", "types", "channels",
            "skills", "commands", "agents", "hooks", "mcpServers", "lspServers", "outputStyles", "workflows",
            "experimental",
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

/** JsonObject/JsonArray 上的取值小工具（样本 JSON 都是本生成器写的，类型不对直接当样本错误抛）。 */
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

private fun primOrNull(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
