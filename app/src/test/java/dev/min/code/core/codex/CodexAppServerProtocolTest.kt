package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerProtocolTest {

    // -----------------------------------------------------------------------
    // 入站
    // -----------------------------------------------------------------------

    @Test
    fun `parses nested thread and turn notifications`() {
        val thread = parseCodexEvent("""{"method":"thread/started","params":{"thread":{"id":"thr-1"}}}""")
        val turn = parseCodexEvent(
            """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"completed"}}}""",
        )

        assertEquals("thr-1", (thread as CodexEvent.ThreadStarted).threadId)
        assertEquals("turn-1", (turn as CodexEvent.TurnCompleted).turnId)
        assertEquals("completed", turn.status)
    }

    @Test
    fun `turn failure carries the error message`() {
        val turn = parseCodexEvent(
            """{"method":"turn/completed","params":{"turn":{"id":"t1","status":"failed",
               "error":{"message":"upstream 502"}}}}""".trimIndent().replace("\n", ""),
        ) as CodexEvent.TurnCompleted

        assertEquals("failed", turn.status)
        assertEquals("upstream 502", turn.errorMessage)
    }

    /**
     * 回归：命令输出曾经和助手正文共用一个 TextDelta 事件，于是 shell 的 stdout
     * 被拼进了对话气泡。两者必须是不同的事件。
     */
    @Test
    fun `command output delta is not an agent message delta`() {
        val output = parseCodexEvent(
            """{"method":"item/commandExecution/outputDelta","params":{"itemId":"i1","delta":"total 12\n"}}""",
        )
        val message = parseCodexEvent(
            """{"method":"item/agentMessage/delta","params":{"itemId":"i2","delta":"好的"}}""",
        )

        assertTrue(output is CodexEvent.CommandOutputDelta)
        assertEquals("i1", (output as CodexEvent.CommandOutputDelta).itemId)
        assertTrue(message is CodexEvent.AgentMessageDelta)
        assertEquals("好的", (message as CodexEvent.AgentMessageDelta).delta)
    }

    @Test
    fun `reasoning deltas distinguish summary from raw text`() {
        val summary = parseCodexEvent(
            """{"method":"item/reasoning/summaryTextDelta","params":{"itemId":"r1","delta":"先看目录"}}""",
        ) as CodexEvent.ReasoningDelta
        val rawText = parseCodexEvent(
            """{"method":"item/reasoning/textDelta","params":{"itemId":"r1","delta":"hmm"}}""",
        ) as CodexEvent.ReasoningDelta

        assertTrue(summary.summary)
        assertFalse(rawText.summary)
    }

    @Test
    fun `item started and completed both carry the item`() {
        val started = parseCodexEvent(
            """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"i1","command":"ls"}}}""",
        ) as CodexEvent.ItemStarted
        val completed = parseCodexEvent(
            """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"i1",
               "command":"ls","status":"completed","exitCode":0,"aggregatedOutput":"a\nb\n"}}}"""
                .trimIndent().replace("\n", ""),
        ) as CodexEvent.ItemCompleted

        assertEquals("i1", started.item.id)
        assertNull(started.item.status)
        assertEquals("completed", completed.item.status)
    }

    /** 审批是服务端发起的请求，回信要用它这一帧的 id（原始形态） */
    @Test
    fun `command approval keeps the request id and available decisions`() {
        val approval = parseCodexEvent(
            """{"id":42,"method":"item/commandExecution/requestApproval","params":{"itemId":"i1",
               "command":"rm -rf build","cwd":"/workspace","reason":"destructive",
               "availableDecisions":["accept","acceptForSession","decline"]}}"""
                .trimIndent().replace("\n", ""),
        ) as CodexEvent.ApprovalRequest

        assertEquals("42", approval.requestId)
        // id 的原始形态必须留着：app-server 发的是整数（Rust RequestId::Integer），
        // 应答要原样放回 —— 统一成字符串在 Rust 侧就是另一个值，回调表查不中
        assertEquals(JsonPrimitive(42), approval.rawRequestId)
        assertEquals(CodexEvent.ApprovalRequest.Kind.Command, approval.kind)
        assertEquals("rm -rf build", approval.command)
        assertEquals("/workspace", approval.cwd)
        assertEquals(listOf("accept", "acceptForSession", "decline"), approval.availableDecisions)
    }

    @Test
    fun `file change approval is its own kind`() {
        val approval = parseCodexEvent(
            """{"id":"7","method":"item/fileChange/requestApproval","params":{"itemId":"i2","reason":"writes outside root"}}""",
        ) as CodexEvent.ApprovalRequest

        assertEquals(CodexEvent.ApprovalRequest.Kind.FileChange, approval.kind)
        assertEquals("7", approval.requestId)
    }

    /** 服务端发起的请求也带 id + method，不能被当成我们请求的应答 */
    @Test
    fun `server initiated request is not mistaken for a response`() {
        val event = parseCodexEvent(
            """{"id":9,"method":"item/fileChange/requestApproval","params":{"itemId":"x"}}""",
        )
        assertTrue(event is CodexEvent.ApprovalRequest)

        val response = parseCodexEvent("""{"id":"9","result":{"threadId":"thr-9"}}""")
        assertTrue(response is CodexEvent.Response)
        assertEquals("9", (response as CodexEvent.Response).id)
    }

    @Test
    fun `token usage arrives on its own notification`() {
        val usage = parseCodexEvent(
            """{"method":"thread/tokenUsage/updated","params":{"usage":{"inputTokens":120,"outputTokens":34,"totalTokens":154}}}""",
        ) as CodexEvent.TokenUsage

        assertEquals(120, usage.inputTokens)
        assertEquals(34, usage.outputTokens)
    }

    /** 认不出的方法留成 Unknown，不静默吞掉 */
    @Test
    fun `unknown methods survive as Unknown`() {
        val event = parseCodexEvent("""{"method":"turn/somethingNew","params":{"a":1}}""")
        assertEquals("turn/somethingNew", (event as CodexEvent.Unknown).method)
    }

    @Test
    fun `malformed lines never throw`() {
        assertNull(parseCodexEvent(""))
        assertNull(parseCodexEvent("not json"))
        assertNull(parseCodexEvent("[1,2,3]"))
    }

    /**
     * 类型不符的字段不能把整行打死（硬转会抛异常，一路冒到读取循环把 stdout 读死）。
     * 这一帧有内容，所以降级成 Unknown 而不是丢掉。
     */
    @Test
    fun `an item of the wrong shape degrades to Unknown instead of vanishing`() {
        val event = parseCodexEvent("""{"method":"item/started","params":{"item":["wrong"]}}""")
        assertTrue(event is CodexEvent.Unknown)

        // item 没有 type 也一样
        assertTrue(
            parseCodexEvent("""{"method":"item/completed","params":{"item":{"id":"x"}}}""")
                is CodexEvent.Unknown,
        )
    }

    // -----------------------------------------------------------------------
    // 出站
    // -----------------------------------------------------------------------

    /** 线上不带 jsonrpc 字段，照官方客户端的形状走 */
    @Test
    fun `initialize omits the jsonrpc member`() {
        val root = Json.parseToJsonElement(encodeCodexInitialize("0")) as JsonObject

        assertFalse(root.containsKey("jsonrpc"))
        assertEquals("initialize", (root["method"] as JsonPrimitive).content)
        val clientInfo = (root["params"] as JsonObject)["clientInfo"] as JsonObject
        assertEquals("min", (clientInfo["name"] as JsonPrimitive).content)
    }

    /** 握手第二步是通知，不能带 id —— 带了服务端会当成请求一直等我们的应答 */
    @Test
    fun `initialized is a notification without an id`() {
        val root = Json.parseToJsonElement(encodeCodexInitialized()) as JsonObject

        assertFalse(root.containsKey("id"))
        assertEquals("initialized", (root["method"] as JsonPrimitive).content)
    }

    @Test
    fun `turn start encodes structured text input`() {
        val root = Json.parseToJsonElement(encodeCodexTurnStart("7", "thr-1", "hello")) as JsonObject
        val params = root["params"] as JsonObject
        val message = (params["input"] as JsonArray).single() as JsonObject

        assertEquals("turn/start", (root["method"] as JsonPrimitive).content)
        assertEquals("text", (message["type"] as JsonPrimitive).content)
        assertEquals("hello", (message["text"] as JsonPrimitive).content)
        assertEquals("thr-1", (params["threadId"] as JsonPrimitive).content)
    }

    /** sandboxPolicy 是带 type 判别的对象，不是裸字符串 */
    @Test
    fun `workspace write sandbox carries writable roots and network access`() {
        val root = Json.parseToJsonElement(
            encodeCodexTurnStart(
                requestId = "1",
                threadId = "t",
                text = "hi",
                model = "gpt-5.6-terra",
                effort = "medium",
                sandbox = CodexSandbox.WORKSPACE_WRITE,
                writableRoots = listOf("/workspace"),
                networkAccess = true,
            ),
        ) as JsonObject
        val params = root["params"] as JsonObject
        val policy = params["sandboxPolicy"] as JsonObject

        assertEquals("workspaceWrite", (policy["type"] as JsonPrimitive).content)
        assertEquals("/workspace", ((policy["writableRoots"] as JsonArray).single() as JsonPrimitive).content)
        assertEquals("true", (policy["networkAccess"] as JsonPrimitive).content)
        assertEquals("gpt-5.6-terra", (params["model"] as JsonPrimitive).content)
        assertEquals("medium", (params["effort"] as JsonPrimitive).content)
    }

    @Test
    fun `read only sandbox has no writable roots`() {
        val root = Json.parseToJsonElement(
            encodeCodexThreadStart("1", cwd = "/workspace", sandbox = CodexSandbox.READ_ONLY),
        ) as JsonObject
        val policy = (root["params"] as JsonObject)["sandboxPolicy"] as JsonObject

        assertEquals("readOnly", (policy["type"] as JsonPrimitive).content)
        assertFalse(policy.containsKey("writableRoots"))
    }

    /** 空 cwd/model 不能拼进去：Codex 对空串的校验比缺字段严 */
    @Test
    fun `blank optional fields are omitted entirely`() {
        val params = (Json.parseToJsonElement(
            encodeCodexThreadStart("1", cwd = "", model = "  ", effort = null),
        ) as JsonObject)["params"] as JsonObject

        assertFalse(params.containsKey("cwd"))
        assertFalse(params.containsKey("model"))
        assertFalse(params.containsKey("effort"))
    }

    @Test
    fun `approval response echoes the request id and decision`() {
        val root = Json.parseToJsonElement(
            encodeCodexApprovalResponse(JsonPrimitive(42), CodexDecision.ACCEPT_FOR_SESSION),
        ) as JsonObject
        val result = root["result"] as JsonObject

        // 整数 id 原样回整数。Rust 侧 RequestId 是 #[serde(untagged)] 枚举，
        // String("42") 与 Integer(42) 的相等和哈希都不同 —— 回成字符串等于换了个 id
        assertEquals(JsonPrimitive(42), root["id"])
        assertEquals("acceptForSession", (result["decision"] as JsonPrimitive).content)
        assertFalse(root.containsKey("method"))
    }

    @Test
    fun `decision wire values round trip`() {
        CodexDecision.entries.forEach { decision ->
            assertEquals(decision, CodexDecision.fromWire(decision.wire))
        }
        assertNull(CodexDecision.fromWire("approved"))
    }

    // -----------------------------------------------------------------------
    // item -> ChatItem
    // -----------------------------------------------------------------------

    private fun item(json: String): CodexItem =
        (parseCodexEvent("""{"method":"item/completed","params":{"item":$json}}""")
            as CodexEvent.ItemCompleted).item

    /** commandExecution 必须落成界面认识的 Bash 卡，否则掉进兜底的 JSON 里 */
    @Test
    fun `command execution maps onto the Bash tool card`() {
        val chat = item(
            """{"type":"commandExecution","id":"i1","command":"ls -la","cwd":"/workspace",
               "status":"completed","exitCode":0,"aggregatedOutput":"total 0\n"}"""
                .trimIndent().replace("\n", ""),
        ).toChatItem("fallback") as ChatItem.ToolCall

        assertEquals("Bash", chat.name)
        assertEquals("ls -la", (chat.input["command"] as JsonPrimitive).content)
        assertEquals(ChatItem.ToolCall.Status.Done, chat.status)
        assertEquals("total 0\n", chat.result)
        assertFalse(chat.isError)
    }

    @Test
    fun `non zero exit code marks the command as an error`() {
        val chat = item(
            """{"type":"commandExecution","id":"i1","command":"false","status":"completed","exitCode":1}""",
        ).toChatItem("f") as ChatItem.ToolCall

        assertTrue(chat.isError)
    }

    @Test
    fun `declined command is an error and not a success`() {
        val chat = item(
            """{"type":"commandExecution","id":"i1","command":"rm -rf /","status":"declined"}""",
        ).toChatItem("f") as ChatItem.ToolCall

        assertEquals(ChatItem.ToolCall.Status.Error, chat.status)
        assertTrue(chat.isError)
    }

    /** 没有 status 的（item/started 那一帧）还在跑 */
    @Test
    fun `item without status is still running`() {
        val started = (parseCodexEvent(
            """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"i1","command":"sleep 5"}}}""",
        ) as CodexEvent.ItemStarted).item.toChatItem("f") as ChatItem.ToolCall

        assertEquals(ChatItem.ToolCall.Status.Running, started.status)
    }

    /** fileChange 给的是现成 diff，要走 editDiff 而不是让界面从入参现算 */
    @Test
    fun `file change carries a unified diff with a file header`() {
        val chat = item(
            """{"type":"fileChange","id":"f1","status":"completed","changes":[
               {"path":"src/a.kt","kind":"modify","diff":"@@ -1 +1 @@\n-a\n+b\n"}]}"""
                .trimIndent().replace("\n   ", ""),
        ).toChatItem("f") as ChatItem.ToolCall

        assertEquals("Edit", chat.name)
        assertEquals("src/a.kt", (chat.input["file_path"] as JsonPrimitive).content)
        val diff = requireNotNull(chat.editDiff)
        assertTrue(diff.contains("--- a/src/a.kt"))
        assertTrue(diff.contains("+++ b/src/a.kt"))
        assertTrue(diff.contains("+b"))
    }

    /** 已经带文件头的 diff 不要再包一层 */
    @Test
    fun `file change does not double wrap an already headed diff`() {
        val chat = item(
            """{"type":"fileChange","id":"f1","changes":[
               {"path":"a.kt","diff":"--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-x\n+y\n"}]}"""
                .trimIndent().replace("\n   ", ""),
        ).toChatItem("f") as ChatItem.ToolCall

        assertEquals(1, requireNotNull(chat.editDiff).split("--- a/a.kt").size - 1)
    }

    @Test
    fun `agent message and reasoning become text items`() {
        val message = item("""{"type":"agentMessage","id":"m1","text":"做完了"}""").toChatItem("f")
        val reasoning = item(
            """{"type":"reasoning","id":"r1","summary":["先读目录","再改文件"],"content":["raw"]}""",
        ).toChatItem("f")

        assertEquals("做完了", (message as ChatItem.AssistantText).text)
        // 摘要优先于原始思考块
        assertEquals("先读目录\n\n再改文件", (reasoning as ChatItem.Thinking).text)
    }

    @Test
    fun `web search maps onto the WebSearch card`() {
        val chat = item("""{"type":"webSearch","id":"w1","query":"kotlin flow","status":"completed"}""")
            .toChatItem("f") as ChatItem.ToolCall

        assertEquals("WebSearch", chat.name)
        assertEquals("kotlin flow", (chat.input["query"] as JsonPrimitive).content)
    }

    @Test
    fun `user message content blocks are flattened to text`() {
        val chat = item(
            """{"type":"userMessage","id":"u1","content":[{"type":"text","text":"你好"}]}""",
        ).toChatItem("f")

        assertEquals("你好", (chat as ChatItem.UserText).text)
    }

    /** 认不出的 item 落兜底卡，原始 JSON 留在入参里，不能静默消失 */
    @Test
    fun `unknown item types fall back instead of disappearing`() {
        val chat = item("""{"type":"somethingNew","id":"x1","foo":"bar"}""").toChatItem("f")
            as ChatItem.ToolCall

        assertEquals("somethingNew", chat.name)
        assertEquals("bar", (chat.input["foo"] as JsonPrimitive).content)
    }

    @Test
    fun `item without an id uses the fallback id`() {
        val chat = item("""{"type":"agentMessage","text":"hi"}""").toChatItem("fallback-7")
        assertEquals("fallback-7", chat.id)
    }

    @Test
    fun `a warning shows its sentence instead of an unknown-method line`() {
        val event = parseCodexEvent("""{"method":"warning","params":{"threadId":"t","message":"config.toml: model_reasoning_effort is deprecated"}}""")
        assertEquals(CodexEvent.Warning("config.toml: model_reasoning_effort is deprecated"), event)

        // 字段改了名也别什么都不显示：退回第一个非空字符串
        val renamed = parseCodexEvent("""{"method":"warning","params":{"text":"  ","note":"sandbox is off"}}""")
        assertEquals(CodexEvent.Warning("sandbox is off"), renamed)
    }

    @Test
    fun `a warning with nothing to say still leaves a trace`() {
        val event = parseCodexEvent("""{"method":"warning","params":{"count":3}}""")
        assertTrue(event is CodexEvent.Unknown)
    }
}
