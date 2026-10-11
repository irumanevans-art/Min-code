package dev.min.agent.cli

import dev.min.agent.ext.config.FrontmatterParser
import dev.min.agent.ext.plugins.LoadedPlugin
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest

internal class EvalMocks(private val suite: EvalSuite, private val case: EvalCase,
    private val agentAnswer: suspend (String) -> String, private val onAbort: () -> Unit) {
    val calls = mutableListOf<JsonObject>()
    val recordings = linkedMapOf<String, JsonObject>()
    var aborted: JsonObject? = null; private set
    private var agentCalls = 0
    private data class Mock(val path: Path, val fields: JsonObject, val body: String)
    private fun read(path: Path): Mock {
        checkedMockPath(path)
        require(Files.size(path) <= 1024 * 1024) { "mock 文件过大" }
        val parsed = FrontmatterParser.parse(Files.readString(path), path.toString())
        require(parsed.notes.isEmpty()) { parsed.notes.joinToString() }
        val fields = JsonObject(parsed.fields.mapValues { it.value.toJson() })
        require(fields.keys.all { it in setOf("type", "expect", "error", "abort_when", "tools") }) { "未知 mock 字段" }
        require(fields.string("type", "fixed") in setOf("fixed", "agent")) { "未知 mock type" }
        require(fields.string("type", "fixed") == "agent" || "abort_when" !in fields) { "abort_when 只用于 agent mock" }
        (fields["expect"] as? JsonObject)?.values?.forEach { value ->
            if (value is JsonPrimitive && value.isString && value.content.startsWith('/')) expectRegex(value.content)
        }
        return Mock(path, fields, parsed.body)
    }
    private fun expectRegex(raw: String): JsEvalRegex {
        val end = raw.lastIndexOf('/')
        require(end > 0 && raw.substring(end+1).all { it in "is" } && raw.substring(end+1).toSet().size == raw.length-end-1) { "无效 mock expect regex" }
        val pattern = raw.substring(1, end)
        require(pattern.length <= 256) { "mock expect regex 过长" }
        var escaped = false; var inClass = false
        pattern.forEachIndexed { index, char ->
            if (escaped) {
                require(char !in "123456789kpPbBAZz") { "mock expect regex 不支持引用、属性或边界转义" }; escaped = false
            } else when(char) {
                '\\' -> escaped = true
                '[' -> inClass = true
                ']' -> inClass = false
                '(', ')', '|' -> require(inClass) { "mock expect regex 不支持分组、选择或 lookaround" }
                '^' -> require(inClass || index == 0) { "mock expect regex 的 ^ 只能在开始" }
                '$' -> require(inClass || index == pattern.lastIndex) { "mock expect regex 的 $ 只能在结束" }
            }
        }
        require(!Regex("[+*?}][+*?{]").containsMatchIn(pattern)) { "mock expect regex 不接受嵌套或重复量词" }
        return JsEvalRegex(pattern, raw.substring(end+1))
    }
    private fun lookup(input: JsonObject, path: String): JsonElement? {
        var result: JsonElement = input
        for (key in path.split('.')) result = (result as? JsonObject)?.get(key) ?: return null
        return result
    }
    private fun text(value: JsonElement?) = (value as? JsonPrimitive)?.content ?: value?.toString().orEmpty()
    private fun expectation(input: JsonObject, fields: JsonObject): String? {
        val expect = fields["expect"] as? JsonObject ?: return null
        for ((key, wanted) in expect) {
            val value = lookup(input, key)
            val ok = when(wanted) {
                is JsonArray -> value in wanted
                is JsonPrimitive -> when(wanted.content) {
                    "string" -> value is JsonPrimitive && value.isString
                    "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                    "array" -> value is JsonArray
                    "object" -> value is JsonObject
                    else -> if (wanted.isString && wanted.content.startsWith('/')) value is JsonPrimitive && value.isString && value.content.length <= 4096 && expectRegex(wanted.content).containsMatchIn(value.content) else value == wanted
                }
                else -> value == wanted
            }
            if (!ok) return "expect failed: $key"
        }
        return null
    }
    private fun checkedMockPath(path: Path): Path {
        val owner = if (path.normalize().startsWith(case.dir.resolve("mocks"))) case.dir else suite.directory
        val base = owner.resolve("mocks").toRealPath()
        require(base.startsWith(owner.toRealPath())) { "mock 根目录链接越界" }
        val real = path.toRealPath()
        require(real.startsWith(base) && Files.isRegularFile(real) && Files.size(real) <= 1024 * 1024) { "mock 文件链接越界或过大" }
        return real
    }
    private fun expand(mock: Mock, input: JsonObject): String {
        // 一次扫描作者模板，输入或 fixture 的文本不能变成第二轮文件读取指令。
        val directive = Regex("\\{\\{input\\.([A-Za-z0-9_.-]+)}}|\\{\\{file:((?:[^{}]|\\{input\\.[A-Za-z0-9_.-]+})+)}}")
        return directive.replace(mock.body) { match ->
            if (match.groupValues[1].isNotEmpty()) text(lookup(input, match.groupValues[1])) else {
                val raw = Regex("\\{input\\.([A-Za-z0-9_.-]+)}").replace(match.groupValues[2]) { text(lookup(input, it.groupValues[1])) }
                require(!Path.of(raw).isAbsolute && raw.replace('\\', '/').split('/').none { it == ".." }) { "mock fixture 必须是目录内的相对路径" }
                val source = checkedMockPath(mock.path.parent.resolve(raw).normalize())
                require(source.startsWith(mock.path.parent.toRealPath())) { "mock fixture 越界" }
                Files.readString(source)
            }
        }
    }
    fun tools(plugins: List<LoadedPlugin>): List<Tool<JsonObject>> = plugins.flatMap { plugin -> plugin.mcpServers.flatMap { server ->
        val files = linkedMapOf<String, Path>()
        for (base in listOf(suite.directory.resolve("mocks"), case.dir.resolve("mocks"))) {
            val folder = base.resolve(server.key)
            if (Files.isDirectory(folder)) {
                require(base.toRealPath().startsWith(base.parent.toRealPath()) && folder.toRealPath().startsWith(base.toRealPath())) { "mock server 目录链接越界" }
                Files.list(folder).use { entries -> entries.sorted().filter { Files.isRegularFile(it) }.forEach { files[it.fileName.toString()] = it } }
            }
        }
        val fallback = files["_server.md"]?.let(::read)
        fallback?.let { require(it.fields.string("type") == "agent") { "_server.md 必须为 agent" }; require(it.fields.strings("tools").isNotEmpty()); require("expect" !in it.fields || it.fields.strings("tools").size == 1) { "多工具 _server 不能有 expect" } }
        val descriptions = files["_tools.json"]?.let { path ->
            checkedMockPath(path)
            val value = AgentJson.parseToJsonElement(Files.readString(path))
            (if (value is JsonArray) value else value.jsonObject["tools"]!!.jsonArray).associate { it.jsonObject.string("name") to it.jsonObject }
        }.orEmpty()
        val mocks = files.filterKeys { it.endsWith(".md") && !it.startsWith('_') }.mapKeys { it.key.removeSuffix(".md") }.mapValues { read(it.value) }.toMutableMap()
        fallback?.fields?.strings("tools")?.forEach { mocks.putIfAbsent(it, fallback) }
        mocks.map { (tool, mock) ->
            require(tool.matches(Regex("[A-Za-z0-9_.-]+"))) { "mock tool 名字无效" }
            object : Tool<JsonObject> {
                override val name = "mcp__plugin_${plugin.ref.name}_${server.key}__$tool"
                override val inputSchema = descriptions[tool]?.get("inputSchema")?.jsonObject ?: buildJsonObject { put("type", "object"); put("additionalProperties", true) }
                override val traits = ToolTraits(readOnly = true, concurrencySafe = false)
                override fun description(profile: HarnessProfile) = descriptions[tool]?.string("description") ?: "Mock MCP tool $tool"
                override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
                override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "eval mock")
                override fun run(input: JsonObject, ctx: ToolContext) = flow {
                    var failure = expectation(input, mock.fields)
                    val isAgent = mock.fields.string("type") == "agent"
                    if (isAgent && ++agentCalls > 4 * case.turns) failure = "mock call budget exceeded"
                    var error = mock.fields["error"]?.jsonPrimitive?.booleanOrNull == true
                    var output = ""
                    if (failure == null) {
                        if (!isAgent) output = expand(mock, input) else {
                            val hash = MessageDigest.getInstance("SHA-256").digest((tool + "\n" + canonicalMockInput(input)).toByteArray()).joinToString("") { "%02x".format(it) }
                            val filename = "$tool-$hash.json"
                            val replay = listOf(case.dir.resolve("mocks/.replay/${server.key}/$filename"), suite.directory.resolve("mocks/.replay/${server.key}/$filename")).firstOrNull { Files.isRegularFile(it) }
                            if (replay != null) {
                                checkedMockPath(replay)
                                val saved = AgentJson.parseToJsonElement(Files.readString(replay)).jsonObject
                                require(saved["input"] == input) { "mock replay input 不一致" }; output = saved.string("output"); error = saved["error"]?.jsonPrimitive?.booleanOrNull == true
                            } else {
                                val answer = agentAnswer("Act as an MCP mock. Respond JSON {\"output\":string,\"error\":boolean,\"abort\":boolean,\"reason\":string}. Only abort under these conditions: ${mock.fields.string("abort_when", "never")}\nInstructions:\n${mock.body}\nTool: $tool\nInput: $input\nPrior calls: $calls")
                                val saved = AgentJson.parseToJsonElement(answer.trim()).jsonObject
                                output = saved.string("output"); error = saved["error"]?.jsonPrimitive?.booleanOrNull == true
                                if (saved["abort"]?.jsonPrimitive?.booleanOrNull == true && "abort_when" in mock.fields) failure = saved.string("reason", "agent mock aborted")
                                if (failure == null) recordings["${server.key}/$filename"] = buildJsonObject { put("input", input); put("output", output); put("error", error) }
                            }
                        }
                    }
                    if (failure != null) {
                        aborted = buildJsonObject { put("server", server.key); put("tool", tool); put("reason", failure) }
                        onAbort(); output = failure; error = true
                    }
                    calls += buildJsonObject { put("server", server.key); put("tool", tool); put("input", input); put("output", output); put("error", error) }
                    emit(ToolProgress.Done(ToolResult(listOf(TextPart(output)), isError = error)))
                }
            }
        }
    } }
}

private fun canonicalMockInput(value: JsonElement): String = when(value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonicalMockInput(it.value)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonicalMockInput)
    else -> value.toString()
}
