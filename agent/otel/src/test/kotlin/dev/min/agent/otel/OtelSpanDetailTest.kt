package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ComponentHooksRegistered
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTel 第三段：span 的属性补全、重试合并成一个 llm_request 并带 gen_ai.request.attempt 事件、tool.output 事件、
 * claude_code.hook span、入站 TRACEPARENT。纯函数（日志记录 → span），照原文 monitoring-usage 的 Traces 一节。
 */
class OtelSpanDetailTest {
    private val sid = "s1"
    private val model = ModelRef("anthropic", "claude-test")
    private val usage = Usage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 3, cacheWriteTokens = 2)
    private var seq = 0L

    private fun rec(atMs: Long, event: SessionEvent) = EventRecord(++seq, atMs, event)
    private fun turn(n: Int = 1) = TurnId("t$n")

    private fun created(effort: Effort? = null) = rec(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default, effort))
    private fun prompt(at: Long, text: String = "你好") =
        rec(at, InputReceived(ClientMsgId("c$at"), listOf(TextPart(text)), Delivery.Queue, InputOrigin.User))
    private fun turnStart(at: Long, n: Int = 1) = rec(at, TurnStarted(turn(n), TurnTrigger.User))
    private fun turnEnd(at: Long, n: Int = 1, outcome: TurnOutcome = TurnOutcome.Completed) =
        rec(at, TurnEnded(turn(n), outcome, usage, durationMs = 100))
    private fun reqStart(at: Long, r: String = "r1", attempt: Int = 1, n: Int = 1) =
        rec(at, ModelRequestStarted(turn(n), RequestId(r), attempt, model, "fp"))
    private fun charged(at: Long) = rec(at, ModelCharged(model, usage, amount = 0.01, currency = "USD"))
    private fun reply(at: Long, r: String = "r1", ttft: Long? = null, first: Long? = null, stop: StopReason = StopReason.EndTurn) =
        rec(at, AssistantMessageCompleted(turn(), RequestId(r), MessageId("m$at"), listOf(TextPart("ok")), usage, stop, ttftMs = ttft, firstContentMs = first))
    private fun failed(at: Long, attempt: Int, kind: ModelFailureKind, retry: Boolean, status: Int? = null, r: String = "r1") =
        rec(at, ModelRequestFailed(turn(), RequestId(r), attempt, kind, "出错了", httpStatus = status, willRetry = retry))
    private fun planned(at: Long, call: String, tool: String, input: Map<String, String> = emptyMap()) =
        rec(at, ToolPlanned(turn(), CallId(call), tool, JsonObject(input.mapValues { JsonPrimitive(it.value) }), ResumePolicy.Unknown))
    private fun effect(at: Long, call: String) = rec(at, ToolEffectPending(turn(), CallId(call)))
    private fun outcome(at: Long, call: String, text: String = "out", error: Boolean = false, edits: List<FileEdit> = emptyList()) =
        rec(at, ToolOutcome(turn(), CallId(call), ToolResult(listOf(TextPart(text)), isError = error, fileEdits = edits)))
    private fun outcomeParts(at: Long, call: String, parts: List<dev.min.agent.protocol.Part>) =
        rec(at, ToolOutcome(turn(), CallId(call), ToolResult(parts)))

    private fun spansOf(
        events: List<EventRecord>,
        options: OtelOptions = OtelOptions(sessionId = sid),
        link: SpanLink? = null,
    ): List<OtelSpan> {
        val mapper = OtelSpanMapper(options, link)
        return events.flatMap { mapper.feed(it) }
    }

    private fun OtelSpan.text(k: String) = (attributes[k] as? OtelValue.Text)?.value
    private fun OtelSpan.whole(k: String) = (attributes[k] as? OtelValue.Whole)?.value
    private fun OtelSpan.flag(k: String) = (attributes[k] as? OtelValue.Flag)?.value
    private fun Map<String, OtelValue>.text(k: String) = (this[k] as? OtelValue.Text)?.value

    private fun llmOf(vararg events: EventRecord, options: OtelOptions = OtelOptions(sessionId = sid)): OtelSpan =
        spansOf(listOf(turnStart(1000)) + events.toList() + turnEnd(9000), options).single { it.name == SpanNames.LLM_REQUEST }

    // ---- llm_request 属性 ----

    @Test
    fun `llm_request 带 span 类型、speed、首字节与首内容耗时、finish_reasons`() {
        seq = 0
        val llm = llmOf(reqStart(1010), charged(1500), reply(1510, ttft = 120, first = 180, stop = StopReason.ToolUse))
        assertEquals("llm_request", llm.text("span.type"))
        assertEquals("normal", llm.text("speed")) // Min 没有快速模式，恒为 normal
        assertEquals(120L, llm.whole("ttft_ms"))
        assertEquals(180L, llm.whole("first_content_ms"))
        assertEquals(OtelValue.Texts(listOf("tool_use")), llm.attributes["gen_ai.response.finish_reasons"])
        assertEquals(10L, llm.whole("input_tokens"))
        assertEquals(3L, llm.whole("cache_read_tokens"))
        assertEquals(2L, llm.whole("cache_creation_tokens"))
    }

    @Test
    fun `日志里没有首字节耗时就不写，不编造`() {
        seq = 0
        val llm = llmOf(reqStart(1010), charged(1500), reply(1510))
        assertFalse(llm.attributes.containsKey("ttft_ms"))
        assertFalse(llm.attributes.containsKey("first_content_ms"))
    }

    @Test
    fun `effort 取自当时的会话设置：创建时、中途改、清掉`() {
        seq = 0
        val spans = spansOf(listOf(
            created(Effort.High),
            turnStart(1000, 1), reqStart(1010, "r1", n = 1), charged(1100), reply(1110, "r1"), turnEnd(1200, 1),
            rec(1300, SettingsChanged(effort = Effort.XHigh)),
            turnStart(2000, 2), reqStart(2010, "r2", n = 2), charged(2100), reply(2110, "r2"), turnEnd(2200, 2),
            rec(2300, SettingsChanged(clearEffort = true)),
            turnStart(3000, 3), reqStart(3010, "r3", n = 3), charged(3100), reply(3110, "r3"), turnEnd(3200, 3),
        )).filter { it.name == SpanNames.LLM_REQUEST }
        assertEquals(listOf("high", "xhigh", null), spans.map { it.text("effort") })
    }

    @Test
    fun `effort off 与没设都不写，会话中途接上时用传入的初始值`() {
        seq = 0
        val off = llmOf(reqStart(1010), charged(1100), reply(1110), options = OtelOptions(sessionId = sid))
        assertNull(off.text("effort"))
        val mapper = OtelSpanMapper(OtelOptions(sessionId = sid), null, initialEffort = Effort.Max)
        val spans = listOf(turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(1200)).flatMap { mapper.feed(it) }
        assertEquals("max", spans.single { it.name == SpanNames.LLM_REQUEST }.text("effort"))
    }

    @Test
    fun `query_source 受详细追踪开关门控，query_source_safe 总是有`() {
        seq = 0
        val plain = llmOf(reqStart(1010), charged(1100), reply(1110))
        assertFalse(plain.attributes.containsKey("query_source"))
        assertEquals("repl_main_thread", plain.text("query_source_safe"))
        seq = 0
        val detailed = llmOf(reqStart(1010), charged(1100), reply(1110), options = OtelOptions(sessionId = sid, detailedTracing = true))
        assertEquals("repl_main_thread", detailed.text("query_source"))
    }

    @Test
    fun `子 agent 的 query_source 是它的类型名，自定义类型的 safe 形式不泄露名字`() {
        fun child(type: String, detailed: Boolean): OtelSpan {
            seq = 0
            val lineage = OtelLineage("p", "p", "c9", type, 1, false)
            return spansOf(
                listOf(turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(1200)),
                OtelOptions(sessionId = "kid", lineage = lineage, detailedTracing = detailed),
                SpanLink("a".repeat(32), "b".repeat(16)),
            ).single { it.name == SpanNames.LLM_REQUEST }
        }
        assertEquals("Explore", child("Explore", true).text("query_source"))
        assertEquals("agent.Explore", child("Explore", false).text("query_source_safe"))
        assertEquals("agent.custom", child("my-secret-agent", false).text("query_source_safe"))
        assertFalse(child("my-secret-agent", false).attributes.containsKey("query_source"))
    }

    // ---- 重试合并与 gen_ai.request.attempt ----

    @Test
    fun `重试合并成一个 llm_request：耗时含重试，attempt 是总次数，每次重试一条 gen_ai_request_attempt 事件`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000), reqStart(1010, attempt = 1),
            failed(1200, 1, ModelFailureKind.RateLimited, retry = true, status = 429),
            reqStart(1300, attempt = 2),
            failed(1400, 2, ModelFailureKind.Overloaded, retry = true, status = 529),
            reqStart(1500, attempt = 3), charged(1600), reply(1610, ttft = 40, first = 60),
            turnEnd(1700),
        )).filter { it.name == SpanNames.LLM_REQUEST }
        val llm = spans.single()
        assertEquals(3L, llm.whole("attempt"))
        assertEquals(true, llm.flag("success"))
        assertNull(llm.error)
        assertEquals(1010L, llm.startMs)
        assertEquals(1610L, llm.endMs) // 含重试的整段
        assertEquals(listOf("gen_ai.request.attempt", "gen_ai.request.attempt"), llm.events.map { it.name })
        assertEquals(listOf(2L, 3L), llm.events.map { (it.attributes["attempt"] as OtelValue.Whole).value })
        assertEquals(listOf(1300L, 1500L), llm.events.map { it.timeMs })
        assertEquals(40L, llm.whole("ttft_ms")) // 成功那次的
    }

    @Test
    fun `没有重试就没有事件`() {
        seq = 0
        val llm = llmOf(reqStart(1010), charged(1100), reply(1110))
        assertTrue(llm.events.isEmpty())
    }

    @Test
    fun `重试用尽：一个错误的 llm_request，带最后一次的状态码、error_class 与总次数`() {
        seq = 0
        val llm = llmOf(
            reqStart(1010), failed(1100, 1, ModelFailureKind.Overloaded, retry = true, status = 529),
            reqStart(1200, attempt = 2), failed(1300, 2, ModelFailureKind.Overloaded, retry = false, status = 529),
        )
        assertEquals(2L, llm.whole("attempt"))
        assertEquals(false, llm.flag("success"))
        assertEquals(529L, llm.whole("status_code"))
        assertEquals("server_overload", llm.text("error_class"))
        assertNotNull(llm.error)
        assertEquals(1, llm.events.size)
    }

    @Test
    fun `error_class 按失败种类给短标识`() {
        val table = mapOf(
            ModelFailureKind.Overloaded to "server_overload",
            ModelFailureKind.RateLimited to "rate_limited",
            ModelFailureKind.Auth to "auth_error",
            ModelFailureKind.ContextOverflow to "context_overflow",
            ModelFailureKind.BadRequest to "bad_request",
            ModelFailureKind.ModelUnavailable to "model_unavailable",
            ModelFailureKind.Server to "server_error",
            ModelFailureKind.Network to "network_error",
            ModelFailureKind.Other to "other",
        )
        for ((kind, expected) in table) {
            seq = 0
            val llm = llmOf(reqStart(1010), failed(1100, 1, kind, retry = false))
            assertEquals(kind.name, expected, llm.text("error_class"))
        }
    }

    @Test
    fun `重试之后回合被中断：按最后一次失败收口，不丢`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000), reqStart(1010), failed(1100, 1, ModelFailureKind.Server, retry = true, status = 500),
            turnEnd(1200, outcome = TurnOutcome.Interrupted(InterruptedBy.User)),
        )).filter { it.name == SpanNames.LLM_REQUEST }
        val llm = spans.single()
        assertEquals(500L, llm.whole("status_code"))
        assertNotNull(llm.error)
    }

    @Test
    fun `换到回退模型后同一个请求 id 重新从 1 数：是另一个 llm_request，id 不撞`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000), reqStart(1010, attempt = 1), failed(1100, 1, ModelFailureKind.Overloaded, retry = false, status = 529),
            reqStart(1200, attempt = 1), charged(1300), reply(1310),
            turnEnd(1400),
        )).filter { it.name == SpanNames.LLM_REQUEST }
        assertEquals(2, spans.size)
        assertNotEquals(spans[0].spanId, spans[1].spanId)
    }

    // ---- tool 属性 ----

    @Test
    fun `tool_name_safe 把 MCP 工具记成 mcp_other，playwright 的固定形状除外`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000),
            planned(1010, "a", "Bash", mapOf("command" to "ls")), effect(1011, "a"), outcome(1020, "a"),
            planned(1030, "b", "mcp__my-private-server__lookup"), effect(1031, "b"), outcome(1040, "b"),
            planned(1050, "c", "mcp__playwright__browser_click"), effect(1051, "c"), outcome(1060, "c"),
            turnEnd(1100),
        )).filter { it.name == SpanNames.TOOL }
        assertEquals(listOf("Bash", "mcp_other", "mcp__playwright__browser_click"), spans.map { it.text("tool_name_safe") })
        // 原名仍在 tool_name（原文如此）；safe 形式不含服务器名
        assertFalse(spans.map { it.text("tool_name_safe") }.any { it!!.contains("my-private-server") })
    }

    @Test
    fun `Bash 的命令类别与 argv0：固定名单里的程序记名字，其余 other，解析不了是 unparsed`() {
        val cases = listOf(
            "git status" to ("vcs" to "git"),
            "  git -C x log" to ("vcs" to "git"),
            "FOO=1 npm install" to ("package_manager" to "npm"),
            "sudo apt-get update" to ("package_manager" to "apt-get"),
            "/usr/bin/git diff" to ("vcs" to "git"),
            "ls -la | grep x" to ("other" to "ls"),
            "my-secret-tool --flag" to ("other" to "other"),
            "\"git status" to ("unparsed" to "other"), // 第一个词的引号没闭合
            "echo \"hi" to ("other" to "echo"), // 程序已确定，后面的参数不影响
            "\$(whoami) x" to ("unparsed" to "other"),
            "(cd x && make)" to ("unparsed" to "other"),
            "" to ("unparsed" to "other"),
        )
        for ((command, expected) in cases) {
            val c = OtelBash.classify(command)
            assertEquals(command, expected, c.commandClass to c.argv0)
        }
        seq = 0
        val tool = spansOf(listOf(
            turnStart(1000), planned(1010, "a", "Bash", mapOf("command" to "git push origin secret-branch")), effect(1011, "a"), outcome(1020, "a"), turnEnd(1100),
        )).single { it.name == SpanNames.TOOL }
        assertEquals("vcs", tool.text("bash_command_class"))
        assertEquals("git", tool.text("bash_argv0"))
        // 命令本身（含分支名）受 OTEL_LOG_TOOL_DETAILS 门控，这里没开
        assertFalse(tool.attributes.containsKey("full_command"))
    }

    @Test
    fun `非 Bash 的 tool 没有命令类别属性`() {
        seq = 0
        val tool = spansOf(listOf(
            turnStart(1000), planned(1010, "a", "Read", mapOf("file_path" to "/w/a")), effect(1011, "a"), outcome(1020, "a"), turnEnd(1100),
        )).single { it.name == SpanNames.TOOL }
        assertFalse(tool.attributes.containsKey("bash_command_class"))
        assertFalse(tool.attributes.containsKey("bash_argv0"))
    }

    @Test
    fun `result_tokens 是工具结果的大致 token 数，按四个字符一个 token 向上取整`() {
        seq = 0
        val tool = spansOf(listOf(
            turnStart(1000), planned(1010, "a", "Read"), effect(1011, "a"), outcome(1020, "a", text = "x".repeat(401)), turnEnd(1100),
        )).single { it.name == SpanNames.TOOL }
        assertEquals(101L, tool.whole("result_tokens"))
    }

    @Test
    fun `skill_name 受 OTEL_LOG_TOOL_DETAILS 门控`() {
        fun tool(options: OtelOptions): OtelSpan {
            seq = 0
            return spansOf(
                listOf(turnStart(1000), planned(1010, "a", "Skill", mapOf("skill" to "deploy-secret")), effect(1011, "a"), outcome(1020, "a"), turnEnd(1100)),
                options,
            ).single { it.name == SpanNames.TOOL }
        }
        assertFalse(tool(OtelOptions(sessionId = sid)).attributes.containsKey("skill_name"))
        assertEquals("deploy-secret", tool(OtelOptions(sessionId = sid, logToolDetails = true)).text("skill_name"))
    }

    // ---- execution 的错误 ----

    @Test
    fun `execution 的 error 是类别，完整错误正文只在 OTEL_LOG_TOOL_DETAILS 开着时才有，error_class 是标识形式`() {
        fun exec(options: OtelOptions, tool: String): OtelSpan {
            seq = 0
            return spansOf(
                listOf(turnStart(1000), planned(1010, "a", tool, mapOf("command" to "false")), effect(1011, "a"), outcome(1020, "a", text = "秘密的错误正文", error = true), turnEnd(1100)),
                options,
            ).single { it.name == SpanNames.EXECUTION }
        }
        val bash = exec(OtelOptions(sessionId = sid), "Bash")
        assertEquals("ShellError", bash.text("error"))
        assertEquals("ShellError", bash.text("error_class"))
        assertFalse(bash.attributes.values.any { it is OtelValue.Text && it.value.contains("秘密") })
        val other = exec(OtelOptions(sessionId = sid), "Read")
        assertEquals("ToolError", other.text("error"))
        val detailed = exec(OtelOptions(sessionId = sid, logToolDetails = true), "Bash")
        assertEquals("秘密的错误正文", detailed.text("error"))
        assertEquals("ShellError", detailed.text("error_class"))
    }

    // ---- tool.output 事件 ----

    private fun toolWithOutput(
        tool: String,
        input: Map<String, String>,
        options: OtelOptions,
        text: String = "输出内容",
        edits: List<FileEdit> = emptyList(),
        error: Boolean = false,
    ): OtelSpan {
        seq = 0
        return spansOf(
            listOf(turnStart(1000), planned(1010, "a", tool, input), effect(1011, "a"), outcome(1020, "a", text, error, edits), turnEnd(1100)),
            options,
        ).single { it.name == SpanNames.TOOL }
    }

    @Test
    fun `没开 OTEL_LOG_TOOL_CONTENT 就没有 tool_output 事件，哪怕开了 TOOL_DETAILS 与 USER_PROMPTS`() {
        val everythingElse = OtelOptions(sessionId = sid, logToolDetails = true, logUserPrompts = true, logAssistantResponses = true)
        for ((tool, input) in listOf(
            "Read" to mapOf("file_path" to "/w/a"), "Bash" to mapOf("command" to "echo 秘密输出"),
            "Write" to mapOf("file_path" to "/w/a", "content" to "秘密输出"), "WebFetch" to mapOf("url" to "https://x"),
        )) {
            val span = toolWithOutput(tool, input, everythingElse, text = "秘密输出")
            assertTrue("$tool 不该有事件", span.events.isEmpty())
        }
    }

    @Test
    fun `内容绝不出现在导出里：没开 TOOL_CONTENT 时整段导出文本里找不到工具输出`() {
        seq = 0
        val spans = spansOf(
            listOf(
                prompt(900, "秘密提示词"), turnStart(1000),
                planned(1010, "a", "Bash", mapOf("command" to "echo 秘密命令")), effect(1011, "a"), outcome(1020, "a", "秘密输出"),
                planned(1030, "b", "Read", mapOf("file_path" to "/w/秘密路径")), effect(1031, "b"), outcome(1040, "b", "秘密文件正文"),
                turnEnd(1100),
            ),
            OtelOptions(sessionId = sid), // 全部开关默认关
        )
        val exported = OtlpJson.spans(spans, emptyList())
        for (secret in listOf("秘密提示词", "秘密命令", "秘密输出", "秘密路径", "秘密文件正文")) {
            assertFalse("$secret 不该出现在导出里", exported.contains(secret))
        }
    }

    @Test
    fun `Read 开了内容就有 content，file_path 还要 TOOL_DETAILS`() {
        val onlyContent = toolWithOutput("Read", mapOf("file_path" to "/w/a.txt"), OtelOptions(sessionId = sid, logToolContent = true), text = "文件正文")
        val event = onlyContent.events.single()
        assertEquals("tool.output", event.name)
        assertEquals("文件正文", event.attributes.text("content"))
        assertFalse(event.attributes.containsKey("file_path"))
        val withDetails = toolWithOutput("Read", mapOf("file_path" to "/w/a.txt"), OtelOptions(sessionId = sid, logToolContent = true, logToolDetails = true), text = "文件正文")
        assertEquals("/w/a.txt", withDetails.events.single().attributes.text("file_path"))
    }

    @Test
    fun `Bash 的 output 是合并输出，bash_command 受 TOOL_DETAILS 门控`() {
        val a = toolWithOutput("Bash", mapOf("command" to "echo hi"), OtelOptions(sessionId = sid, logToolContent = true), text = "hi")
        assertEquals("hi", a.events.single().attributes.text("output"))
        assertFalse(a.events.single().attributes.containsKey("bash_command"))
        val b = toolWithOutput("Bash", mapOf("command" to "echo hi"), OtelOptions(sessionId = sid, logToolContent = true, logToolDetails = true), text = "hi")
        assertEquals("echo hi", b.events.single().attributes.text("bash_command"))
    }

    @Test
    fun `Edit 与 Write 只有 TOOL_DETAILS 也开着才有事件，带 diff 与 content`() {
        val edits = listOf(FileEdit(GuestPath("/w/a.txt"), diff = "@@ -1 +1 @@\n-a\n+b"))
        val noDetails = toolWithOutput("Edit", mapOf("file_path" to "/w/a.txt"), OtelOptions(sessionId = sid, logToolContent = true), edits = edits)
        assertTrue(noDetails.events.isEmpty())
        val edit = toolWithOutput("Edit", mapOf("file_path" to "/w/a.txt"), OtelOptions(sessionId = sid, logToolContent = true, logToolDetails = true), edits = edits)
        assertEquals("@@ -1 +1 @@\n-a\n+b", edit.events.single().attributes.text("diff"))
        assertEquals("/w/a.txt", edit.events.single().attributes.text("file_path"))

        val writeNoDetails = toolWithOutput("Write", mapOf("file_path" to "/w/b", "content" to "新文件"), OtelOptions(sessionId = sid, logToolContent = true))
        assertTrue(writeNoDetails.events.isEmpty())
        val write = toolWithOutput("Write", mapOf("file_path" to "/w/b", "content" to "新文件"), OtelOptions(sessionId = sid, logToolContent = true, logToolDetails = true))
        assertEquals("新文件", write.events.single().attributes.text("content"))
    }

    @Test
    fun `MCP、WebFetch、WebSearch 的结果记在 output，图片换成占位`() {
        val opts = OtelOptions(sessionId = sid, logToolContent = true)
        for (tool in listOf("WebFetch", "WebSearch", "mcp__srv__lookup")) {
            val span = toolWithOutput(tool, emptyMap(), opts, text = "结果")
            assertEquals(tool, "结果", span.events.single().attributes.text("output"))
        }
        seq = 0
        val withImage = spansOf(
            listOf(
                turnStart(1000), planned(1010, "a", "mcp__srv__shot"), effect(1011, "a"),
                outcomeParts(1020, "a", listOf(TextPart("前"), ImagePart("image/png", ImageSource.Base64("AAAA")), TextPart("后"))),
                turnEnd(1100),
            ),
            opts,
        ).single { it.name == SpanNames.TOOL }
        assertEquals("前[image]后", withImage.events.single().attributes.text("output"))
    }

    @Test
    fun `出错的调用、图片的 Read、其他工具都没有事件`() {
        val opts = OtelOptions(sessionId = sid, logToolContent = true, logToolDetails = true)
        assertTrue(toolWithOutput("Bash", mapOf("command" to "false"), opts, error = true).events.isEmpty())
        assertTrue(toolWithOutput("Glob", mapOf("pattern" to "*"), opts).events.isEmpty())
        seq = 0
        val image = spansOf(
            listOf(
                turnStart(1000), planned(1010, "a", "Read", mapOf("file_path" to "/w/a.png")), effect(1011, "a"),
                outcomeParts(1020, "a", listOf(ImagePart("image/png", ImageSource.Base64("AAAA")))), turnEnd(1100),
            ),
            opts,
        ).single { it.name == SpanNames.TOOL }
        assertTrue(image.events.isEmpty())
    }

    @Test
    fun `内容按上限截断，被截断的属性带 _truncated 与 _original_length`() {
        val opts = OtelOptions(sessionId = sid, logToolContent = true, contentMaxLength = 20)
        val long = "x".repeat(100)
        val event = toolWithOutput("Bash", mapOf("command" to "yes"), opts, text = long).events.single()
        val out = event.attributes.text("output")!!
        assertTrue(out.length <= 20)
        assertTrue(out.endsWith(OTEL_TRUNCATED))
        assertEquals(OtelValue.Flag(true), event.attributes["output_truncated"])
        assertEquals(OtelValue.Whole(100), event.attributes["output_original_length"])
        // 没被截断就没有伴随属性
        val short = toolWithOutput("Bash", mapOf("command" to "yes"), opts, text = "短").events.single()
        assertFalse(short.attributes.containsKey("output_truncated"))
        assertFalse(short.attributes.containsKey("output_original_length"))
    }

    // ---- hook span ----

    private fun hooksRan(at: Long, event: String, call: String? = "a", turnId: TurnId? = turn(), durationMs: Long = 40, problems: List<String> = emptyList()) =
        rec(at, HooksRan(event, 2, "block", durationMs, turnId, call?.let { CallId(it) }, problems, succeeded = 1, blocking = 1, nonBlockingErrors = 0))

    @Test
    fun `没开详细 beta 追踪就没有 hook span`() {
        seq = 0
        val spans = spansOf(listOf(turnStart(1000), planned(1010, "a", "Write"), hooksRan(1100, "PreToolUse"), effect(1110, "a"), outcome(1200, "a"), turnEnd(1300)))
        assertTrue(spans.none { it.name == SpanNames.HOOK })
    }

    @Test
    fun `详细追踪下 hook span 挂在 interaction 下，带原文的字段`() {
        seq = 0
        val spans = spansOf(
            listOf(turnStart(1000), planned(1010, "a", "Write"), hooksRan(1100, "PreToolUse"), effect(1110, "a"), outcome(1200, "a"), turnEnd(1300)),
            OtelOptions(sessionId = sid, detailedTracing = true),
        )
        val hook = spans.single { it.name == SpanNames.HOOK }
        val interaction = spans.single { it.name == SpanNames.INTERACTION }
        assertEquals(interaction.spanId, hook.parentSpanId)
        assertEquals(interaction.traceId, hook.traceId)
        assertEquals("hook", hook.text("span.type"))
        assertEquals("PreToolUse", hook.text("hook_event"))
        assertEquals("PreToolUse:Write", hook.text("hook_name"))
        assertEquals(2L, hook.whole("num_hooks"))
        assertEquals(1L, hook.whole("num_success"))
        assertEquals(1L, hook.whole("num_blocking"))
        assertEquals(0L, hook.whole("num_non_blocking_error"))
        assertEquals(40.0, (hook.attributes["duration_ms"] as OtelValue.Real).value, 0.0)
        assertEquals(1060L, hook.startMs) // 结束时刻减耗时
        assertEquals(1100L, hook.endMs)
        assertNull(hook.error) // 拦下不是失败
        assertFalse(hook.attributes.containsKey("hook_definitions")) // TOOL_DETAILS 没开
    }

    @Test
    fun `hook 的非阻断错误让 span 状态为错误`() {
        seq = 0
        val failing = rec(1100, HooksRan("Stop", 1, "neutral", 10, turn(), null, listOf("「x」超时"), succeeded = 0, blocking = 0, nonBlockingErrors = 1))
        val hook = spansOf(listOf(turnStart(1000), failing, turnEnd(1200)), OtelOptions(sessionId = sid, detailedTracing = true))
            .single { it.name == SpanNames.HOOK }
        assertEquals("Stop", hook.text("hook_name")) // 没有工具调用：只有事件名
        assertNotNull(hook.error)
    }

    @Test
    fun `没有回合的 hook（SessionStart 一类）没有 span，旧日志缺计数时不写计数`() {
        seq = 0
        val spans = spansOf(
            listOf(
                rec(500, HooksRan("SessionStart", 1, "neutral", 5, null, null)),
                turnStart(1000), rec(1100, HooksRan("Stop", 1, "neutral", 5, turn(), null)), turnEnd(1200),
            ),
            OtelOptions(sessionId = sid, detailedTracing = true),
        )
        val hooks = spans.filter { it.name == SpanNames.HOOK }
        assertEquals(1, hooks.size)
        assertFalse(hooks.single().attributes.containsKey("num_success"))
        assertFalse(hooks.single().attributes.containsKey("num_blocking"))
    }

    @Test
    fun `hook_definitions 受 TOOL_DETAILS 门控，内容来自日志里冻结的 hooks 快照`() {
        fun hook(options: OtelOptions): OtelSpan {
            seq = 0
            val config = JsonObject(mapOf("PreToolUse" to JsonArray(listOf(JsonObject(mapOf("matcher" to JsonPrimitive("Write")))))))
            return spansOf(
                listOf(rec(1, HooksLoaded(listOf(HookSourceData("user", config)))), turnStart(1000), hooksRan(1100, "PreToolUse", call = null), turnEnd(1200)),
                options,
            ).single { it.name == SpanNames.HOOK }
        }
        assertFalse(hook(OtelOptions(sessionId = sid, detailedTracing = true)).attributes.containsKey("hook_definitions"))
        val defs = hook(OtelOptions(sessionId = sid, detailedTracing = true, logToolDetails = true)).text("hook_definitions")!!
        assertTrue(defs, defs.contains("\"matcher\":\"Write\""))
        assertFalse(defs.contains("Stop"))
    }

    @Test
    fun `组件 hooks 登记也算进 hook_definitions`() {
        seq = 0
        val config = JsonObject(mapOf("PreToolUse" to JsonArray(listOf(JsonObject(mapOf("matcher" to JsonPrimitive("Bash")))))))
        val hook = spansOf(
            listOf(rec(1, ComponentHooksRegistered("skill", "deploy", "user", config)), turnStart(1000), hooksRan(1100, "PreToolUse", call = null), turnEnd(1200)),
            OtelOptions(sessionId = sid, detailedTracing = true, logToolDetails = true),
        ).single { it.name == SpanNames.HOOK }
        assertTrue(hook.text("hook_definitions")!!.contains("Bash"))
    }

    // ---- 入站 TRACEPARENT ----

    private val inbound = TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "vendor=x")!!

    @Test
    fun `入站 TRACEPARENT 成为 interaction 的父：同一条 trace、parent_source 是 env、tracestate 带回`() {
        seq = 0
        val spans = spansOf(
            listOf(
                turnStart(1000, 1), reqStart(1010, "r1"), charged(1100), reply(1110, "r1"), turnEnd(1200, 1),
                turnStart(2000, 2), turnEnd(2100, 2),
            ),
            OtelOptions(sessionId = sid, inbound = inbound),
        )
        val interactions = spans.filter { it.name == SpanNames.INTERACTION }
        assertEquals(2, interactions.size)
        for (i in interactions) {
            assertEquals(inbound.traceId, i.traceId)
            assertEquals(inbound.spanId, i.parentSpanId)
            assertEquals("env", i.text("parent.source"))
            assertEquals("vendor=x", i.traceState)
        }
        assertNotEquals(interactions[0].spanId, interactions[1].spanId)
        // 子 span 同 trace、挂在 interaction 下
        val llm = spans.single { it.name == SpanNames.LLM_REQUEST }
        assertEquals(inbound.traceId, llm.traceId)
        assertEquals(interactions[0].spanId, llm.parentSpanId)
        assertNull(llm.traceState)
    }

    @Test
    fun `没有入站上下文时 interaction 自成一条 trace，parent_source 是 none`() {
        seq = 0
        val i = spansOf(listOf(turnStart(1000), turnEnd(1100))).single()
        assertNull(i.parentSpanId)
        assertEquals("none", i.text("parent.source"))
        assertNull(i.traceState)
    }

    @Test
    fun `子会话不读入站上下文，继续挂在父会话的工具 span 下`() {
        seq = 0
        val link = SpanLink("a".repeat(32), "b".repeat(16))
        val spans = spansOf(
            listOf(turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(1200)),
            OtelOptions(sessionId = "kid", lineage = OtelLineage("p", "p", "c9", "Explore", 1, false), inbound = inbound), link,
        )
        assertEquals(link.traceId, spans.single().traceId)
        assertEquals(link.parentSpanId, spans.single().parentSpanId)
    }

    @Test
    fun `TraceParent 解析：合规的收，坏的一律忽略（W3C）`() {
        val ok = TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")!!
        assertEquals("0af7651916cd43dd8448eb211c80319c", ok.traceId)
        assertEquals("b7ad6b7169203331", ok.spanId)
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", ok.header())
        val bad = listOf(
            null, "", "garbage", "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            "00-0af7651916cd43dd8448eb211c80319-b7ad6b7169203331-01", // trace 短一位
            "00-00000000000000000000000000000000-b7ad6b7169203331-01", // 全零
            "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",
            "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01", // 大写不合规
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331", // 少一段
        )
        for (v in bad) assertNull("$v", TraceParent.parse(v))
        assertEquals("vendor=x", TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", " vendor=x ")!!.traceState)
        assertNull(TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "  ")!!.traceState)
    }

    // ---- 名字登记 ----

    @Test
    fun `hook span 的名字登记了，照原文`() {
        assertTrue("claude_code.hook" in OTEL_SPAN_NAMES)
        assertEquals("claude_code.hook", SpanNames.HOOK)
    }
}
