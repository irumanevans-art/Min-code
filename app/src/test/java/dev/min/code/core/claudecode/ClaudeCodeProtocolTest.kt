package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言不是"我们希望协议长这样"，而是对照官方 CLI
 * (@anthropic-ai/claude-code v2.1.246) 二进制里内嵌的 zod schema 逐条校对出来的。
 * 相关 schema 片段写在各测试的注释里，升级 CLI 时可以拿来复核。
 */
class ClaudeCodeProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    // region control_response 信封

    /**
     * CLI 自己构造应答的写法：
     * `{type:"control_response", response:{subtype:"success", request_id:e, response:t}}`
     * `subtype` 少了会命中 "canUseTool returned a schema-invalid permission result"。
     */
    @Test
    fun `permission response carries success subtype in the envelope`() {
        val obj = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse(requestId = "req_1", allow = true)
        ).jsonObject

        assertEquals("control_response", obj["type"]?.jsonPrimitive?.contentOrNull)
        val response = obj["response"]!!.jsonObject
        assertEquals("success", response["subtype"]?.jsonPrimitive?.contentOrNull)
        assertEquals("req_1", response["request_id"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * allow 变体 schema：`{behavior:"allow", updatedInput?:object, ...}`。
     * updatedInput 是可选的，且 CLI 会拿它重跑工具入参校验——原样回显只会多一次失败机会，
     * 所以我们不带。
     */
    @Test
    fun `allow omits updatedInput`() {
        val result = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse(requestId = "req_1", allow = true)
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject

        assertEquals("allow", result["behavior"]?.jsonPrimitive?.contentOrNull)
        assertNull(result["updatedInput"])
    }

    /** deny 变体 schema：`{behavior:"deny", message:string}`，message 必填 */
    @Test
    fun `deny always carries a message`() {
        val withoutReason = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse(requestId = "r", allow = false)
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("deny", withoutReason["behavior"]?.jsonPrimitive?.contentOrNull)
        assertTrue(withoutReason["message"]!!.jsonPrimitive.content.isNotBlank())

        val withReason = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse(requestId = "r", allow = false, denyMessage = "太危险了")
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("太危险了", withReason["message"]?.jsonPrimitive?.contentOrNull)
    }

    /** 空白的拒绝理由要回落到默认文案，不能发一个空 message */
    @Test
    fun `blank deny message falls back to default`() {
        val result = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse(requestId = "r", allow = false, denyMessage = "   ")
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
        assertTrue(result["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `control error uses the error subtype`() {
        val response = json.parseToJsonElement(
            encodeClaudeCodeControlError("req_9", "unsupported")
        ).jsonObject["response"]!!.jsonObject

        assertEquals("error", response["subtype"]?.jsonPrimitive?.contentOrNull)
        assertEquals("req_9", response["request_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("unsupported", response["error"]?.jsonPrimitive?.contentOrNull)
    }

    // endregion

    // region stdin user 消息

    /**
     * user 帧 schema：`{type:"user", message:..., parent_tool_use_id: e().nullable(), ...}`。
     * 实测省略也能被接受（CLI 解析宽容），但官方 SDK 总是显式写 null，我们对齐它。
     */
    @Test
    fun `user message includes explicit null parent_tool_use_id`() {
        val obj = json.parseToJsonElement(encodeClaudeCodeUserMessage("hello")).jsonObject

        assertEquals("user", obj["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(obj.containsKey("parent_tool_use_id"))
        assertEquals(JsonNull, obj["parent_tool_use_id"])
    }

    /** 键盘输入显式标 origin.kind=human；实测该字段会被 CLI 原样回显在 result 帧里 */
    @Test
    fun `user message stamps human origin`() {
        val obj = json.parseToJsonElement(encodeClaudeCodeUserMessage("hi")).jsonObject
        assertEquals("human", obj["origin"]!!.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `user message body is a text content block`() {
        val message = json.parseToJsonElement(encodeClaudeCodeUserMessage("hi")).jsonObject["message"]!!.jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.contentOrNull)
        val block = message["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", block["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hi", block["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `interrupt request has request_id and subtype`() {
        val obj = json.parseToJsonElement(encodeClaudeCodeInterrupt("req_i")).jsonObject
        assertEquals("control_request", obj["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("req_i", obj["request_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("interrupt", obj["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull)
    }

    // endregion

    // region stdout 解析

    @Test
    fun `init event carries session model and tools`() {
        val events = parseClaudeCodeEvents(
            """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5","tools":["Bash","Read"]}"""
        )
        val init = events.single() as ClaudeCodeEvent.Init
        assertEquals("s1", init.sessionId)
        assertEquals("claude-opus-5", init.model)
        assertEquals(listOf("Bash", "Read"), init.tools)
    }

    @Test
    fun `assistant message expands into per-block events`() {
        val events = parseClaudeCodeEvents(
            """{"type":"assistant","message":{"content":[
               {"type":"thinking","thinking":"想一下"},
               {"type":"text","text":"好的"},
               {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}"""
        )
        assertEquals(3, events.size)
        assertEquals("想一下", (events[0] as ClaudeCodeEvent.Thinking).text)
        assertEquals("好的", (events[1] as ClaudeCodeEvent.AssistantText).text)
        val tool = events[2] as ClaudeCodeEvent.ToolUse
        assertEquals("t1", tool.id)
        assertEquals("Bash", tool.name)
    }

    @Test
    fun `tool result reads both string and block-array content`() {
        val asString = parseClaudeCodeEvents(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""
        ).single() as ClaudeCodeEvent.ToolResult
        assertEquals("ok", asString.content)
        assertFalse(asString.isError)

        val asBlocks = parseClaudeCodeEvents(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2",
               "is_error":true,"content":[{"type":"text","text":"line1"},{"type":"text","text":"line2"}]}]}}"""
        ).single() as ClaudeCodeEvent.ToolResult
        assertEquals("line1\nline2", asBlocks.content)
        assertTrue(asBlocks.isError)
    }

    /** can_use_tool 请求里的辅助字段以前被整包丢掉，弹窗只能显示原始 JSON */
    @Test
    fun `permission request keeps the human readable fields`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"req_5","request":{
               "subtype":"can_use_tool","tool_name":"Bash","input":{"command":"rm -rf /tmp/x"},
               "description":"Run rm -rf /tmp/x","decision_reason":"rule","blocked_path":"/etc",
               "tool_use_id":"tu_1"}}"""
        ).single() as ClaudeCodeEvent.PermissionRequest

        assertEquals("req_5", event.requestId)
        assertEquals("Bash", event.toolName)
        assertEquals("Run rm -rf /tmp/x", event.description)
        assertEquals("rule", event.decisionReason)
        assertEquals("/etc", event.blockedPath)
        assertEquals("tu_1", event.toolUseId)
    }

    /** 不认识的 control_request 必须冒出来，否则 CLI 会一直等应答直到超时 */
    @Test
    fun `unknown control request surfaces so it can be answered`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"req_6","request":{"subtype":"request_user_dialog"}}"""
        ).single() as ClaudeCodeEvent.UnsupportedControlRequest
        assertEquals("req_6", event.requestId)
        assertEquals("request_user_dialog", event.subtype)
    }

    @Test
    fun `control response error surfaces`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"error","request_id":"req_7","error":"boom"}}"""
        ).single() as ClaudeCodeEvent.ControlError
        assertEquals("boom", event.error)

        // success 现在会认领成 ControlOk，交给发起方按 request_id 取回结果
        // （list_models / get_plan 这类需要读返回值）
        val ok = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r","response":{}}}"""
        ).single() as ClaudeCodeEvent.ControlOk
        assertEquals("r", ok.requestId)
    }

    /**
     * result 有两个变体：success 变体**没有** is_error 字段；
     * 错误变体 subtype 取 error_during_execution / error_max_turns / ...
     */
    @Test
    fun `result success is not treated as an error`() {
        val ok = parseClaudeCodeEvents(
            """{"type":"result","subtype":"success","duration_ms":12,"num_turns":2,
               "total_cost_usd":0.0123,"session_id":"s1","result":"done"}"""
        ).single() as ClaudeCodeEvent.Result
        assertFalse(ok.isError)
        assertEquals(12L, ok.durationMs)
        assertEquals(2, ok.numTurns)
        assertEquals(0.0123, ok.totalCostUsd!!, 1e-9)
    }

    @Test
    fun `result error subtype without is_error is still an error`() {
        val failed = parseClaudeCodeEvents(
            """{"type":"result","subtype":"error_max_turns","duration_ms":5}"""
        ).single() as ClaudeCodeEvent.Result
        assertTrue(failed.isError)
    }

    /**
     * 真机抓到的帧：认证失败时 CLI 回的是 `is_error:true` **配** `subtype:"success"`。
     * 光看 subtype 会把失败当成功，所以 is_error 必须优先于 subtype。
     */
    @Test
    fun `is_error wins over a success subtype`() {
        val frame = """{"type":"result","subtype":"success","is_error":true,"duration_ms":89,
            "num_turns":1,"total_cost_usd":0,"session_id":"s",
            "result":"Not logged in · Please run /login","terminal_reason":"api_error"}"""
        val result = parseClaudeCodeEvents(frame).single() as ClaudeCodeEvent.Result
        assertTrue(result.isError)
        assertEquals("Not logged in · Please run /login", result.resultText)
    }

    /** --include-partial-messages 的增量帧 */
    @Test
    fun `stream events map to partial text and thinking`() {
        assertEquals(
            ClaudeCodeEvent.PartialStart,
            parseClaudeCodeEvents("""{"type":"stream_event","event":{"type":"message_start"}}""").single()
        )

        val text = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"content_block_delta",
               "delta":{"type":"text_delta","text":"ab"}}}"""
        ).single() as ClaudeCodeEvent.PartialText
        assertEquals("ab", text.text)
        assertFalse(text.thinking)

        val thinking = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"content_block_delta",
               "delta":{"type":"thinking_delta","thinking":"cd"}}}"""
        ).single() as ClaudeCodeEvent.PartialText
        assertTrue(thinking.thinking)

        // 工具入参增量交给整块 assistant 消息处理，这里不产生事件
        assertTrue(
            parseClaudeCodeEvents(
                """{"type":"stream_event","event":{"type":"content_block_delta",
                   "delta":{"type":"input_json_delta","partial_json":"{"}}}"""
            ).isEmpty()
        )
    }

    /** 宽容降级：空行 / 非 JSON / 未知类型都不能让会话崩掉 */
    @Test
    fun `malformed lines degrade to no events`() {
        assertTrue(parseClaudeCodeEvents("").isEmpty())
        assertTrue(parseClaudeCodeEvents("   ").isEmpty())
        assertTrue(parseClaudeCodeEvents("not json").isEmpty())
        assertTrue(parseClaudeCodeEvents("{broken").isEmpty())
        assertTrue(parseClaudeCodeEvents("""{"no_type":1}""").isEmpty())
        assertTrue(parseClaudeCodeEvents("""{"type":"prompt_suggestion","suggestion":"x"}""").isEmpty())
    }

    // endregion

    // region 类型不符不能抛异常（会话中断回归）

    /**
     * 真机报错：`会话中断: Element class kotlinx.serialization.json.JsonLiteral is not a JsonArray`。
     *
     * `message.content` 在协议里是 `string | ContentBlock[]`：工具结果是数组，而**纯文本用户
     * 消息、本地斜杠命令的回显**是裸字符串。旧代码用 `.jsonArray` 硬转，一个字符串就抛
     * IllegalArgumentException，异常从 parse 一路冒到 readLoop，把**整条 stdout 读取循环**
     * 打死 —— CLI 还活着还在写，App 却再也不读了，此后所有 control_request 全部超时。
     */
    @Test
    fun `user frame with string content never throws`() {
        assertTrue(
            parseClaudeCodeEvents(
                """{"type":"user","message":{"role":"user","content":"你好"}}"""
            ).isEmpty()
        )
        assertTrue(
            parseClaudeCodeEvents(
                """{"type":"user","message":{"role":"user",
                   "content":"<local-command-stdout>Current model: Opus 5</local-command-stdout>"}}"""
            ).isEmpty()
        )
    }

    /** assistant 的 content 也可能是裸字符串（老版本 CLI / 部分 transcript 行） */
    @Test
    fun `assistant frame with string content becomes text`() {
        val event = parseClaudeCodeEvents(
            """{"type":"assistant","message":{"role":"assistant","content":"直接给一段文本"}}"""
        ).single() as ClaudeCodeEvent.AssistantText
        assertEquals("直接给一段文本", event.text)
    }

    /**
     * 字段形状会随 CLI 版本漂移（本文件对照 v2.1.246 校对，用户实际跑 2.1.247）。
     * 任何一个字段类型变了都只能降级，绝不能抛。
     */
    @Test
    fun `unexpected field types degrade instead of throwing`() {
        // tools 不是数组
        val init = parseClaudeCodeEvents(
            """{"type":"system","subtype":"init","session_id":"s","model":"m","tools":"Bash,Read"}"""
        ).single() as ClaudeCodeEvent.Init
        assertTrue(init.tools.isEmpty())
        assertEquals("s", init.sessionId)

        // tools 是对象数组而不是字符串数组
        val objTools = parseClaudeCodeEvents(
            """{"type":"system","subtype":"init","session_id":"s","tools":[{"name":"Bash"},"Read"]}"""
        ).single() as ClaudeCodeEvent.Init
        assertEquals(listOf("Read"), objTools.tools)

        // result 的数值字段变成字符串
        val result = parseClaudeCodeEvents(
            """{"type":"result","subtype":"success","duration_ms":"12","num_turns":{"n":1}}"""
        ).single() as ClaudeCodeEvent.Result
        assertNull(result.numTurns)

        // control_request 的 input 不是对象
        val permission = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"r","request":{
               "subtype":"can_use_tool","tool_name":"Bash","input":"ls"}}"""
        ).single() as ClaudeCodeEvent.PermissionRequest
        assertTrue(permission.input.isEmpty())

        // 整个 request / response 字段类型不符
        assertTrue(parseClaudeCodeEvents("""{"type":"control_request","request":"nope"}""").isEmpty())
        assertTrue(parseClaudeCodeEvents("""{"type":"control_response","response":42}""").isEmpty())
    }

    /**
     * control_response 的载荷形状随 subtype 变，不一定是对象。
     * 认领不到 request_id 的话发起方会一直等到超时，UI 上就是"切换模型失败：CLI 未应答"。
     */
    @Test
    fun `success control response is claimed even when the payload is not an object`() {
        val ok = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":true}}"""
        ).single() as ClaudeCodeEvent.ControlOk
        assertEquals("r1", ok.requestId)
        assertTrue(ok.payload.isEmpty())

        val noPayload = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r2"}}"""
        ).single() as ClaudeCodeEvent.ControlOk
        assertEquals("r2", noPayload.requestId)
    }

    // endregion

    // region transcript 回放

    /** transcript 里键盘输入的 user 行，content 就是裸字符串 —— 这是最常见的一行 */
    @Test
    fun `transcript user row with string content becomes a user message`() {
        val event = parseTranscriptLine(
            """{"type":"user","uuid":"u1","timestamp":"2026-08-29T00:00:00Z",
               "message":{"role":"user","content":"帮我看下这个 bug"}}"""
        ).single() as ClaudeCodeEvent.UserMessage
        assertEquals("帮我看下这个 bug", event.text)
    }

    /** 数组形态的 user 行同样要能取出文本 */
    @Test
    fun `transcript user row with block array content becomes a user message`() {
        val event = parseTranscriptLine(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}"""
        ).single() as ClaudeCodeEvent.UserMessage
        assertEquals("hi", event.text)
    }

    /**
     * CLI 把本地斜杠命令拆成命令信封 + 命令输出两条 user 行写进 transcript。
     * 它们是内部记账：回放到聊天流里就是一堆裸 XML，还会把会话标题占掉。
     */
    @Test
    fun `transcript drops local command envelopes`() {
        listOf(
            "<command-name>/model</command-name><command-args></command-args>",
            "<local-command-stdout>Current model: Opus 5</local-command-stdout>",
            "<system-reminder>internal</system-reminder>",
        ).forEach { body ->
            assertTrue(
                "本地命令信封不该回放成用户消息: $body",
                parseTranscriptLine(
                    """{"type":"user","message":{"role":"user","content":"$body"}}"""
                ).isEmpty()
            )
        }
    }

    /** 子 agent 线程和 meta 行不进主聊天流 */
    @Test
    fun `transcript skips sidechain and meta rows`() {
        assertTrue(
            parseTranscriptLine(
                """{"type":"user","isSidechain":true,"message":{"role":"user","content":"x"}}"""
            ).isEmpty()
        )
        assertTrue(
            parseTranscriptLine(
                """{"type":"user","isMeta":true,"message":{"role":"user","content":"x"}}"""
            ).isEmpty()
        )
    }

    /** transcript 的 tool_result 行仍然走工具结果路径，不能被当成用户文本 */
    @Test
    fun `transcript tool result rows still expand to tool results`() {
        val event = parseTranscriptLine(
            """{"type":"user","message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""
        ).single() as ClaudeCodeEvent.ToolResult
        assertEquals("t1", event.toolUseId)
    }

    // endregion

    // region 权限建议（"始终允许"）

    /**
     * `can_use_tool` 请求里 CLI 附带现成的 PermissionUpdate 对象。不解析它就没有"始终允许"，
     * 默认 Manual 模式下每一条 Bash / Edit 都要重新点一遍。
     */
    @Test
    fun `permission suggestions are parsed with readable labels`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"r","request":{
               "subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},
               "permission_suggestions":[
                 {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"ls:*"}],
                  "behavior":"allow","destination":"session"},
                 {"type":"setMode","mode":"acceptEdits","destination":"session"},
                 {"type":"addDirectories","directories":["/workspace/sub"],"destination":"localSettings"}]}}"""
        ).single() as ClaudeCodeEvent.PermissionRequest

        assertEquals(3, event.suggestions.size)
        assertEquals("始终允许 Bash(ls:*)（仅本次会话）", event.suggestions[0].label)
        assertEquals("切换到「Accept edits」模式", event.suggestions[1].label)
        assertEquals("允许访问 /workspace/sub（本机）", event.suggestions[2].label)
    }

    /** 语义上不是"放宽"的更新不该变成批准按钮 */
    @Test
    fun `non actionable permission updates are dropped`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"r","request":{
               "subtype":"can_use_tool","tool_name":"Bash","input":{},
               "permission_suggestions":[
                 {"type":"removeRules","rules":[{"toolName":"Bash"}],"behavior":"allow","destination":"session"},
                 {"type":"addRules","rules":[],"behavior":"allow","destination":"session"},
                 {"type":"weird_future_type"}]}}"""
        ).single() as ClaudeCodeEvent.PermissionRequest
        assertTrue(event.suggestions.isEmpty())
    }

    /** 没有建议时字段缺省，不能崩 */
    @Test
    fun `permission request without suggestions is fine`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"r","request":{
               "subtype":"can_use_tool","tool_name":"Bash","input":{},"permission_suggestions":"nope"}}"""
        ).single() as ClaudeCodeEvent.PermissionRequest
        assertTrue(event.suggestions.isEmpty())
    }

    /**
     * PermissionUpdate 必须**原样**回传。自己重新拼一个很容易被 CLI 的 schema 打回
     * （"malformed updatedPermissions ignored"），那时"始终允许"会静默退化成"允许一次"。
     */
    @Test
    fun `allow response echoes updated permissions verbatim`() {
        val update = json.parseToJsonElement(
            """{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"ls:*"}],
               "behavior":"allow","destination":"session"}"""
        ).jsonObject

        val result = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse("r", allow = true, updatedPermissions = listOf(update))
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject

        assertEquals("allow", result["behavior"]?.jsonPrimitive?.contentOrNull)
        assertEquals(update, result["updatedPermissions"]!!.jsonArray.single())
    }

    /** 不带建议时不能凭空塞一个空数组进去 */
    @Test
    fun `allow response omits updated permissions when there are none`() {
        val result = json.parseToJsonElement(
            encodeClaudeCodePermissionResponse("r", allow = true)
        ).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
        assertNull(result["updatedPermissions"])
    }

    // endregion

    // region system 帧：用户可见的那几条不能再被丢掉

    /**
     * 这些帧的人类可读文案**不在 `text`/`message` 里**，字段名各不相同。
     * 旧实现只看 text/message，于是中转站报错、模型被换掉、斜杠命令输出全都无声无息。
     * 字段名对照 v2.1.246 二进制里的 zod schema。
     */
    @Test
    fun `local command output renders as assistant text`() {
        val event = parseClaudeCodeEvents(
            """{"type":"system","subtype":"local_command_output",
               "content":"Current model: Opus 5 (1M context)","session_id":"s"}"""
        ).single() as ClaudeCodeEvent.AssistantText
        assertEquals("Current model: Opus 5 (1M context)", event.text)
    }

    @Test
    fun `api error surfaces the formatted message`() {
        val event = parseClaudeCodeEvents(
            """{"type":"system","subtype":"api_error","error":{
               "message":"raw","status":401,"formatted":"Authentication failed"}}"""
        ).single() as ClaudeCodeEvent.SystemNote
        assertTrue(event.isError)
        assertEquals("Authentication failed（HTTP 401）", event.text)
    }

    @Test
    fun `model fallback is announced`() {
        val event = parseClaudeCodeEvents(
            """{"type":"system","subtype":"model_fallback","trigger":"model_not_found",
               "original_model":"claude-fable-5","fallback_model":"claude-opus-5"}"""
        ).single() as ClaudeCodeEvent.SystemNote
        assertTrue(event.isError)
        assertTrue(event.text.contains("claude-fable-5"))
        assertTrue(event.text.contains("claude-opus-5"))
    }

    /** level=info 是"只在 transcript 模式显示"，不该刷给用户；warning 走红字 */
    @Test
    fun `informational respects its render level`() {
        assertTrue(
            parseClaudeCodeEvents(
                """{"type":"system","subtype":"informational","content":"x","level":"info"}"""
            ).isEmpty()
        )
        val warning = parseClaudeCodeEvents(
            """{"type":"system","subtype":"informational","content":"磁盘快满了","level":"warning"}"""
        ).single() as ClaudeCodeEvent.SystemNote
        assertTrue(warning.isError)
        assertEquals("磁盘快满了", warning.text)
    }

    /**
     * skip_transcript 只压掉**聊天流**那条，不影响任务表 ——
     * 后台子任务的状态该更新还得更新，否则状态条上它会一直停在"运行中"。
     */
    @Test
    fun `task notification honours skip_transcript`() {
        val skipped = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_notification","task_id":"t","status":"completed",
               "output_file":"o","summary":"done","skip_transcript":true}"""
        )
        assertTrue(skipped.none { it is ClaudeCodeEvent.SystemNote })
        assertEquals("completed", (skipped.single() as ClaudeCodeEvent.TaskEvent).status)

        val shown = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_notification","task_id":"t","status":"failed",
               "output_file":"o","summary":"炸了"}"""
        ).filterIsInstance<ClaudeCodeEvent.SystemNote>().single()
        assertTrue(shown.isError)
        assertTrue(shown.text.contains("炸了"))
    }

    /** 内部计量绝不能进聊天流 —— 一轮能刷几十条 */
    @Test
    fun `internal bookkeeping frames stay out of the transcript`() {
        listOf(
            """{"type":"system","subtype":"thinking_tokens","tokens":123}""",
            """{"type":"system","subtype":"hook_progress","content":"x"}""",
            """{"type":"system","subtype":"session_state_changed","content":"x"}""",
            """{"type":"system","subtype":"never_heard_of_it","foo":"bar"}""",
        ).forEach {
            assertTrue("这条不该进聊天流: $it", parseClaudeCodeEvents(it).isEmpty())
        }
    }

    // endregion

    // region 运行状态：电脑端看得到、手机上曾经全丢的那批

    /**
     * `system/status` 的运行阶段就是终端里那句"等待中/工作中"。
     * 它频率高，但正确去处是**原地替换的状态条**，不是丢掉，也不是往聊天流里追加。
     */
    @Test
    fun `status frame becomes a replaceable status event`() {
        listOf("idle", "busy", "working", "waiting", "thinking", "responding", "compacting")
            .forEach { phase ->
                val e = parseClaudeCodeEvents(
                    """{"type":"system","subtype":"status","status":"$phase","session_id":"s"}"""
                ).single() as ClaudeCodeEvent.Status
                assertEquals(phase, e.phase)
            }
    }

    /**
     * `task_summary.detail` 是 CLI 的实时短语（"the same live phrase"）。
     * detail 为 null 是"回到空闲"的清除信号，必须原样传下去而不是当成"没有这一帧"。
     */
    @Test
    fun `task summary carries the live phrase and its null clear`() {
        val live = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_summary","detail":"正在读取项目结构"}"""
        ).single() as ClaudeCodeEvent.Status
        assertEquals("正在读取项目结构", live.detail)
        assertNull(live.phase)

        val cleared = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_summary","detail":null}"""
        ).single() as ClaudeCodeEvent.Status
        assertNull(cleared.detail)
    }

    /** 重试提示：不显示的话用户只看到界面卡住不动 */
    @Test
    fun `api retry is surfaced with attempt and delay`() {
        val e = parseClaudeCodeEvents(
            """{"type":"system","subtype":"api_retry","attempt":2,"max_retries":5,
               "retry_delay_ms":4000,"error_status":529,"error":"overloaded"}"""
        ).single() as ClaudeCodeEvent.ApiRetry
        assertEquals(2, e.attempt)
        assertEquals(5, e.maxRetries)
        assertEquals(4000, e.retryDelayMs)
        assertEquals(529, e.errorStatus)
        assertEquals("overloaded", e.message)

        // 连接类错误 error_status 为 null（超时等），不能因此崩掉
        val conn = parseClaudeCodeEvents(
            """{"type":"system","subtype":"api_retry","attempt":1,"max_retries":3,
               "retry_delay_ms":1000,"error_status":null,"error":"unknown"}"""
        ).single() as ClaudeCodeEvent.ApiRetry
        assertNull(conn.errorStatus)
        assertEquals("unknown", conn.message)
    }

    /**
     * `error` 是枚举不是自由文本，必须翻译成人能看懂的话。
     * 最关键的一条：`unknown` + `error_status == null` 是**连接层断掉**，
     * 和"服务器返回了错误"是两个完全不同的排查方向，不能都糊成"未知错误"。
     */
    @Test
    fun `retry reason distinguishes transport failure from server errors`() {
        fun reason(error: String?, status: Int?) = ClaudeCodeManager.retryReason(
            ClaudeCodeEvent.ApiRetry(
                attempt = 1, maxRetries = 10, retryDelayMs = 1000,
                errorStatus = status, message = error,
            )
        )

        // 没有 HTTP 状态码 = 根本没到应用层（文案本身会提到"未收到 HTTP 响应"，
        // 所以这里要断言的是没有 "· HTTP <code>" 那个后缀，而不是没有 HTTP 三个字母）
        val transport = reason("unknown", null)
        assertTrue("应指出是连接问题: $transport", transport.contains("连接中断"))
        assertFalse("不该拼状态码后缀: $transport", transport.contains("· HTTP"))
        assertEquals(transport, reason(null, null))

        // 有状态码就是服务器真的答复了
        assertTrue(reason("overloaded", 529).contains("过载"))
        assertTrue(reason("overloaded", 529).contains("HTTP 529"))
        assertTrue(reason("authentication_failed", 401).contains("token"))
        assertTrue(reason("rate_limit", 429).contains("限流"))
        assertTrue(reason("model_not_found", 404).contains("模型"))

        // 未来新增的枚举值原样透出, 不能吞掉
        assertTrue(reason("some_future_bucket", 500).contains("some_future_bucket"))
    }

    // endregion

    // region 子 agent 任务

    @Test
    fun `task started becomes a running task`() {
        val e = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_started","task_id":"t1",
               "description":"搜索调用点","subagent_type":"Explore","is_backgrounded":true}"""
        ).single() as ClaudeCodeEvent.TaskEvent
        assertEquals("t1", e.taskId)
        assertEquals("running", e.status)
        assertEquals("Explore", e.subagentType)
        assertEquals(true, e.backgrounded)
    }

    @Test
    fun `task progress carries usage and last tool`() {
        val e = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_progress","task_id":"t1","description":"搜索调用点",
               "subagent_type":"Explore","usage":{"total_tokens":15000,"tool_uses":7,"duration_ms":42000},
               "last_tool_name":"Grep","summary":"已扫描 40 个文件"}"""
        ).single() as ClaudeCodeEvent.TaskEvent
        assertEquals(15000, e.totalTokens)
        assertEquals(7, e.toolUses)
        assertEquals(42000L, e.durationMs)
        assertEquals("Grep", e.lastToolName)
        assertEquals("已扫描 40 个文件", e.summary)
        // progress 帧不改状态，避免把已经 completed 的任务倒回 running
        assertNull(e.status)
    }

    @Test
    fun `task updated applies the status patch`() {
        val e = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_updated","task_id":"t1",
               "patch":{"status":"failed","error":"子 agent 超时"}}"""
        ).single() as ClaudeCodeEvent.TaskEvent
        assertEquals("failed", e.status)
        assertEquals("子 agent 超时", e.error)
    }

    /** 终局通知：既要更新任务表，也要在聊天流里留一条 */
    @Test
    fun `task notification updates the task and leaves a note`() {
        val events = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_notification","task_id":"t1","status":"completed",
               "output_file":"o","summary":"找到 3 处","usage":{"total_tokens":9000,"tool_uses":4,"duration_ms":100}}"""
        )
        assertEquals(2, events.size)
        val task = events[0] as ClaudeCodeEvent.TaskEvent
        assertEquals("completed", task.status)
        assertEquals(9000, task.totalTokens)
        val note = events[1] as ClaudeCodeEvent.SystemNote
        assertTrue(note.text.contains("找到 3 处"))

        // skip_transcript 只压掉聊天流那条，任务表仍要更新
        val skipped = parseClaudeCodeEvents(
            """{"type":"system","subtype":"task_notification","task_id":"t2","status":"failed",
               "output_file":"o","summary":"炸了","skip_transcript":true}"""
        )
        assertEquals(1, skipped.size)
        assertEquals("failed", (skipped.single() as ClaudeCodeEvent.TaskEvent).status)
    }

    /** task_id 缺失的畸形帧不能崩，也不该产生半个任务 */
    @Test
    fun `task frames without an id are dropped`() {
        listOf("task_started", "task_progress", "task_updated").forEach { sub ->
            assertTrue(
                parseClaudeCodeEvents("""{"type":"system","subtype":"$sub","description":"x"}""")
                    .isEmpty()
            )
        }
    }

    // endregion

    // region 提示缓存 TTL

    /**
     * CLI 的 zod 是 `m(["5m","1h"]).optional().catch(void 0)` —— 非法值被静默吞掉，
     * 传错了 App 侧毫无感知，只会以为设了其实没设。
     */
    @Test
    fun `prompt cache ttl must stay inside the cli whitelist`() {
        assertTrue(
            ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL in ClaudeCodeManager.PROMPT_CACHE_TTLS
        )
        assertEquals(listOf("5m", "1h"), ClaudeCodeManager.PROMPT_CACHE_TTLS)
        // 默认必须是 1h：走 API token 时 CLI 自己的默认只有 5 分钟
        assertEquals("1h", ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL)
        assertEquals("1h", ClaudeCodeManager.SessionOptions().promptCacheTtl)
    }

    // endregion

    /**
     * Node/npm/proot 会往 stderr 打一堆常规警告。全量当错误显示的话，
     * 一个完全正常的会话界面上也会永远挂着一条红字。
     */
    @Test
    fun `benign stderr noise is not reported as an error`() {
        listOf(
            "(node:1234) ExperimentalWarning: CommonJS module support is experimental",
            "(node:1234) [DEP0040] DeprecationWarning: punycode is deprecated",
            "npm warn deprecated inflight@1.0.6",
            "Use `node --trace-deprecation ...` to show where the warning was created",
        ).forEach {
            assertFalse("这行不该被当错误: $it", ClaudeCodeManager.looksLikeError(it))
        }
    }

    @Test
    fun `real stderr failures are reported`() {
        listOf(
            "Error: Cannot find module '@anthropic-ai/claude-code'",
            "node: ENOENT: no such file or directory",
            "AuthenticationError: invalid x-api-key",
        ).forEach {
            assertTrue("这行应该被当错误: $it", ClaudeCodeManager.looksLikeError(it))
        }
    }

    // endregion

    // region 启动参数转义

    @Test
    fun `shell quoting survives spaces and single quotes`() {
        assertEquals("'claude-opus-5'", ClaudeCodeManager.shellQuote("claude-opus-5"))
        assertEquals("'a b'", ClaudeCodeManager.shellQuote("a b"))
        assertEquals("'it'\\''s'", ClaudeCodeManager.shellQuote("it's"))
        assertEquals("''", ClaudeCodeManager.shellQuote(""))
    }

    // endregion
}
