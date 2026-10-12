package dev.min.agent.otel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTel 第四段：响应 id、客户端请求 id、traceresponse span link、api_timeout、工作流归属、工具错误类别、num_cancelled、
 * 详细追踪的内容属性（默认关、受内容开关管）、模型请求的出站 traceparent。照原文 monitoring-usage 的 Traces 一节。
 */
class OtelRequestSpanTest {
    private val sid = "s1"
    private val model = ModelRef("anthropic", "claude-test")
    private val usage = Usage(inputTokens = 10, outputTokens = 5)
    private var seq = 0L
    private val secret = "SECRET-CONTENT-DO-NOT-LEAK"
    private val traceResponse = "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"

    private fun rec(atMs: Long, event: SessionEvent) = EventRecord(++seq, atMs, event)
    private fun turn(n: Int = 1) = TurnId("t$n")
    private fun turnStart(at: Long) = rec(at, TurnStarted(turn(), TurnTrigger.User))
    private fun turnEnd(at: Long) = rec(at, TurnEnded(turn(), TurnOutcome.Completed, usage, durationMs = 100))
    private fun prompt(at: Long, text: String) = rec(at, InputReceived(ClientMsgId("c$at"), listOf(TextPart(text)), Delivery.Queue, InputOrigin.User))
    private fun reqStart(at: Long, attempt: Int = 1, client: String? = null, r: String = "r1") =
        rec(at, ModelRequestStarted(turn(), RequestId(r), attempt, model, "fp", clientRequestId = client))
    private fun charged(at: Long) = rec(at, ModelCharged(model, usage, amount = 0.01, currency = "USD"))
    private fun reply(at: Long, text: String = "ok", responseId: String? = null, trace: String? = null, r: String = "r1") =
        rec(at, AssistantMessageCompleted(turn(), RequestId(r), MessageId("m$at"), listOf(TextPart(text)), usage, StopReason.EndTurn, responseId = responseId, traceResponse = trace))
    private fun failed(at: Long, attempt: Int, retry: Boolean, timedOut: Boolean = false, kind: ModelFailureKind = ModelFailureKind.Network, responseId: String? = null, trace: String? = null) =
        rec(at, ModelRequestFailed(turn(), RequestId("r1"), attempt, kind, "出错了", willRetry = retry, timedOut = timedOut, responseId = responseId, traceResponse = trace))
    private fun planned(at: Long, call: String, tool: String, input: Map<String, String> = emptyMap()) =
        rec(at, ToolPlanned(turn(), CallId(call), tool, JsonObject(input.mapValues { JsonPrimitive(it.value) }), ResumePolicy.Unknown))
    private fun effect(at: Long, call: String) = rec(at, ToolEffectPending(turn(), CallId(call)))
    private fun outcome(at: Long, call: String, text: String = "out", error: Boolean = false, kind: String? = null) =
        rec(at, ToolOutcome(turn(), CallId(call), ToolResult(listOf(TextPart(text)), isError = error, errorKind = kind)))

    private fun spansOf(events: List<EventRecord>, options: OtelOptions = OtelOptions(sessionId = sid), link: SpanLink? = null): List<OtelSpan> {
        val mapper = OtelSpanMapper(options, link)
        return events.flatMap { mapper.feed(it) }
    }

    private fun OtelSpan.text(k: String) = (attributes[k] as? OtelValue.Text)?.value
    private fun OtelSpan.whole(k: String) = (attributes[k] as? OtelValue.Whole)?.value
    private fun Map<String, OtelValue>.text(k: String) = (this[k] as? OtelValue.Text)?.value

    private fun llmOf(vararg events: EventRecord, options: OtelOptions = OtelOptions(sessionId = sid), link: SpanLink? = null): OtelSpan =
        spansOf(listOf(turnStart(1000)) + events.toList() + turnEnd(9000), options, link).single { it.name == SpanNames.LLM_REQUEST }

    private fun toolsOf(events: List<EventRecord>, options: OtelOptions = OtelOptions(sessionId = sid)): List<OtelSpan> =
        spansOf(listOf(turnStart(1000)) + events + turnEnd(9000), options)

    // ---- gen_ai.response.id / client_request_id ----

    @Test
    fun `llm_request 带供应商响应 id 与最后一次尝试的客户端请求 id`() {
        seq = 0
        val llm = llmOf(reqStart(1010, client = "creq_1"), charged(1500), reply(1510, responseId = "req_011CT"))
        assertEquals("req_011CT", llm.text("gen_ai.response.id"))
        assertEquals("creq_1", llm.text("client_request_id"))
    }

    @Test
    fun `没带客户端请求 id 的供应商不写这个属性，也不编造响应 id`() {
        seq = 0
        val llm = llmOf(reqStart(1010), charged(1500), reply(1510))
        assertFalse(llm.attributes.containsKey("client_request_id"))
        assertFalse(llm.attributes.containsKey("gen_ai.response.id"))
    }

    @Test
    fun `重试：每次重试的 gen_ai_request_attempt 事件带自己的客户端请求 id，span 上是最后一次的`() {
        seq = 0
        val llm = llmOf(
            reqStart(1010, 1, client = "creq_1"), failed(1100, 1, retry = true),
            reqStart(1200, 2, client = "creq_2"), failed(1300, 2, retry = true),
            reqStart(1400, 3, client = "creq_3"), charged(1500), reply(1510, responseId = "req_9"),
        )
        val attempts = llm.events.filter { it.name == SpanEventNames.REQUEST_ATTEMPT }
        assertEquals(listOf(2L, 3L), attempts.map { (it.attributes["attempt"] as OtelValue.Whole).value })
        assertEquals(listOf("creq_2", "creq_3"), attempts.map { it.attributes.text("client_request_id") })
        assertEquals("creq_3", llm.text("client_request_id"))
        assertEquals(3L, llm.whole("attempt"))
    }

    @Test
    fun `最终失败的 span 带失败响应的 id 与最后一次尝试的客户端请求 id`() {
        seq = 0
        val llm = llmOf(reqStart(1010, 1, client = "creq_1"), failed(1100, 1, retry = false, kind = ModelFailureKind.Server, responseId = "req_err"))
        assertEquals("req_err", llm.text("gen_ai.response.id"))
        assertEquals("creq_1", llm.text("client_request_id"))
        assertEquals("success=false", "success=" + (llm.attributes["success"] as OtelValue.Flag).value)
    }

    // ---- error_class: api_timeout ----

    @Test
    fun `超时的失败是 api_timeout，普通网络错误仍是 network_error`() {
        seq = 0
        val timeout = llmOf(reqStart(1010, 1), failed(1100, 1, retry = false, timedOut = true))
        assertEquals("api_timeout", timeout.text("error_class"))
        seq = 0
        val network = llmOf(reqStart(1010, 1), failed(1100, 1, retry = false))
        assertEquals("network_error", network.text("error_class"))
        seq = 0
        val overloaded = llmOf(reqStart(1010, 1), failed(1100, 1, retry = false, kind = ModelFailureKind.Overloaded))
        assertEquals("server_overload", overloaded.text("error_class"))
    }

    // ---- traceresponse span link ----

    @Test
    fun `响应头 traceresponse 记成 span link`() {
        seq = 0
        val llm = llmOf(reqStart(1010, client = "c"), charged(1500), reply(1510, trace = traceResponse))
        val link = llm.links.single()
        assertEquals("0af7651916cd43dd8448eb211c80319c", link.traceId)
        assertEquals("00f067aa0ba902b7", link.spanId)
    }

    @Test
    fun `失败的请求也带 traceresponse 的 link`() {
        seq = 0
        val llm = llmOf(reqStart(1010), failed(1100, 1, retry = false, trace = traceResponse))
        assertEquals("00f067aa0ba902b7", llm.links.single().spanId)
    }

    @Test
    fun `不合规的 traceresponse 不出 link，也不抛`() {
        for (bad in listOf("garbage", "00-short-00f067aa0ba902b7-01", "01-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01")) {
            seq = 0
            assertTrue("「$bad」不该出 link", llmOf(reqStart(1010), charged(1500), reply(1510, trace = bad)).links.isEmpty())
        }
    }

    @Test
    fun `没有 traceresponse 就没有 link`() {
        seq = 0
        assertTrue(llmOf(reqStart(1010), charged(1500), reply(1510)).links.isEmpty())
    }

    @Test
    fun `span link 与新属性出现在 OTLP 的 traces 请求体里`() = runBlocking {
        seq = 0
        val llm = llmOf(reqStart(1010, client = "creq_1"), charged(1500), reply(1510, responseId = "req_011CT", trace = traceResponse))
        val config = OtelConfig.fromEnv {
            mapOf(
                "CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1", "OTEL_TRACES_EXPORTER" to "otlp",
                "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json", "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://collector.example.com",
            )[it]
        }
        val transport = ScriptedTransport(OK)
        OtelExporter(config, transport).export(listOf(llm))
        val body = Json.parseToJsonElement(transport.requests.single().body!!.toString(Charsets.UTF_8)).jsonObject
        val span = body["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        val link = span["links"]!!.jsonArray.single().jsonObject
        assertEquals("0af7651916cd43dd8448eb211c80319c", link["traceId"]!!.jsonPrimitive.content)
        assertEquals("00f067aa0ba902b7", link["spanId"]!!.jsonPrimitive.content)
        val attrs = span["attributes"]!!.jsonArray.associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject }
        assertEquals("req_011CT", attrs["gen_ai.response.id"]!!["stringValue"]!!.jsonPrimitive.content)
        assertEquals("creq_1", attrs["client_request_id"]!!["stringValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `本机 JSONL 也带 links`() {
        seq = 0
        val llm = llmOf(reqStart(1010), charged(1500), reply(1510, trace = traceResponse))
        val line = Json.parseToJsonElement(OtlpJson.fileLine(llm)).jsonObject
        assertEquals("00f067aa0ba902b7", line["links"]!!.jsonArray.single().jsonObject["span_id"]!!.jsonPrimitive.content)
    }

    // ---- 工作流归属 ----

    private val workflowChild = OtelLineage(
        parent = "p", root = "p", parentCall = "c1", agentType = "general-purpose", depth = 1, teamMember = false,
        workflowRunId = "workflow_7", workflowName = "deploy",
    )
    private val link = SpanLink("a".repeat(32), "b".repeat(16))

    @Test
    fun `工作流里的子 agent：llm_request 与 tool span 带 workflow_run_id，名字默认换成 custom`() {
        seq = 0
        val options = OtelOptions(sessionId = "kid", lineage = workflowChild)
        val spans = spansOf(
            listOf(turnStart(1000), reqStart(1010), charged(1500), reply(1510), planned(1600, "t1", "Read"), outcome(1700, "t1"), turnEnd(9000)),
            options, link,
        )
        for (name in listOf(SpanNames.LLM_REQUEST, SpanNames.TOOL)) {
            val s = spans.single { it.name == name }
            assertEquals("workflow_7", s.text("workflow.run_id"))
            assertEquals("用户自写的名字不外露", "custom", s.text("workflow.name"))
        }
        assertNull(spans.single { it.name == SpanNames.EXECUTION }.text("workflow.run_id"))
    }

    @Test
    fun `OTEL_LOG_TOOL_DETAILS 开着才给真实的工作流名`() {
        seq = 0
        val options = OtelOptions(sessionId = "kid", lineage = workflowChild, logToolDetails = true)
        val llm = spansOf(listOf(turnStart(1000), reqStart(1010), charged(1500), reply(1510), turnEnd(9000)), options, link).single { it.name == SpanNames.LLM_REQUEST }
        assertEquals("deploy", llm.text("workflow.name"))
    }

    @Test
    fun `不是工作流的子 agent 与根会话没有 workflow 属性`() {
        seq = 0
        val plain = workflowChild.copy(workflowRunId = null, workflowName = null)
        val child = spansOf(listOf(turnStart(1000), reqStart(1010), charged(1500), reply(1510), turnEnd(9000)), OtelOptions(sessionId = "kid", lineage = plain), link)
            .single { it.name == SpanNames.LLM_REQUEST }
        assertFalse(child.attributes.containsKey("workflow.run_id"))
        assertFalse(child.attributes.containsKey("workflow.name"))
        seq = 0
        assertFalse(llmOf(reqStart(1010), charged(1500), reply(1510)).attributes.containsKey("workflow.run_id"))
    }

    @Test
    fun `事件上也带 workflow 属性（API 与工具事件），指标上不带`() {
        seq = 0
        val mapper = OtelMapper(OtelOptions(sessionId = "kid", lineage = workflowChild))
        val items = listOf(turnStart(1000), reqStart(1010), charged(1500), planned(1600, "t1", "Read"), outcome(1700, "t1")).flatMap { mapper.feed(it) }
        val apiRequest = items.filterIsInstance<OtelLog>().single { it.name == OtelNames.API_REQUEST }
        assertEquals("workflow_7", apiRequest.attributes.text("workflow.run_id"))
        val toolResult = items.filterIsInstance<OtelLog>().single { it.name == OtelNames.TOOL_RESULT }
        assertEquals("workflow_7", toolResult.attributes.text("workflow.run_id"))
        assertTrue("指标不带运行 id（基数）", items.filterIsInstance<OtelMetric>().none { it.attributes.containsKey("workflow.run_id") })
    }

    @Test
    fun `api_request 与 api_error 事件带客户端请求 id`() {
        seq = 0
        val mapper = OtelMapper(OtelOptions(sessionId = sid))
        val ok = listOf(reqStart(1010, client = "creq_1"), charged(1500)).flatMap { mapper.feed(it) }
        assertEquals("creq_1", ok.filterIsInstance<OtelLog>().single { it.name == OtelNames.API_REQUEST }.attributes.text("client_request_id"))
        val bad = listOf(reqStart(2000, client = "creq_2"), failed(2100, 1, retry = false)).flatMap { mapper.feed(it) }
        assertEquals("creq_2", bad.filterIsInstance<OtelLog>().single { it.name == OtelNames.API_ERROR }.attributes.text("client_request_id"))
    }

    // ---- 工具错误类别 ----

    private fun execution(vararg events: EventRecord, options: OtelOptions = OtelOptions(sessionId = sid)) =
        toolsOf(events.toList(), options).single { it.name == SpanNames.EXECUTION }

    @Test
    fun `工具给出了错误类别就用它：Error_ENOENT`() {
        seq = 0
        val exec = execution(planned(1100, "t1", "Read"), effect(1200, "t1"), outcome(1300, "t1", "不存在", error = true, kind = "Error:ENOENT"))
        assertEquals("Error:ENOENT", exec.text("error"))
        assertEquals("Error_ENOENT", exec.text("error_class"))
        assertNotNull(exec.error)
    }

    @Test
    fun `没有类别的失败按工具名补：Bash 是 ShellError，其余 ToolError`() {
        seq = 0
        val bash = execution(planned(1100, "t1", "Bash"), effect(1200, "t1"), outcome(1300, "t1", "exit 1", error = true))
        assertEquals("ShellError", bash.text("error"))
        seq = 0
        val other = execution(planned(1100, "t1", "Write"), effect(1200, "t1"), outcome(1300, "t1", "no", error = true))
        assertEquals("ToolError", other.text("error"))
        assertEquals("ToolError", other.text("error_class"))
    }

    @Test
    fun `OTEL_LOG_TOOL_DETAILS 开着时 error 是完整消息，error_class 仍是类别`() {
        seq = 0
        val exec = execution(
            planned(1100, "t1", "Read"), effect(1200, "t1"), outcome(1300, "t1", "文件不存在：/x", error = true, kind = "Error:ENOENT"),
            options = OtelOptions(sessionId = sid, logToolDetails = true),
        )
        assertEquals("文件不存在：/x", exec.text("error"))
        assertEquals("Error_ENOENT", exec.text("error_class"))
    }

    @Test
    fun `类别里字母数字下划线之外的字符在 error_class 里换成下划线`() {
        seq = 0
        val exec = execution(planned(1100, "t1", "Read"), effect(1200, "t1"), outcome(1300, "t1", "x", error = true, kind = "Weird-Kind.1:x y"))
        assertEquals("Weird_Kind_1_x_y", exec.text("error_class"))
    }

    // ---- hook num_cancelled ----

    private fun hookSpan(ran: HooksRan): OtelSpan {
        seq = 0
        return spansOf(
            listOf(turnStart(1000), rec(1500, ran), turnEnd(9000)),
            OtelOptions(sessionId = sid, detailedTracing = true),
        ).single { it.name == SpanNames.HOOK }
    }

    @Test
    fun `整批被取消的 hook：num_cancelled 是处理器数，不算失败`() {
        val s = hookSpan(HooksRan("UserPromptSubmit", 2, "cancelled", 40, turn(), problems = listOf("hook 被取消（回合中断）"), cancelled = 2))
        assertEquals(2L, s.whole("num_cancelled"))
        assertNull("取消不是失败：状态不是 ERROR", s.error)
    }

    @Test
    fun `正常结束的批 num_cancelled 为 0；没有结局计数的旧记录不写`() {
        assertEquals(0L, hookSpan(HooksRan("PreToolUse", 1, "neutral", 5, turn(), succeeded = 1, blocking = 0, nonBlockingErrors = 0)).whole("num_cancelled"))
        assertFalse(hookSpan(HooksRan("PreToolUse", 1, "neutral", 5, turn())).attributes.containsKey("num_cancelled"))
    }

    // ---- 详细追踪的内容属性 ----

    private val detailedOn = OtelOptions(
        sessionId = sid, detailedTracing = true, logUserPrompts = true, logToolContent = true, logToolDetails = true,
    )

    private fun conversation(): List<EventRecord> {
        seq = 0
        return listOf(
            prompt(900, "问：$secret-prompt"), turnStart(1000),
            reqStart(1010, client = "creq_1"), charged(1100), reply(1110, text = "答：$secret-model"),
            planned(1200, "t1", "Read", mapOf("file_path" to "/w/$secret-path")), effect(1210, "t1"), outcome(1300, "t1", "内容：$secret-tool"),
            reqStart(1400, r = "r2"), charged(1500), reply(1510, text = "完", r = "r2"),
            turnEnd(9000),
        )
    }

    private fun allText(spans: List<OtelSpan>): String = spans.joinToString("\n") { span ->
        span.attributes.toString() + span.events.joinToString { it.attributes.toString() }
    }

    @Test
    fun `详细追踪全开：interaction 与 llm_request 与 tool 带各自的内容属性`() {
        val spans = spansOf(conversation(), detailedOn)
        val interaction = spans.single { it.name == SpanNames.INTERACTION }
        assertEquals("问：$secret-prompt", interaction.text("new_context"))
        val llms = spans.filter { it.name == SpanNames.LLM_REQUEST }.sortedBy { it.startMs }
        // 第一次请求：新进来的是用户提示词；第二次：上一次之后的工具结果
        assertTrue(llms[0].text("new_context")!!.contains("$secret-prompt"))
        assertEquals("答：$secret-model", llms[0].text("response.model_output"))
        assertTrue(llms[1].text("new_context")!!.contains("$secret-tool"))
        assertFalse("第二次请求的 new_context 不重复第一次已发过的提示词", llms[1].text("new_context")!!.contains("$secret-prompt"))
        val tool = spans.single { it.name == SpanNames.TOOL }
        assertEquals("内容：$secret-tool", tool.text("new_context"))
        assertTrue(tool.text("tool_input")!!.contains("$secret-path"))
    }

    @Test
    fun `没有详细追踪时一律没有这些属性，即使内容开关全开`() {
        val spans = spansOf(conversation(), detailedOn.copy(detailedTracing = false))
        for (s in spans) for (k in listOf("new_context", "response.model_output", "tool_input", "system_reminders", "system_prompt_preview", "user_system_prompt")) {
            assertFalse("${s.name} 不该有 $k", s.attributes.containsKey(k))
        }
    }

    @Test
    fun `详细追踪开着，各属性仍受自己的内容开关管`() {
        // 只开详细追踪，内容开关全关：连正文的影子都没有
        val none = spansOf(conversation(), OtelOptions(sessionId = sid, detailedTracing = true))
        assertFalse(allText(none).contains(secret))
        // 只开 USER_PROMPTS：提示词与模型输出有，工具内容与入参没有（new_context 在 llm 里含工具结果——原文：新用户消息与工具结果同属 llm 的 new_context）
        val prompts = spansOf(conversation(), OtelOptions(sessionId = sid, detailedTracing = true, logUserPrompts = true))
        assertFalse(prompts.single { it.name == SpanNames.TOOL }.attributes.containsKey("new_context"))
        assertFalse(prompts.single { it.name == SpanNames.TOOL }.attributes.containsKey("tool_input"))
        // 只开 TOOL_CONTENT：工具结果有，提示词与模型输出没有，入参没有
        val content = spansOf(conversation(), OtelOptions(sessionId = sid, detailedTracing = true, logToolContent = true))
        assertNotNull(content.single { it.name == SpanNames.TOOL }.attributes["new_context"])
        assertFalse(content.single { it.name == SpanNames.TOOL }.attributes.containsKey("tool_input"))
        assertFalse(content.filter { it.name == SpanNames.LLM_REQUEST }.any { it.attributes.containsKey("new_context") || it.attributes.containsKey("response.model_output") })
        assertFalse(content.single { it.name == SpanNames.INTERACTION }.attributes.containsKey("new_context"))
        assertFalse(allText(content).contains("$secret-prompt"))
        // 只开 TOOL_DETAILS：入参有，工具结果没有
        val details = spansOf(conversation(), OtelOptions(sessionId = sid, detailedTracing = true, logToolDetails = true))
        assertNotNull(details.single { it.name == SpanNames.TOOL }.attributes["tool_input"])
        assertFalse(details.single { it.name == SpanNames.TOOL }.attributes.containsKey("new_context"))
    }

    @Test
    fun `系统提示类属性不做：全开也不出现`() {
        val spans = spansOf(conversation(), detailedOn)
        for (s in spans) for (k in listOf("system_reminders", "system_prompt_preview", "user_system_prompt")) {
            assertFalse(s.attributes.containsKey(k))
        }
    }

    @Test
    fun `new_context 按内容上限截断并带伴随属性，缓冲不无限增长`() {
        seq = 0
        val long = "x".repeat(500)
        val options = detailedOn.copy(contentMaxLength = 100)
        val llm = spansOf(listOf(prompt(900, long), turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(9000)), options)
            .single { it.name == SpanNames.LLM_REQUEST }
        assertTrue(llm.text("new_context")!!.length <= 100)
        assertEquals(OtelValue.Flag(true), llm.attributes["new_context_truncated"])
        assertEquals(500L, llm.whole("new_context_original_length"))
    }

    // ---- 模型请求的出站 traceparent（宿主侧） ----

    private val modelCreated = SessionCreated(GuestPath("/w"), model, PermissionMode.Default)

    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl")
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun provider(cfg: OtelConfig?, vararg logs: dev.min.agent.spi.SessionLog) =
        OtelTraceContext({ cfg }) { id -> logs.firstOrNull { it.header.sessionId.value == id } }

    private val tp = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-01")

    @Test
    fun `出站 traceparent 指向导出的那个 llm_request span`() = runBlocking {
        seq = 0
        // 内核把第一次尝试的 seq 交给宿主；这里用与导出完全相同的日志核对 span id
        val records = listOf(turnStart(1000), reqStart(1010), charged(1500), reply(1510), turnEnd(9000))
        val startSeq = records[1].seq
        val out = provider(config()).modelRequest(SessionId(sid), turn(), RequestId("r1"), startSeq)!!
        val m = tp.matchEntire(out.traceparent)!!
        val exported = spansOf(records).single { it.name == SpanNames.LLM_REQUEST }
        assertEquals(exported.traceId, m.groupValues[1])
        assertEquals(exported.spanId, m.groupValues[2])
        assertFalse(out.allowCustomAnthropicEndpoint)
    }

    @Test
    fun `重试沿用第一次尝试的 seq，回退到别的模型后是另一个 span`() = runBlocking {
        seq = 0
        val records = listOf(
            turnStart(1000), reqStart(1010, 1), failed(1100, 1, retry = true), reqStart(1200, 2), charged(1500), reply(1510), turnEnd(9000),
        )
        val first = records[1].seq
        val out = provider(config()).modelRequest(SessionId(sid), turn(), RequestId("r1"), first)!!
        assertEquals(spansOf(records).single { it.name == SpanNames.LLM_REQUEST }.spanId, tp.matchEntire(out.traceparent)!!.groupValues[2])
        val other = provider(config()).modelRequest(SessionId(sid), turn(), RequestId("r1"), first + 5)!!
        assertTrue("不同的起点 seq 是不同的 span", other.traceparent != out.traceparent)
    }

    @Test
    fun `子 agent 的请求在父会话那次委派所在的 trace 里`() = runBlocking {
        val parent = FakeSessionLog("p")
        parent.add(1, modelCreated)
        parent.add(2, ToolPlanned(TurnId("tp1"), CallId("agent1"), "Agent", JsonObject(emptyMap()), ResumePolicy.Unknown))
        val child = FakeSessionLog("kid", parent = "p")
        child.add(1, modelCreated)
        child.add(2, SubagentOrigin(SessionId("p"), SessionId("p"), CallId("agent1"), "Explore", fork = false, depth = 1, cacheKey = "k"))
        val out = provider(config(), parent, child).modelRequest(SessionId("kid"), TurnId("kt1"), RequestId("r1"), 3)!!
        assertEquals(SpanIds.trace("p", "tp1"), tp.matchEntire(out.traceparent)!!.groupValues[1])
    }

    @Test
    fun `追踪没在产出就不给：遥测关、没开 beta、没有去处`() = runBlocking {
        assertNull(provider(null).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1))
        assertNull(provider(config("CLAUDE_CODE_ENABLE_TELEMETRY" to "")).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1))
        assertNull(provider(config("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "")).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1))
        assertNull(provider(config("MIN_TELEMETRY_FILE" to "")).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1))
    }

    @Test
    fun `显式 CLAUDE_CODE_PROPAGATE_TRACEPARENT 才把自定义端点的同意带给编解码器`() = runBlocking {
        val default = provider(config()).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1)!!
        assertFalse(default.allowCustomAnthropicEndpoint)
        // 自定义 ANTHROPIC_BASE_URL 不影响模型请求（Min 的供应商各有地址）；只有显式开关才算同意
        val proxyEnv = provider(config("ANTHROPIC_BASE_URL" to "https://proxy.example.com")).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1)!!
        assertFalse(proxyEnv.allowCustomAnthropicEndpoint)
        val explicit = provider(config("CLAUDE_CODE_PROPAGATE_TRACEPARENT" to "1")).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1)!!
        assertTrue(explicit.allowCustomAnthropicEndpoint)
    }

    @Test
    fun `入站 TRACEPARENT 的 trace 也用在模型请求上`() = runBlocking {
        val inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val cfg = OtelConfig.fromEnv({
            mapOf(
                "CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl",
                "TRACEPARENT" to inbound,
            )[it]
        }, acceptInbound = true)
        val out = provider(cfg).modelRequest(SessionId(sid), turn(), RequestId("r1"), 1)!!
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", tp.matchEntire(out.traceparent)!!.groupValues[1])
    }
}
