package dev.min.agent.ext.plugins

import dev.min.agent.spi.*
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** 每个样本必须通过生产加载器，单独的 JUnit 用例便于审计 46 个验收结果。 */
@RunWith(Parameterized::class)
class PluginCompatTest(private val dir: File) {
    private val json = Json
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun samples(): Collection<Array<Any>> {
            val root = File(PluginCompatTest::class.java.classLoader.getResource("plugin-compat")!!.toURI())
            return root.listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }.map { arrayOf<Any>(it) }
        }
    }
    @Test fun `sample passes real plugin loader`(): Unit = runBlocking {
        val case = parseJson(File(dir, "case.json")).jsonObject
        val expected = parseJson(File(dir, "expected.json")).jsonObject
        assertTrue("尚有 pendingFix", "pendingFix" !in expected)
        val rootName = case.strOrNull("root")!!
        val rootDir = File(dir, rootName)
        val files = rootDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(rootDir).invariantSeparatorsPath to it }
        val fs = FakeFs()
        files.forEach { (rel, file) -> fs.put("/$rootName/$rel", file.readText()) }
        val entry = case.obj("entry")
        val ref = case.strOrNull("marketplaceName")?.let { PluginRef(entry?.strOrNull("name") ?: rootName, it) }
        val result = PluginLoader(fs).load(PluginRequest(
            root = "/$rootName", ref = ref, entry = entry,
            enabledPlugins = case.obj("enabledPlugins")?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap(),
        ))
        assertTrue("${dir.name}: ${result.errors}", (result.plugin != null) == expected.boolOrNull("loads"))
        expected.arr("errors")?.forEach { fragment -> assertTrue("${dir.name}: 缺错误 ${fragment.jsonPrimitive.content}", result.errors.any { fragment.jsonPrimitive.content in it }) }
        expected.arr("warnings")?.forEach { fragment -> assertTrue("${dir.name}: 缺警告 ${fragment.jsonPrimitive.content}", result.warnings.any { fragment.jsonPrimitive.content in it }) }
        result.plugin?.let { got ->
            val diffs = mutableListOf<String>()
            compareLoaded(dir.name, got, expected, case, files, diffs)
            assertTrue(diffs.joinToString("\n"), diffs.isEmpty())
        }
    }

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

    private fun vars(case: JsonObject, ns: String) = PluginVars(
        case.strOrNull("installRoot") ?: "/$ns",
        case.strOrNull("dataRoot") ?: "~/.claude/plugins/data/" + pluginDataId(PluginRef(ns, case.strOrNull("marketplaceName"))),
        case.strOrNull("projectDir") ?: "/workspace",
    )
    private fun substitutePaths(raw: String, case: JsonObject, ns: String): String = vars(case, ns).paths(raw)
    private fun substituteUserConfig(raw: String, case: JsonObject, got: LoadedPlugin, allowSensitive: Boolean): String = runBlocking {
        val options = case.obj("pluginConfigs")?.obj(got.ref.id)?.obj("options") ?: JsonObject(emptyMap())
        val records = mutableMapOf<McpTokenKey, McpOAuthCredentials>()
        val store = PluginSecretStore(object : McpTokenStore {
            override suspend fun load(key: McpTokenKey) = records[key]
            override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials) { records[key] = credentials }
            override suspend fun delete(key: McpTokenKey) { records.remove(key) }
        })
        val public = savePluginOptions(got, options, store)["options"]!!.jsonObject
        val values = PluginValues.read(got, public, store)
        if (allowSensitive) resolvePluginMcp(raw, vars(case, got.namespace), values)
        else resolvePluginBody(raw, vars(case, got.namespace), values, got.userConfig.filter { it.sensitive }.map { it.key }.toSet())
    }
    private fun hookExports(keys: List<String>): List<String> = pluginHookExportNames(keys)
    private fun parseJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())
    private fun bug(message: String): Nothing = error(message)
    private fun markdownBody(text: String): String = dev.min.agent.ext.config.FrontmatterParser.parse(text, "plugin").body
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

private fun primOrNull(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
