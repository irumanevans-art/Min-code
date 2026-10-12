package dev.min.agent.otel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.protocol.MessageId
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksDisabledBy
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.McpServerInfo
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLoadingModes
import dev.min.agent.spi.SkillEntry
import dev.min.agent.spi.SkillsLoaded
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTel 第四段的事件映射补全：api_retries_exhausted、api_refusal、hook_registered / hook_execution_start / hook_execution_complete、
 * compaction、subagent_completed、skill_activated，以及 request_id（服务端请求 id）的新口径。
 */
class OtelMoreEventsTest {
    private val sid = "s1"
    private val model = ModelRef("anthropic", "claude-test")
    private val usage = Usage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 3, cacheWriteTokens = 2)
    private var seq = 0L

    private fun rec(atMs: Long, event: SessionEvent) = EventRecord(++seq, atMs, event)
    private val turn = TurnId("t1")

    private fun logs(events: List<EventRecord>, options: OtelOptions = OtelOptions(sessionId = sid)): List<OtelLog> {
        seq = 0
        val mapper = OtelMapper(options)
        return events.flatMap { mapper.feed(it) }.filterIsInstance<OtelLog>()
    }

    private fun List<OtelLog>.named(name: String) = filter { it.name == name }
    private fun OtelLog.text(k: String) = (attributes[k] as? OtelValue.Text)?.value
    private fun OtelLog.whole(k: String) = (attributes[k] as? OtelValue.Whole)?.value
    private fun OtelLog.real(k: String) = (attributes[k] as? OtelValue.Real)?.value
    private fun OtelLog.flag(k: String) = (attributes[k] as? OtelValue.Flag)?.value

    private fun reqStart(at: Long, attempt: Int = 1, m: ModelRef = model) = rec(at, ModelRequestStarted(turn, RequestId("r1"), attempt, m, "fp"))
    private fun failed(at: Long, attempt: Int, retry: Boolean, timedOut: Boolean = false, responseId: String? = null) =
        rec(at, ModelRequestFailed(turn, RequestId("r1"), attempt, ModelFailureKind.Server, "boom", httpStatus = 500, willRetry = retry, timedOut = timedOut, responseId = responseId))
    private fun charged(at: Long, responseId: String? = null) = rec(at, ModelCharged(model, usage, 0.01, "USD", responseId))
    private fun reply(at: Long, stop: StopReason = StopReason.EndTurn, responseId: String? = null) =
        rec(at, AssistantMessageCompleted(turn, RequestId("r1"), MessageId("m$at"), listOf(TextPart("ok")), usage, stop, responseId = responseId))

    // ---- request_id ----

    @Test
    fun `api_request 的 request_id 是服务端请求 id，没有就不写；Min 自己的请求 id 在 min_request_id`() {
        val with = logs(listOf(reqStart(1000), charged(1500, responseId = "req_011CT"))).named(OtelNames.API_REQUEST).single()
        assertEquals("req_011CT", with.text("request_id"))
        assertEquals("r1", with.text("min.request_id"))
        val without = logs(listOf(reqStart(1000), charged(1500))).named(OtelNames.API_REQUEST).single()
        assertFalse(without.attributes.containsKey("request_id"))
        assertEquals("r1", without.text("min.request_id"))
    }

    @Test
    fun `api_error 与 assistant_response 也带服务端请求 id，超时的 api_error 带 error_class`() {
        val err = logs(listOf(reqStart(1000), failed(1100, 1, retry = false, timedOut = true, responseId = "req_err"))).named(OtelNames.API_ERROR).single()
        assertEquals("req_err", err.text("request_id"))
        assertEquals("api_timeout", err.text("error_class"))
        val ok = logs(listOf(reqStart(1000), charged(1500), reply(1510, responseId = "req_ok"))).named(OtelNames.ASSISTANT_RESPONSE).single()
        assertEquals("req_ok", ok.text("request_id"))
    }

    // ---- api_retries_exhausted ----

    @Test
    fun `重试用尽：最终的 api_error 旁边记一次 api_retries_exhausted，带总尝试数与总耗时`() {
        val out = logs(listOf(
            reqStart(1000, 1), failed(1100, 1, retry = true),
            reqStart(1500, 2), failed(1600, 2, retry = true),
            reqStart(2500, 3), failed(2700, 3, retry = false),
        ))
        assertEquals("过程中的失败也各有 api_error", 3, out.named(OtelNames.API_ERROR).size)
        val ex = out.named(OtelNames.API_RETRIES_EXHAUSTED).single()
        assertEquals(3L, ex.whole("total_attempts"))
        assertEquals(1700.0, ex.real("total_retry_duration_ms")!!, 0.0)
        assertEquals(500L, ex.whole("status_code"))
        assertEquals("normal", ex.text("speed"))
        assertEquals("claude-test", ex.text("model"))
    }

    @Test
    fun `只尝试了一次就失败、或重试后成功，都不记 api_retries_exhausted`() {
        assertTrue(logs(listOf(reqStart(1000), failed(1100, 1, retry = false))).named(OtelNames.API_RETRIES_EXHAUSTED).isEmpty())
        assertTrue(logs(listOf(reqStart(1000, 1), failed(1100, 1, retry = true), reqStart(1200, 2), charged(1500), reply(1510)))
            .named(OtelNames.API_RETRIES_EXHAUSTED).isEmpty())
    }

    // ---- api_refusal ----

    @Test
    fun `模型拒绝时记 api_refusal，带 request_id、attempt、query_source、server_fallback_hop=false`() {
        val out = logs(listOf(
            rec(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default, dev.min.agent.protocol.Effort.High)),
            reqStart(1000, 1), charged(1500), reply(1510, StopReason.Refusal, responseId = "req_ref"),
        )).named(OtelNames.API_REFUSAL).single()
        assertEquals("req_ref", out.text("request_id"))
        assertEquals(1L, out.whole("attempt"))
        assertEquals("repl_main_thread", out.text("query_source"))
        assertEquals("high", out.text("effort"))
        assertEquals(false, out.flag("server_fallback_hop"))
        assertFalse("日志里没有 stop_details，不编造 has_category", out.attributes.containsKey("has_category"))
        assertFalse(out.attributes.containsKey("category"))
    }

    @Test
    fun `正常结束的回复没有 api_refusal`() {
        assertTrue(logs(listOf(reqStart(1000), charged(1500), reply(1510))).named(OtelNames.API_REFUSAL).isEmpty())
    }

    // ---- hook 事件 ----

    private fun hookConfig(event: String = "PreToolUse", matcher: String? = "Bash", type: String = "command") = JsonObject(mapOf(
        event to JsonArray(listOf(JsonObject(buildMap {
            if (matcher != null) put("matcher", JsonPrimitive(matcher))
            put("hooks", JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive(type), "command" to JsonPrimitive("echo $SECRET"))))))
        }))),
    ))
    private val SECRET = "SECRET-HOOK-COMMAND"

    @Test
    fun `hook_registered：每个配置的 hook 一条，来源按原文取值，matcher 受 TOOL_DETAILS 管，命令正文永不外露`() {
        val loaded = rec(2, HooksLoaded(listOf(
            HookSourceData("user", hookConfig()), HookSourceData("project", hookConfig("Stop", null, "http")),
            HookSourceData("managed", hookConfig("PostToolUse", "Edit", "prompt")),
        )))
        val off = logs(listOf(rec(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default)), loaded)).named(OtelNames.HOOK_REGISTERED)
        assertEquals(3, off.size)
        assertEquals(listOf("userSettings", "projectSettings", "policySettings"), off.map { it.text("hook_source") })
        assertEquals(listOf("PreToolUse", "Stop", "PostToolUse"), off.map { it.text("hook_event") })
        assertEquals(listOf("command", "http", "prompt"), off.map { it.text("hook_type") })
        assertTrue(off.all { it.text("safe_mode") == "false" })
        assertTrue("默认不带 matcher", off.none { it.attributes.containsKey("hook_matcher") })
        assertFalse(off.joinToString { it.attributes.toString() }.contains(SECRET))
        val on = logs(listOf(loaded), OtelOptions(sessionId = sid, logToolDetails = true)).named(OtelNames.HOOK_REGISTERED)
        assertEquals("Bash", on[0].text("hook_matcher"))
        assertFalse("没写 matcher 就没有", on[1].attributes.containsKey("hook_matcher"))
        assertFalse(on.joinToString { it.attributes.toString() }.contains(SECRET))
    }

    @Test
    fun `safe_mode 随会话的加载档`() {
        val out = logs(listOf(
            rec(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default, loadingModes = SessionLoadingModes(safe = true))),
            rec(2, HooksLoaded(listOf(HookSourceData("user", hookConfig())))),
        )).named(OtelNames.HOOK_REGISTERED).single()
        assertEquals("true", out.text("safe_mode"))
    }

    @Test
    fun `hook_execution_start 与 complete：一批一对，start 在记录时刻之前一个耗时，计数与取消数照日志`() {
        val out = logs(listOf(
            rec(1, ToolPlannedFor("c1", "Bash")),
            rec(5000, HooksRan("PreToolUse", 2, "block", 400, turn, CallId("c1"), succeeded = 1, blocking = 1, nonBlockingErrors = 0)),
            rec(6000, HooksRan("UserPromptSubmit", 2, "cancelled", 100, turn, problems = listOf("取消"), cancelled = 2)),
        ))
        val start = out.named(OtelNames.HOOK_EXECUTION_START)
        val complete = out.named(OtelNames.HOOK_EXECUTION_COMPLETE)
        assertEquals(2, start.size)
        assertEquals(2, complete.size)
        assertEquals("PreToolUse:Bash", start[0].text("hook_name"))
        assertEquals(4600L, start[0].timeMs)
        assertEquals(5000L, complete[0].timeMs)
        assertEquals(2L, complete[0].whole("num_hooks"))
        assertEquals(1L, complete[0].whole("num_success"))
        assertEquals(1L, complete[0].whole("num_blocking"))
        assertEquals(0L, complete[0].whole("num_non_blocking_error"))
        assertEquals(0L, complete[0].whole("num_cancelled"))
        assertEquals(400.0, complete[0].real("total_duration_ms")!!, 0.0)
        assertEquals("false", complete[0].text("managed_only"))
        assertEquals("merged", complete[0].text("hook_source"))
        assertEquals("UserPromptSubmit", complete[1].text("hook_name"))
        assertEquals(2L, complete[1].whole("num_cancelled"))
        // 输出长度类属性日志里没有，不编造
        for (k in listOf("stdout_chars", "additional_context_chars", "system_message_chars", "initial_user_message_chars", "num_outputs_persisted", "hook_definitions")) {
            assertFalse(k, complete[0].attributes.containsKey(k))
        }
    }

    private fun ToolPlannedFor(call: String, tool: String) = ToolPlanned(turn, CallId(call), tool, JsonObject(emptyMap()), ResumePolicy.Unknown)

    @Test
    fun `只许托管层 hooks 时 managed_only 为 true、来源为 policySettings`() {
        val out = logs(listOf(
            rec(1, HooksLoaded(emptyList(), disabledBy = HooksDisabledBy.ManagedOnly)),
            rec(2000, HooksRan("Stop", 1, "neutral", 10, turn, succeeded = 1, blocking = 0, nonBlockingErrors = 0)),
        )).named(OtelNames.HOOK_EXECUTION_COMPLETE).single()
        assertEquals("true", out.text("managed_only"))
        assertEquals("policySettings", out.text("hook_source"))
    }

    // ---- compaction ----

    @Test
    fun `compaction：自动、手动、超长后压缩（归 auto）；耗时、压缩前后 token、失败原因`() {
        val auto = logs(listOf(
            rec(1000, CompactionStarted(turn, "cp1", CompactionTrigger.Auto)),
            rec(3500, ContextCompacted(turn, "cp1", CompactionTrigger.Auto, summary = "x".repeat(400), through = MessageId("m1"), tokensBefore = 90_000)),
        )).named(OtelNames.COMPACTION).single()
        assertEquals("auto", auto.text("trigger"))
        assertEquals("auto", auto.text("min.trigger"))
        assertEquals("true", auto.text("success"))
        assertEquals(2500.0, auto.real("duration_ms")!!, 0.0)
        assertEquals(90_000L, auto.whole("pre_tokens"))
        assertEquals("摘要四字符一 token 的估算", 100L, auto.whole("post_tokens"))
        assertFalse(auto.attributes.containsKey("precompute_reuse"))
        val manual = logs(listOf(
            rec(1000, CompactionStarted(turn, "cp2", CompactionTrigger.Manual, instructions = "焦点")),
            rec(2000, ContextCompacted(turn, "cp2", CompactionTrigger.Manual, "s", MessageId("m1"), tokensBefore = 10)),
        )).named(OtelNames.COMPACTION).single()
        assertEquals("manual", manual.text("trigger"))
        val overflow = logs(listOf(
            rec(1000, CompactionStarted(turn, "cp3", CompactionTrigger.Overflow)),
            rec(2000, ContextCompacted(turn, "cp3", CompactionTrigger.Overflow, "s", MessageId("m1"))),
        )).named(OtelNames.COMPACTION).single()
        assertEquals("auto", overflow.text("trigger"))
        assertEquals("overflow", overflow.text("min.trigger"))
    }

    @Test
    fun `压缩失败：success 为 false，带原因与耗时；摘要的正文不外露`() {
        val out = logs(listOf(
            rec(1000, CompactionStarted(turn, "cp1", CompactionTrigger.Manual)),
            rec(1800, CompactionFailed(turn, "cp1", "摘要请求失败")),
        )).named(OtelNames.COMPACTION).single()
        assertEquals("false", out.text("success"))
        assertEquals("摘要请求失败", out.text("error"))
        assertEquals("manual", out.text("trigger"))
        assertEquals(800.0, out.real("duration_ms")!!, 0.0)
        val withBody = logs(listOf(
            rec(1000, CompactionStarted(turn, "cp1", CompactionTrigger.Auto)),
            rec(2000, ContextCompacted(turn, "cp1", CompactionTrigger.Auto, "SECRET-SUMMARY-BODY", MessageId("m1"))),
        ))
        assertFalse(withBody.joinToString { it.attributes.toString() }.contains("SECRET-SUMMARY-BODY"))
    }

    // ---- subagent_completed ----

    private fun child(type: String, team: Boolean = false) = OtelOptions(
        sessionId = "kid",
        lineage = OtelLineage(parent = "p", root = "p", parentCall = "c1", agentType = type, depth = 1, teamMember = team),
    )

    private fun childRun(options: OtelOptions, vararg extra: SessionEvent): List<OtelLog> {
        val events = ArrayList<EventRecord>()
        seq = 0
        events += rec(1000, TurnStarted(turn, TurnTrigger.User))
        events += rec(1010, reqStart(1010, 1).event)
        events += rec(1100, ToolPlannedFor("a", "Read"))
        events += rec(1110, ToolPlannedFor("b", "Grep"))
        events += rec(1500, reply(1500).event)
        extra.forEach { events += rec(1600, it) }
        events += rec(2000, TurnEnded(turn, TurnOutcome.Completed, usage, durationMs = 1000))
        val mapper = OtelMapper(options)
        return events.flatMap { mapper.feed(it) }.filterIsInstance<OtelLog>()
    }

    @Test
    fun `子 agent 交回结果：subagent_completed 带类型、工具次数、最后一次请求的上下文规模、耗时、模型`() {
        val out = childRun(child("Explore")).named(OtelNames.SUBAGENT_COMPLETED).single()
        assertEquals("Explore", out.text("agent_type"))
        assertEquals("built-in", out.text("agent.source"))
        assertEquals(true, out.flag("is_built_in"))
        assertEquals(2L, out.whole("total_tool_uses"))
        assertEquals("最后一次请求的输入 + 缓存读写 + 输出", 20L, out.whole("total_tokens"))
        assertEquals(1000.0, out.real("duration_ms")!!, 0.0)
        assertEquals("claude-test", out.text("model"))
        assertEquals("claude-test", out.text("final_model"))
        assertEquals(false, out.flag("model_swapped"))
        assertFalse("后台与否在子会话日志里看不出来", out.attributes.containsKey("is_async"))
    }

    @Test
    fun `自定义类型的名字换成 custom，OTEL_LOG_TOOL_DETAILS 开着才给真名；不写不知道的 agent_source`() {
        val hidden = childRun(child("my-secret-agent")).named(OtelNames.SUBAGENT_COMPLETED).single()
        assertEquals("custom", hidden.text("agent_type"))
        assertEquals(false, hidden.flag("is_built_in"))
        assertFalse(hidden.attributes.containsKey("agent.source"))
        val shown = childRun(child("my-secret-agent").copy(logToolDetails = true)).named(OtelNames.SUBAGENT_COMPLETED).single()
        assertEquals("my-secret-agent", shown.text("agent_type"))
    }

    @Test
    fun `根会话与团队成员没有 subagent_completed`() {
        assertTrue(childRun(OtelOptions(sessionId = sid)).named(OtelNames.SUBAGENT_COMPLETED).isEmpty())
        assertTrue(childRun(child("general-purpose", team = true)).named(OtelNames.SUBAGENT_COMPLETED).isEmpty())
    }

    @Test
    fun `回合里换过模型：model_swapped 为 true，final_model 是最后的`() {
        val other = ModelRef("anthropic", "claude-fallback")
        seq = 0
        val mapper = OtelMapper(child("general-purpose"))
        val events = listOf(
            rec(1000, TurnStarted(turn, TurnTrigger.User)),
            rec(1010, ModelRequestStarted(turn, RequestId("r1"), 1, model, "fp")),
            rec(1100, ModelRequestFailed(turn, RequestId("r1"), 1, ModelFailureKind.Overloaded, "x", willRetry = false)),
            rec(1200, ModelRequestStarted(turn, RequestId("r1"), 1, other, "fp")),
            rec(1500, AssistantMessageCompleted(turn, RequestId("r1"), MessageId("m"), listOf(TextPart("ok")), usage, StopReason.EndTurn)),
            rec(2000, TurnEnded(turn, TurnOutcome.Completed, usage, durationMs = 1000)),
        )
        val out = events.flatMap { mapper.feed(it) }.filterIsInstance<OtelLog>().named(OtelNames.SUBAGENT_COMPLETED).single()
        assertEquals(true, out.flag("model_swapped"))
        assertEquals("claude-test", out.text("model"))
        assertEquals("claude-fallback", out.text("final_model"))
    }

    // ---- mcp_server_connection ----

    private fun mcp(vararg servers: McpServerInfo) = McpServersLoaded(servers.toList())
    private fun server(name: String, state: String, reason: String? = null) = McpServerInfo(name, "project", "stdio", state, reason)

    @Test
    fun `mcp_server_connection：状态变化才记，连上 失败 断开各一种，名字与错误受 TOOL_DETAILS 管`() {
        val events = listOf(
            rec(1000, mcp(server("github", "pending"), server("jira", "pending"))),
            rec(2000, mcp(server("github", "connected"), server("jira", "failed", "token SECRET-MCP-ERR 无效"))),
            rec(3000, mcp(server("github", "connected"), server("jira", "failed", "token SECRET-MCP-ERR 无效"))),
            rec(4000, mcp(server("github", "disabled"))),
        )
        val off = logs(events).named(OtelNames.MCP_SERVER_CONNECTION)
        assertEquals("pending 不记；第三份状态没变不记", listOf("connected", "failed", "disconnected"), off.map { it.text("status") })
        assertTrue(off.all { it.text("transport_type") == "stdio" || it.text("status") == "disconnected" })
        assertEquals("project", off[0].text("server_scope"))
        assertFalse("默认没有服务器名与错误正文", off.any { it.attributes.containsKey("server_name") || it.attributes.containsKey("error") })
        assertFalse(off.joinToString { it.attributes.toString() }.contains("SECRET-MCP-ERR"))
        val on = logs(events, OtelOptions(sessionId = sid, logToolDetails = true)).named(OtelNames.MCP_SERVER_CONNECTION)
        assertEquals(listOf("github", "jira", "github"), on.map { it.text("server_name") })
        assertEquals("token SECRET-MCP-ERR 无效", on[1].text("error"))
        assertFalse("日志里没有的耗时与错误码不编造", on.any { it.attributes.containsKey("duration_ms") || it.attributes.containsKey("error_code") })
    }

    @Test
    fun `整台服务器从清单里消失也算断开`() {
        val out = logs(listOf(rec(1000, mcp(server("github", "connected"))), rec(2000, mcp()))).named(OtelNames.MCP_SERVER_CONNECTION)
        assertEquals(listOf("connected", "disconnected"), out.map { it.text("status") })
    }

    // ---- skill_activated ----

    private fun skill(name: String, source: String?) = SkillEntry(name, "d", GuestPath("/s/$name/SKILL.md"), source = source)

    private fun skillRun(tool: String, input: Map<String, String>, error: Boolean, options: OtelOptions = OtelOptions(sessionId = sid)): List<OtelLog> =
        logs(listOf(
            rec(1, SkillsLoaded(listOf(skill("deploy", "project"), skill("review", "内置"), skill("mine", "user")))),
            rec(1000, TurnStarted(turn, TurnTrigger.User)),
            rec(1100, ToolPlanned(turn, CallId("c1"), tool, JsonObject(input.mapValues { JsonPrimitive(it.value) }), ResumePolicy.Unknown)),
            rec(1200, ToolOutcome(turn, CallId("c1"), ToolResult(listOf(TextPart("ok")), isError = error))),
        ), options).named(OtelNames.SKILL_ACTIVATED)

    @Test
    fun `模型调 Skill 工具成功：skill_activated 带来源与触发方式，用户自写的名字默认是 custom_skill`() {
        val out = skillRun("Skill", mapOf("skill" to "deploy"), error = false).single()
        assertEquals("custom_skill", out.text("skill.name"))
        assertEquals("claude-proactive", out.text("invocation_trigger"))
        assertEquals("projectSettings", out.text("skill.source"))
    }

    @Test
    fun `OTEL_LOG_TOOL_DETAILS 开着给真名；内置 skill 一律原样`() {
        assertEquals("deploy", skillRun("Skill", mapOf("skill" to "deploy"), false, OtelOptions(sessionId = sid, logToolDetails = true)).single().text("skill.name"))
        val builtin = skillRun("Skill", mapOf("skill" to "review"), false).single()
        assertEquals("review", builtin.text("skill.name"))
        assertEquals("bundled", builtin.text("skill.source"))
    }

    @Test
    fun `失败的 Skill 调用与别的工具不产出 skill_activated`() {
        assertTrue(skillRun("Skill", mapOf("skill" to "deploy"), error = true).isEmpty())
        assertTrue(skillRun("Read", mapOf("skill" to "deploy"), error = false).isEmpty())
    }

    @Test
    fun `清单里没有的 skill 也记（来源未知就不写）`() {
        val out = skillRun("Skill", mapOf("skill" to "ghost"), error = false).single()
        assertEquals("custom_skill", out.text("skill.name"))
        assertNull(out.text("skill.source"))
        assertNotNull(out.text("invocation_trigger"))
    }
}
