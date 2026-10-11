package dev.min.agent.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `-p` 的 stream-json：输出（`--output-format stream-json`，行的形状照 Agent SDK 的 SDKMessage）与输入
 * （`--input-format stream-json`，stdin 逐行 JSON 用户消息、一个进程里多轮）。
 *
 * 形状快照用「键集合」钉死：SDK 里必带的键必须在、Min 没有数据的键必须不在（省略不编），
 * 值只对拍有确定含义的那几个。端到端走真 DesktopApp + 本地假模型（HeadlessTestKit），与 Main 同一条 runHeadless。
 * CC 出处：cc-sdk-typescript.md 的 SDKSystemMessage / SDKAssistantMessage / SDKUserMessage / SDKUserMessageReplay /
 * SDKResultMessage / SDKPartialAssistantMessage（:1342–1830），cc-cli-reference.md 的 `--input-format` /
 * `--replay-user-messages` / `--include-partial-messages`，headless 页的 Stream responses。
 */
class StreamJsonTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(300_000) { block() } }

    // ---- 参数面 ----

    private fun parse(vararg extra: String) = parseArgs(arrayOf("--model", "m") + extra, tmp.root.toPath())

    @Test fun `bare -p with input-format stream-json is non-interactive with an empty command line prompt`() {
        val args = parse("-p", "--input-format", "stream-json", "--output-format", "stream-json")
        assertEquals(InputFormat.StreamJson, args.inputFormat)
        assertEquals("", args.prompt)
        // 位置无关：-p 在最后、-p 后面跟的是别的参数
        assertEquals("", parse("--input-format", "stream-json", "-p").prompt)
        assertEquals("", parse("--print", "--input-format", "stream-json").prompt)
        // 文档示例：claude -p --output-format json --input-format stream-json（输出不一定是 stream-json）
        assertEquals(OutputFormat.Json, parse("-p", "--output-format", "json", "--input-format", "stream-json").outputFormat)
        // 默认还是 text 输入；--input-format text 显式给也行
        assertEquals(InputFormat.Text, parse("-p", "hi").inputFormat)
        assertEquals(InputFormat.Text, parse("-p", "hi", "--input-format", "text").inputFormat)
    }

    @Test fun `verbose is accepted as a no-op so the documented stream-json command lines start`() {
        // headless 页的示例都是 -p … --output-format stream-json --verbose；不认它会让 SDK / 文档里的命令行直接报「不认识的参数」
        val args = parse("-p", "hi", "--output-format", "stream-json", "--verbose", "--include-partial-messages")
        assertEquals(OutputFormat.StreamJson, args.outputFormat)
        assertTrue(args.includePartialMessages)
        assertEquals("", parse("-p", "--verbose", "--input-format", "stream-json").prompt)
    }

    @Test fun `a prompt that merely looks like an option is still the prompt`() {
        // 只有「已知参数名」才让 -p 变成没带提示词；任意以 - 开头的词仍是提示词（老行为）
        assertEquals("-not-a-flag", parse("-p", "-not-a-flag").prompt)
    }

    @Test fun `input-format stream-json argument mistakes fail at startup`() {
        // -p 没带提示词又不是 stream-json 输入：老报错保留
        assertThrows(UsageException::class.java) { parse("-p") }
        assertThrows(UsageException::class.java) { parse("-p", "--output-format", "json") }
        // stream-json 输入要 -p，且 -p 后面不再跟提示词
        assertThrows(UsageException::class.java) { parse("--input-format", "stream-json") }
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--input-format", "stream-json") }
        assertThrows(UsageException::class.java) { parse("-p", "--input-format", "xml") }
        // stdin 是消息通道，--approve ask 没法再从 stdin 读答复
        assertThrows(UsageException::class.java) { parse("-p", "--input-format", "stream-json", "--approve", "ask") }
        parse("-p", "--input-format", "stream-json", "--approve", "yes")
    }

    @Test fun `replay-user-messages needs stream-json on both ends`() {
        val both = arrayOf("-p", "--input-format", "stream-json", "--output-format", "stream-json")
        assertTrue(parse(*both, "--replay-user-messages").replayUserMessages)
        // cc-cli-reference：Requires --input-format stream-json and --output-format stream-json
        assertThrows(UsageException::class.java) { parse("-p", "--input-format", "stream-json", "--replay-user-messages") }
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--output-format", "stream-json", "--replay-user-messages") }
        assertThrows(UsageException::class.java) { parse("--replay-user-messages") }
    }

    // ---- stdin 行的解析 ----

    private fun user(line: InputLine) = line as InputLine.User

    @Test fun `a user message with a string content parses`() {
        val line = user(parseInputLine("""{"type":"user","message":{"role":"user","content":"你好"}}""", 1))
        assertEquals("你好", line.text)
        assertTrue(line.extraParts.isEmpty())
        assertNull(line.uuid)
        assertTrue(line.shouldQuery)
    }

    @Test fun `content blocks join their text and keep base64 images, uuid and shouldQuery are read`() {
        val line = user(parseInputLine(
            """{"type":"user","uuid":"u-7","shouldQuery":false,"parent_tool_use_id":null,"message":{"role":"user","content":[
                {"type":"text","text":"看这张图"},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},
                {"type":"text","text":"然后总结"}]}}""".replace("\n", ""), 3,
        ))
        assertEquals("看这张图\n然后总结", line.text)
        assertEquals(1, line.extraParts.size)
        assertEquals("u-7", line.uuid)
        assertFalse(line.shouldQuery)
    }

    private fun reasonOf(raw: String): String = (parseInputLine(raw, 5) as InputLine.Reject).reason

    @Test fun `broken lines are rejected with the line number`() {
        assertTrue(reasonOf("this is not json").contains("第 5 行不是合法的 JSON"))
        assertTrue(reasonOf("""{"type":"user",""").contains("第 5 行不是合法的 JSON"))
        assertTrue(reasonOf("[1,2]").contains("不是对象"))
        assertTrue(reasonOf("""{"message":{}}""").contains("缺少字符串字段 type"))
        assertTrue(reasonOf("""{"type":"user"}""").contains("缺少 message"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"assistant","content":"x"}}""").contains("role"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"user"}}""").contains("缺少 content"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"user","content":7}}""").contains("字符串或内容块数组"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"x"}]}}""").contains("tool_result"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"url","url":"http://x"}}]}}""").contains("base64"))
        assertTrue(reasonOf("""{"type":"user","message":{"role":"user","content":"   "}}""").contains("没有文本内容"))
    }

    @Test fun `keep_alive is ignored, other message types are skipped and control messages are parsed into their own lines`() {
        assertEquals(InputLine.KeepAlive, parseInputLine("""{"type":"keep_alive"}""", 1))
        assertTrue(parseInputLine("""{"type":"mystery"}""", 2) is InputLine.Skip)
        val control = parseInputLine("""{"type":"control_request","request_id":"r1","request":{"subtype":"interrupt"}}""", 3) as InputLine.Control
        assertEquals("r1", control.requestId)
        assertEquals("interrupt", control.request.str("subtype"))
        val response = parseInputLine("""{"type":"control_response","response":{"subtype":"success","request_id":"q1","response":{"behavior":"deny"}}}""", 4) as InputLine.ControlResponse
        assertEquals("q1", response.requestId)
        // 没有 request_id 的控制消息没法对应任何东西：当坏行
        assertTrue(parseInputLine("""{"type":"control_request","request":{"subtype":"interrupt"}}""", 5) is InputLine.Reject)
        assertTrue(parseInputLine("""{"type":"control_request","request_id":"r","request":7}""", 6) is InputLine.Reject)
        assertTrue(parseInputLine("""{"type":"control_response","response":{}}""", 7) is InputLine.Reject)
    }

    // ---- 端到端 ----

    private class Out(val exit: Int, val stdout: String, val stderr: String, val sessionId: String, val requests: List<JsonObject>) {
        val lines: List<JsonObject> by lazy { stdout.lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList() }
        fun ofType(type: String) = lines.filter { it["type"]?.jsonPrimitive?.contentOrNull == type }
        val results get() = ofType("result")
        val assistants get() = ofType("assistant")
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private val streamIn = listOf("-p", "--input-format", "stream-json")
    private val streamOut = listOf("--output-format", "stream-json")

    private fun userLine(text: String, extra: String = "") =
        """{"type":"user","message":{"role":"user","content":${JsonPrimitive(text)}}$extra}""" + "\n"

    /** 真 DesktopApp + 假模型，走 Main 同一条 runHeadless；[stdin] 给 stream-json 输入（单轮 -p 时留空） */
    private fun runHeadlessWith(name: String, extra: List<String>, stdin: String = "", route: (String) -> String?): Out = run {
        ModelServer(route).use { server ->
            val home = Files.createDirectories(tmp.root.toPath().resolve("$name-home"))
            val work = Files.createDirectories(tmp.root.toPath().resolve("$name-work"))
            val args = parseArgs(baselineArgv(server, work) + extra.toTypedArray(), work)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val app = DesktopApp(args, scope(), home, claudeHome = Files.createDirectories(home.resolve("claude-home")))
            app.checkKey()
            val runtime = app.openSession()
            try {
                val exit = runHeadless(
                    app, runtime, args, scope(),
                    BufferedReader(StringReader(stdin)), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"),
                )
                Out(exit, out.toString("UTF-8"), err.toString("UTF-8"), runtime.sessionId.value, server.requests.toList())
            } finally {
                app.exitSession(runtime).join()
            }
        }
    }

    private fun reply(text: String): (String) -> String? = { _ -> textResponse(text) }

    @Test fun `the init line carries the SDK system fields`() {
        val out = runHeadlessWith("init", listOf("-p", "T_INIT") + streamOut, route = reply("INIT_OK"))
        val init = out.lines.first()
        assertEquals("system", init.str("type"))
        assertEquals("init", init.str("subtype"))
        // SDKSystemMessage 里 Min 有数据的键；apiKeySource / claude_code_version / betas 是 CC 自己的，Min 没有，省略不编
        val expected = setOf(
            "type", "subtype", "uuid", "session_id", "cwd", "model", "tools", "mcp_servers", "permissionMode",
            "slash_commands", "skills", "plugins", "agents", "output_style",
        )
        assertEquals(expected, init.keys)
        assertEquals(out.sessionId, init.str("session_id"))
        assertTrue(init["tools"] is JsonArray && init["tools"]!!.jsonArray.isNotEmpty())
        assertEquals("default", init.str("output_style"))
        assertEquals("bypassPermissions", init.str("permissionMode"))
        assertTrue(init["uuid"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test fun `a plain reply is init then assistant then result with the SDK shapes`() {
        val out = runHeadlessWith("shape", listOf("-p", "T_SHAPE") + streamOut, route = reply("SHAPE_OK"))
        assertEquals(0, out.exit)
        assertEquals(listOf("system", "assistant", "result"), out.lines.map { it.str("type")!! })

        val assistant = out.assistants.single()
        // SDKAssistantMessage：type / uuid / session_id / message / parent_tool_use_id（可选的 error / aborted / timestamp 等 Min 没有）
        assertEquals(setOf("type", "uuid", "session_id", "message", "parent_tool_use_id"), assistant.keys)
        assertEquals(JsonNull, assistant["parent_tool_use_id"])
        assertEquals(out.sessionId, assistant.str("session_id"))
        val message = assistant["message"]!!.jsonObject
        // BetaMessage 的子集：id / type / role / content / model / stop_reason / usage
        assertEquals(setOf("id", "type", "role", "content", "model", "stop_reason", "usage"), message.keys)
        assertEquals("message", message.str("type"))
        assertEquals("assistant", message.str("role"))
        assertEquals("scripted", message.str("model"))
        assertEquals("end_turn", message.str("stop_reason"))
        val block = message["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", block.str("type"))
        assertEquals("SHAPE_OK", block.str("text"))
        assertEquals(setOf("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"), message["usage"]!!.jsonObject.keys)

        val result = out.results.single()
        // SDKResultMessage success 臂里 Min 有数据的键（modelUsage / duration_api_ms 等 Min 没有，省略不编）
        val required = setOf(
            "type", "subtype", "uuid", "is_error", "result", "session_id", "stop_reason", "num_turns",
            "duration_ms", "usage", "terminal_reason", "permission_denials",
        )
        assertTrue("缺：${required - result.keys}", result.keys.containsAll(required))
        assertFalse("success 臂不带 errors", "errors" in result.keys)
        assertEquals("success", result.str("subtype"))
        assertEquals(JsonPrimitive(false), result["is_error"])
        assertEquals("SHAPE_OK", result.str("result"))
        assertEquals("end_turn", result.str("stop_reason"))
        assertEquals("completed", result.str("terminal_reason"))
        assertEquals(JsonArray(emptyList()), result["permission_denials"])
        assertEquals(1, result["num_turns"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `one assistant row per content block, rows of one message share its id`() {
        val out = runHeadlessWith("blocks", listOf("-p", "T_BLOCKS") + streamOut) { last ->
            if (last.contains("T_BLOCKS")) toolsResponse(Triple("call_b", "Bash", buildJsonObject { put("command", "echo b") }), text = "先说一句")
            else textResponse("BLOCKS_DONE")
        }
        assertEquals(listOf("system", "assistant", "assistant", "user", "assistant", "result"), out.lines.map { it.str("type")!! })
        val (text, tool, final) = out.assistants
        // SDK：同一次 API 回复可以产出好几条 assistant 消息（一个 content block 一条），共用 message.id
        fun messageOf(row: JsonObject) = row["message"]!!.jsonObject
        assertEquals(1, messageOf(text)["content"]!!.jsonArray.size)
        assertEquals("text", messageOf(text)["content"]!!.jsonArray.single().jsonObject.str("type"))
        assertEquals("tool_use", messageOf(tool)["content"]!!.jsonArray.single().jsonObject.str("type"))
        assertEquals(messageOf(text).str("id"), messageOf(tool).str("id"))
        assertFalse(messageOf(final).str("id") == messageOf(text).str("id"))
        // 停止原因与用量在流结束时才知道：前面的块是 null、没有 usage，最后一块才带
        assertEquals(JsonNull, messageOf(text)["stop_reason"])
        assertNull(messageOf(text)["usage"])
        assertEquals("tool_use", messageOf(tool).str("stop_reason"))
        assertNotNull(messageOf(tool)["usage"])
        // 每一行都是完整的 SDKAssistantMessage
        out.assistants.forEach { assertEquals(setOf("type", "uuid", "session_id", "message", "parent_tool_use_id"), it.keys) }
        assertEquals(3, out.assistants.map { it.str("uuid") }.toSet().size)
    }

    @Test fun `a tool round trip gives a user line whose tool_result matches the assistant tool_use`() {
        val out = runHeadlessWith("tool", listOf("-p", "T_TOOL") + streamOut) { last ->
            if (last.contains("T_TOOL")) toolResponse("call_1", "Bash", buildJsonObject { put("command", "echo hi-stream") })
            else textResponse("TOOL_DONE")
        }
        assertEquals(0, out.exit)
        assertEquals(listOf("system", "assistant", "user", "assistant", "result"), out.lines.map { it.str("type")!! })
        val toolUse = out.assistants.first()["message"]!!.jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }.single { it.str("type") == "tool_use" }
        assertEquals("tool_use", out.assistants.first()["message"]!!.jsonObject.str("stop_reason"))
        assertEquals("Bash", toolUse.str("name"))

        val userLine = out.ofType("user").single()
        // SDKUserMessage（工具结果）：type / uuid / session_id / message / parent_tool_use_id；没有 isReplay
        assertEquals(setOf("type", "uuid", "session_id", "message", "parent_tool_use_id"), userLine.keys)
        assertEquals(JsonNull, userLine["parent_tool_use_id"])
        val message = userLine["message"]!!.jsonObject
        assertEquals("user", message.str("role"))
        val result = message["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result.str("type"))
        assertEquals(toolUse.str("id"), result.str("tool_use_id"))
        assertEquals(JsonPrimitive(false), result["is_error"])
        assertTrue(result["content"]!!.toString().contains("hi-stream"))
    }

    @Test fun `include-partial-messages adds stream events in the Anthropic order before the whole message`() {
        val out = runHeadlessWith("partial", listOf("-p", "T_PART") + streamOut + "--include-partial-messages", route = reply("PARTIAL_TEXT"))
        assertEquals(0, out.exit)
        val events = out.ofType("stream_event")
        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"),
            events.map { it["event"]!!.jsonObject.str("type")!! },
        )
        events.forEach {
            // SDKPartialAssistantMessage：parent_tool_use_id 恒为 null（流事件只给主会话）
            assertEquals(setOf("type", "event", "parent_tool_use_id", "uuid", "session_id"), it.keys)
            assertEquals(JsonNull, it["parent_tool_use_id"])
            assertEquals(out.sessionId, it.str("session_id"))
        }
        val start = events[1]["event"]!!.jsonObject
        assertEquals(0, start["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("text", start["content_block"]!!.jsonObject.str("type"))
        val delta = events[2]["event"]!!.jsonObject["delta"]!!.jsonObject
        assertEquals("text_delta", delta.str("type"))
        assertEquals("PARTIAL_TEXT", delta.str("text"))
        // 流在完整消息之前合上；之后才是整条 assistant 与 result
        val types = out.lines.map { it.str("type")!! }
        assertEquals(listOf("system") + List(6) { "stream_event" } + listOf("assistant", "result"), types)
        assertEquals("end_turn", events[4]["event"]!!.jsonObject["delta"]!!.jsonObject.str("stop_reason"))
    }

    @Test fun `a streamed tool call becomes a tool_use block start and input_json deltas`() {
        val out = runHeadlessWith("partial-tool", listOf("-p", "T_PT") + streamOut + "--include-partial-messages") { last ->
            if (last.contains("T_PT")) toolResponse("call_p", "Bash", buildJsonObject { put("command", "echo p") }) else textResponse("PT_DONE")
        }
        val events = out.ofType("stream_event").map { it["event"]!!.jsonObject }
        val toolStart = events.first { it.str("type") == "content_block_start" && it["content_block"]!!.jsonObject.str("type") == "tool_use" }
        assertEquals("Bash", toolStart["content_block"]!!.jsonObject.str("name"))
        // 瞬时流里没有调用 id：tool_use 起始块不带 id（完整 assistant 行里才有），不编
        assertNull(toolStart["content_block"]!!.jsonObject["id"])
        val json = events.filter { it.str("type") == "content_block_delta" && it["delta"]!!.jsonObject.str("type") == "input_json_delta" }
            .joinToString("") { it["delta"]!!.jsonObject.str("partial_json")!! }
        assertTrue(json, json.contains("echo p"))
    }

    @Test fun `without include-partial-messages there are no stream events`() {
        val out = runHeadlessWith("no-partial", listOf("-p", "T_NP") + streamOut, route = reply("NP"))
        assertTrue(out.ofType("stream_event").isEmpty())
    }

    // ---- 多轮输入 ----

    private fun twoTurns(text: String) = { last: String ->
        when {
            last.contains("T_ONE") -> textResponse("A_ONE")
            last.contains("T_TWO") -> textResponse("A_TWO")
            else -> textResponse(text)
        }
    }

    @Test fun `stdin messages run as consecutive turns in one process and one session`() {
        val out = runHeadlessWith("multi", streamIn + streamOut, userLine("T_ONE") + userLine("T_TWO"), twoTurns("OTHER"))
        assertEquals(0, out.exit)
        assertEquals(listOf("system", "assistant", "result", "assistant", "result"), out.lines.map { it.str("type")!! })
        assertEquals(listOf("A_ONE", "A_TWO"), out.results.map { it.str("result") })
        assertEquals(setOf(out.sessionId), out.lines.map { it.str("session_id") }.toSet())
        // 同一个会话里的第二轮看得见第一轮：第二个请求带着第一轮的回答
        assertEquals(2, out.requests.size)
        assertTrue(out.requests[1].toString().contains("A_ONE"))
        // 每轮自己的结果：num_turns 各为 1（一次模型请求）
        assertEquals(listOf(1, 1), out.results.map { it["num_turns"]!!.jsonPrimitive.content.toInt() })
    }

    @Test fun `blank lines and keep_alive are ignored and unknown message types only warn`() {
        val stdin = "\n" + """{"type":"keep_alive"}""" + "\n" + """{"type":"mystery"}""" + "\n" + userLine("T_ONE")
        val out = runHeadlessWith("skips", streamIn + streamOut, stdin, twoTurns("X"))
        assertEquals(0, out.exit)
        assertEquals("A_ONE", out.results.single().str("result"))
        assertTrue(out.stderr, out.stderr.contains("mystery"))
    }

    @Test fun `closing stdin without any message ends cleanly with only the init line`() {
        val out = runHeadlessWith("empty", streamIn + streamOut, "", twoTurns("X"))
        assertEquals(0, out.exit)
        assertEquals(listOf("system"), out.lines.map { it.str("type")!! })
        assertEquals(0, out.requests.size)
    }

    @Test fun `replay-user-messages echoes each input message and uuids are echoed on the answering frames`() {
        val stdin = userLine("T_ONE", ""","uuid":"u-1"""") + userLine("T_TWO")
        val out = runHeadlessWith("replay", streamIn + streamOut + "--replay-user-messages", stdin, twoTurns("X"))
        assertEquals(0, out.exit)
        // 回显在收到消息时就发（读 stdin 与跑回合是两条协程）：每条回显都在它自己那一轮的助手消息之前
        val types = out.lines.map { it.str("type")!! }
        assertEquals(listOf("assistant", "result", "assistant", "result"), types.filter { it != "user" && it != "system" })
        val replayAt = types.indices.filter { types[it] == "user" }
        val assistantAt = types.indices.filter { types[it] == "assistant" }
        assertTrue(replayAt[0] < assistantAt[0] && replayAt[1] < assistantAt[1])
        val replays = out.ofType("user")
        // SDKUserMessageReplay：uuid 必带（没给就生成）、isReplay: true、message 原样、parent_tool_use_id null
        replays.forEach {
            assertEquals(setOf("type", "message", "parent_tool_use_id", "session_id", "uuid", "isReplay"), it.keys)
            assertEquals(JsonPrimitive(true), it["isReplay"])
            assertEquals(JsonNull, it["parent_tool_use_id"])
        }
        assertEquals("u-1", replays[0].str("uuid"))
        assertEquals("T_ONE", replays[0]["message"]!!.jsonObject.str("content"))
        assertTrue(replays[1].str("uuid")!!.isNotBlank())
        // 自带 uuid 的那条：首条助手消息与结果回显 user_message_uuid；没带的那条两处都没有
        assertEquals("u-1", out.assistants[0].str("user_message_uuid"))
        assertEquals("u-1", out.results[0].str("user_message_uuid"))
        assertNull(out.assistants[1]["user_message_uuid"])
        assertNull(out.results[1]["user_message_uuid"])
    }

    @Test fun `without replay-user-messages the input is not echoed`() {
        val out = runHeadlessWith("no-replay", streamIn + streamOut, userLine("T_ONE"), twoTurns("X"))
        assertTrue(out.ofType("user").isEmpty())
    }

    @Test fun `shouldQuery false holds a message and merges it into the next turn`() {
        val stdin = userLine("CONTEXT_ONLY", ""","shouldQuery":false""") + userLine("T_ONE")
        val out = runHeadlessWith("held", streamIn + streamOut, stdin, twoTurns("X"))
        assertEquals(0, out.exit)
        assertEquals(1, out.requests.size)
        assertEquals(1, out.results.size)
        val sent = out.requests.single().toString()
        assertTrue(sent, sent.contains("CONTEXT_ONLY") && sent.contains("T_ONE"))
    }

    // ---- 出错 ----

    @Test fun `a bad json line stops the input with an error result and a non-zero exit`() {
        val out = runHeadlessWith("bad", streamIn + streamOut, "this is not json\n", twoTurns("X"))
        assertEquals(1, out.exit)
        assertTrue(out.stderr, out.stderr.contains("第 1 行不是合法的 JSON"))
        val result = out.results.single()
        assertEquals("error_during_execution", result.str("subtype"))
        assertEquals(JsonPrimitive(true), result["is_error"])
        assertNull("error 臂没有 result 字段", result["result"])
        assertTrue(result["errors"]!!.toString().contains("第 1 行不是合法的 JSON"))
        assertEquals(0, out.requests.size)
        // stdout 仍然是干净的逐行 JSON：init 然后这个结果行
        assertEquals(listOf("system", "result"), out.lines.map { it.str("type")!! })
    }

    @Test fun `a bad line after good ones keeps the finished turns and ends the run`() {
        val stdin = userLine("T_ONE") + "{oops\n" + userLine("T_TWO")
        val out = runHeadlessWith("bad-late", streamIn + streamOut, stdin, twoTurns("X"))
        assertEquals(1, out.exit)
        assertEquals(1, out.requests.size)
        assertEquals(listOf("success", "error_during_execution"), out.results.map { it.str("subtype") })
        assertTrue(out.results[1]["errors"]!!.toString().contains("第 2 行"))
    }

    @Test fun `a failing turn reports the same subtype and exit code as the json output`() {
        val failing: (String) -> String? = { _ -> null }
        val single = runHeadlessWith("fail-json", listOf("-p", "T_FAIL", "--output-format", "json"), route = failing)
        val streamed = runHeadlessWith("fail-stream", streamIn + streamOut, userLine("T_FAIL"), failing)
        val jsonResult = Json.parseToJsonElement(single.stdout.trim()).jsonObject
        assertEquals(1, single.exit)
        assertEquals(single.exit, streamed.exit)
        assertEquals(jsonResult.str("subtype"), streamed.results.single().str("subtype"))
        assertEquals("error_during_execution", streamed.results.single().str("subtype"))
        assertTrue(streamed.results.single()["errors"] is JsonArray)
    }

    @Test fun `the exit code follows the last turn`() {
        // 第一轮失败（假模型 400）、第二轮成功（失败的用户消息还留在历史里，所以先认第二轮的标记）：与 json 打印的「最后一轮的结果」同一口径，退出码 0
        val route: (String) -> String? = { last -> if (last.contains("T_GOOD")) textResponse("FINE") else null }
        val out = runHeadlessWith("last-turn", streamIn + streamOut, userLine("T_BAD") + userLine("T_GOOD"), route)
        assertEquals(listOf("error_during_execution", "success"), out.results.map { it.str("subtype") })
        assertEquals(0, out.exit)
    }

    // ---- 与别的输出格式、结构化输出共存 ----

    @Test fun `text output prints each turn's final reply and json output prints only the last turn's result`() {
        val text = runHeadlessWith("in-text", streamIn, userLine("T_ONE") + userLine("T_TWO"), twoTurns("X"))
        assertEquals(listOf("A_ONE", "A_TWO"), text.stdout.lines().filter { it.isNotBlank() })
        val json = runHeadlessWith("in-json", streamIn + listOf("--output-format", "json"), userLine("T_ONE") + userLine("T_TWO"), twoTurns("X"))
        val only = Json.parseToJsonElement(json.stdout.trim()).jsonObject
        assertEquals("result", only.str("type"))
        assertEquals("A_TWO", only.str("result"))
        assertEquals(0, json.exit)
    }

    @Test fun `json-schema structured output works per turn with stream-json in and out`() {
        val schema = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""
        val route: (String) -> String? = { _ -> toolResponse("so1", "StructuredOutput", buildJsonObject { put("answer", "CC_ANSWER") }) }
        val out = runHeadlessWith("schema", streamIn + streamOut + listOf("--json-schema", schema), userLine("T_ONE") + userLine("T_TWO"), route)
        assertEquals(0, out.exit)
        assertEquals(2, out.results.size)
        out.results.forEach {
            assertEquals("success", it.str("subtype"))
            assertEquals("CC_ANSWER", it["structured_output"]!!.jsonObject.str("answer"))
        }
    }

    @Test fun `a local command in the input gives a success result with local_command and no turn`() {
        val out = runHeadlessWith("local", streamIn + streamOut, userLine("/advisor"), twoTurns("X"))
        assertEquals(0, out.exit)
        val result = out.results.single()
        assertEquals("success", result.str("subtype"))
        assertEquals("advisor", result.str("local_command"))
        assertEquals(0, out.requests.size)
    }

    @Test fun `single prompt stream-json still ends with exactly one result line`() {
        val out = runHeadlessWith("single", listOf("-p", "T_SINGLE") + streamOut, route = reply("SINGLE_OK"))
        assertEquals(1, out.results.size)
        assertEquals("result", out.lines.last().str("type"))
        assertNotNull(out.results.single()["uuid"])
    }
}
