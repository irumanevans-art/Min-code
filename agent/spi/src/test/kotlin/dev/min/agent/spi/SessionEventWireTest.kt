package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话事件与 spi 值类型的线形状测试：事件按 seq 原样写进日志，折叠成内存状态后还要能逐字节重建
 * 下一次模型请求（docs/agent/02-总体架构.md §1）。谁改了字段名或判别值，期望 JSON 立刻红。
 *
 * 期望 JSON 一律按源码里的 @SerialName 与属性名手写；比较前两边都过 AgentJson.parseToJsonElement。
 */
class SessionEventWireTest {

    private val model = ModelRef(providerId = "anthropic", modelId = "claude-sonnet-5-5")

    private fun <T> roundTrips(serializer: KSerializer<T>, value: T) {
        val text = AgentJson.encodeToString(serializer, value)
        assertEquals("往返不相等：$text", value, AgentJson.decodeFromString(serializer, text))
    }

    private fun <T> assertWire(serializer: KSerializer<T>, value: T, expectedJson: String) {
        val actual = AgentJson.encodeToString(serializer, value)
        assertEquals(
            AgentJson.parseToJsonElement(expectedJson),
            AgentJson.parseToJsonElement(actual),
        )
    }

    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        roundTrips(SessionEvent.serializer(), event)
        assertWire(SessionEvent.serializer(), event, expectedJson)
    }

    // ---- 会话设置事件 ----

    @Test
    fun `session created keeps its wire shape`() {
        assertEventWire(
            SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default),
            """{"type":"session_created","cwd":"/workspace","model":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"mode":"default"}""",
        )
        // effort 有值才落盘
        assertEventWire(
            SessionCreated(GuestPath("/workspace"), model, PermissionMode.Plan, Effort.High),
            """{"type":"session_created","cwd":"/workspace","model":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"mode":"plan","effort":"high"}""",
        )
    }

    @Test
    fun `settings changed writes only the changed fields`() {
        // 全默认 → 只有判别键
        assertEventWire(
            SettingsChanged(),
            """{"type":"settings_changed"}""",
        )
        assertEventWire(
            SettingsChanged(
                model = ModelRef("openai", "gpt-5"),
                mode = PermissionMode.Auto,
                effort = Effort.Low,
                cwd = GuestPath("/workspace/sub"),
            ),
            """{"type":"settings_changed","model":{"providerId":"openai","modelId":"gpt-5"},"mode":"auto","effort":"low","cwd":"/workspace/sub"}""",
        )
    }

    // ---- 输入事件 ----

    @Test
    fun `input events keep their wire shape`() {
        assertEventWire(
            InputReceived(ClientMsgId("cm_1"), listOf(TextPart("hi")), Delivery.Queue),
            """{"type":"input_received","clientMsgId":"cm_1","parts":[{"type":"text","text":"hi"}],"delivery":"queue"}""",
        )
        assertEventWire(
            InputReceived(ClientMsgId("cm_2"), listOf(TextPart("插话")), Delivery.Steer),
            """{"type":"input_received","clientMsgId":"cm_2","parts":[{"type":"text","text":"插话"}],"delivery":"steer"}""",
        )
        assertEventWire(
            InputPromoted(ClientMsgId("cm_1"), MessageId("msg_1")),
            """{"type":"input_promoted","clientMsgId":"cm_1","messageId":"msg_1"}""",
        )
        assertEventWire(
            InputWithdrawn(ClientMsgId("cm_1")),
            """{"type":"input_withdrawn","clientMsgId":"cm_1"}""",
        )
    }

    // ---- 回合与模型请求事件 ----

    @Test
    fun `turn and model request events keep their wire shape`() {
        assertEventWire(
            TurnStarted(TurnId("turn_1"), TurnTrigger.User),
            """{"type":"turn_started","turnId":"turn_1","trigger":"user"}""",
        )
        assertEventWire(
            TurnStarted(TurnId("turn_2"), TurnTrigger.Resume),
            """{"type":"turn_started","turnId":"turn_2","trigger":"resume"}""",
        )
        assertEventWire(
            ModelRequestStarted(TurnId("turn_1"), RequestId("req_1"), attempt = 2, model = model, prefixFingerprint = "abc123"),
            """{"type":"model_request_started","turnId":"turn_1","requestId":"req_1","attempt":2,"model":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"prefixFingerprint":"abc123"}""",
        )
        assertEventWire(
            AssistantSegment(TurnId("turn_1"), RequestId("req_1"), MessageId("msg_1"), listOf(TextPart("half"))),
            """{"type":"assistant_segment","turnId":"turn_1","requestId":"req_1","messageId":"msg_1","parts":[{"type":"text","text":"half"}]}""",
        )
        assertEventWire(
            AssistantMessageCompleted(
                TurnId("turn_1"), RequestId("req_1"), MessageId("msg_1"),
                parts = listOf(TextPart("done")),
                usage = Usage(inputTokens = 3, outputTokens = 4),
                stop = StopReason.EndTurn,
            ),
            """{"type":"assistant_message_completed","turnId":"turn_1","requestId":"req_1","messageId":"msg_1","parts":[{"type":"text","text":"done"}],"usage":{"inputTokens":3,"outputTokens":4},"stop":{"type":"end_turn"}}""",
        )
        // truncated = true 才落盘
        assertEventWire(
            AssistantMessageCompleted(
                TurnId("turn_1"), RequestId("req_1"), MessageId("msg_1"),
                parts = emptyList(), usage = Usage(), stop = StopReason.MaxTokens, truncated = true,
            ),
            """{"type":"assistant_message_completed","turnId":"turn_1","requestId":"req_1","messageId":"msg_1","parts":[],"usage":{},"stop":{"type":"max_tokens"},"truncated":true}""",
        )
        // 流式耗时（遥测 ttft_ms / first_content_ms）有值才落盘
        assertEventWire(
            AssistantMessageCompleted(
                TurnId("turn_1"), RequestId("req_1"), MessageId("msg_1"),
                parts = listOf(TextPart("done")), usage = Usage(), stop = StopReason.EndTurn, ttftMs = 120, firstContentMs = 180,
            ),
            """{"type":"assistant_message_completed","turnId":"turn_1","requestId":"req_1","messageId":"msg_1","parts":[{"type":"text","text":"done"}],"usage":{},"stop":{"type":"end_turn"},"ttftMs":120,"firstContentMs":180}""",
        )
        // hook 批的结局计数（遥测 hook span）有值才落盘；旧日志缺这三个键照常读
        assertEventWire(
            HooksRan("PreToolUse", 2, "block", 40, TurnId("turn_1"), CallId("call_1"), succeeded = 1, blocking = 1, nonBlockingErrors = 0),
            """{"type":"hooks_ran","event":"PreToolUse","handlers":2,"decision":"block","durationMs":40,"turnId":"turn_1","callId":"call_1","succeeded":1,"blocking":1,"nonBlockingErrors":0}""",
        )
        assertEventWire(
            HooksRan("PreToolUse", 1, "neutral", 5),
            """{"type":"hooks_ran","event":"PreToolUse","handlers":1,"decision":"neutral","durationMs":5}""",
        )
        // 第四段：响应 id、traceresponse、客户端请求 id、超时、被取消的 hook 数（遥测要用，有值才落盘）
        assertEventWire(
            AssistantMessageCompleted(
                TurnId("turn_1"), RequestId("req_1"), MessageId("msg_1"),
                parts = emptyList(), usage = Usage(), stop = StopReason.EndTurn, responseId = "req_abc", traceResponse = "00-aa-bb-01",
            ),
            """{"type":"assistant_message_completed","turnId":"turn_1","requestId":"req_1","messageId":"msg_1","parts":[],"usage":{},"stop":{"type":"end_turn"},"responseId":"req_abc","traceResponse":"00-aa-bb-01"}""",
        )
        assertEventWire(
            ModelRequestStarted(TurnId("turn_1"), RequestId("req_1"), 1, ModelRef("p", "m"), "fp", clientRequestId = "creq_1"),
            """{"type":"model_request_started","turnId":"turn_1","requestId":"req_1","attempt":1,"model":{"providerId":"p","modelId":"m"},"prefixFingerprint":"fp","clientRequestId":"creq_1"}""",
        )
        assertEventWire(
            ModelRequestFailed(TurnId("turn_1"), RequestId("req_1"), attempt = 1, kind = ModelFailureKind.Network, message = "t", timedOut = true, responseId = "req_x"),
            """{"type":"model_request_failed","turnId":"turn_1","requestId":"req_1","attempt":1,"kind":"network","message":"t","timedOut":true,"responseId":"req_x"}""",
        )
        assertEventWire(
            HooksRan("PreToolUse", 2, "cancelled", 40, TurnId("turn_1"), cancelled = 2),
            """{"type":"hooks_ran","event":"PreToolUse","handlers":2,"decision":"cancelled","durationMs":40,"turnId":"turn_1","cancelled":2}""",
        )
        // httpStatus / willRetry 默认不落盘
        assertEventWire(
            ModelRequestFailed(TurnId("turn_1"), RequestId("req_1"), attempt = 1, kind = ModelFailureKind.RateLimited, message = "429 too many"),
            """{"type":"model_request_failed","turnId":"turn_1","requestId":"req_1","attempt":1,"kind":"rate_limited","message":"429 too many"}""",
        )
        assertEventWire(
            ModelRequestFailed(TurnId("turn_1"), RequestId("req_1"), attempt = 2, kind = ModelFailureKind.Server, message = "boom", httpStatus = 500, willRetry = true),
            """{"type":"model_request_failed","turnId":"turn_1","requestId":"req_1","attempt":2,"kind":"server","message":"boom","httpStatus":500,"willRetry":true}""",
        )
    }

    // ---- 工具事件 ----

    @Test
    fun `tool events keep their wire shape`() {
        assertEventWire(
            ToolPlanned(TurnId("turn_1"), CallId("call_1"), "Bash", buildJsonObject { put("cmd", "ls") }, ResumePolicy.Replay),
            """{"type":"tool_planned","turnId":"turn_1","callId":"call_1","tool":"Bash","input":{"cmd":"ls"},"resumePolicy":"replay"}""",
        )
        val ask = PermissionAsk(
            tool = "Bash",
            summary = "run ls",
            paths = listOf(PathAccess(GuestPath("/workspace/a"), AccessKind.Write, PathScope.Workspace)),
            commands = listOf(CommandAccess("ls -la")),
            domains = listOf("example.com"),
        )
        assertEventWire(
            ApprovalRequested(TurnId("turn_1"), ApprovalId("ap_1"), CallId("call_1"), ask),
            """{"type":"approval_requested","turnId":"turn_1","approvalId":"ap_1","callId":"call_1","ask":{"tool":"Bash","summary":"run ls","paths":[{"path":"/workspace/a","kind":"write","scope":"workspace"}],"commands":[{"raw":"ls -la"}],"domains":["example.com"]}}""",
        )
        assertEventWire(
            ApprovalResolved(ApprovalId("ap_1"), ApprovalChoice.Once, DecidedBy.User),
            """{"type":"approval_resolved","approvalId":"ap_1","choice":{"type":"once"},"decidedBy":{"type":"user"}}""",
        )
        // handle 默认不落盘
        assertEventWire(
            ToolEffectPending(TurnId("turn_1"), CallId("call_1")),
            """{"type":"tool_effect_pending","turnId":"turn_1","callId":"call_1"}""",
        )
        assertEventWire(
            ToolEffectPending(TurnId("turn_1"), CallId("call_1"), ExecHandle("h1", pid = 42, logPath = GuestPath("/tmp/x.log"))),
            """{"type":"tool_effect_pending","turnId":"turn_1","callId":"call_1","handle":{"id":"h1","pid":42,"logPath":"/tmp/x.log"}}""",
        )
        assertEventWire(
            ToolOutcome(TurnId("turn_1"), CallId("call_1"), ToolResult(listOf(TextPart("ok")))),
            """{"type":"tool_outcome","turnId":"turn_1","callId":"call_1","result":{"content":[{"type":"text","text":"ok"}]}}""",
        )
        // 失败类别（遥测 tool.execution 的 error）有值才落盘
        assertEventWire(
            ToolOutcome(TurnId("turn_1"), CallId("call_1"), ToolResult(listOf(TextPart("no")), isError = true, errorKind = "Error:ENOENT")),
            """{"type":"tool_outcome","turnId":"turn_1","callId":"call_1","result":{"content":[{"type":"text","text":"no"}],"isError":true,"errorKind":"Error:ENOENT"}}""",
        )
    }

    // ---- 回合结束与 note ----

    @Test
    fun `turn ended and note keep their wire shape`() {
        assertEventWire(
            TurnEnded(TurnId("turn_1"), TurnOutcome.Completed, Usage(), durationMs = 1234),
            """{"type":"turn_ended","turnId":"turn_1","outcome":{"type":"completed"},"usage":{},"durationMs":1234}""",
        )
        assertEventWire(
            TurnEnded(TurnId("turn_1"), TurnOutcome.Failed("boom"), Usage(), durationMs = 1234),
            """{"type":"turn_ended","turnId":"turn_1","outcome":{"type":"failed","message":"boom"},"usage":{},"durationMs":1234}""",
        )
        assertEventWire(
            TurnEnded(TurnId("turn_1"), TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), Usage(), durationMs = 1234),
            """{"type":"turn_ended","turnId":"turn_1","outcome":{"type":"interrupted","by":"process_death"},"usage":{},"durationMs":1234}""",
        )
        assertEventWire(
            Note(NoteLevel.Warning, "careful"),
            """{"type":"note","level":"warning","text":"careful"}""",
        )
    }

    @Test
    fun `turn outcome variants keep their serial names`() {
        val cases: List<Pair<TurnOutcome, String>> = listOf(
            TurnOutcome.Completed to """{"type":"completed"}""",
            TurnOutcome.Failed("boom") to """{"type":"failed","message":"boom"}""",
            TurnOutcome.Interrupted(InterruptedBy.Limit) to """{"type":"interrupted","by":"limit"}""",
        )
        for ((value, expected) in cases) {
            roundTrips(TurnOutcome.serializer(), value)
            assertWire(TurnOutcome.serializer(), value, expected)
        }
    }

    // ---- 审批答复与决定者 ----

    @Test
    fun `approval choice variants keep their serial names`() {
        val cases: List<Pair<ApprovalChoice, String>> = listOf(
            ApprovalChoice.Once to """{"type":"once"}""",
            ApprovalChoice.Always("Bash(git status *)") to """{"type":"always","rule":"Bash(git status *)"}""",
            ApprovalChoice.Deny() to """{"type":"deny"}""",
            ApprovalChoice.Deny("别 push") to """{"type":"deny","note":"别 push"}""",
            ApprovalChoice.Deny("非交互模式下没有人审批", fromHost = true) to """{"type":"deny","note":"非交互模式下没有人审批","fromHost":true}""",
        )
        for ((value, expected) in cases) {
            roundTrips(ApprovalChoice.serializer(), value)
            assertWire(ApprovalChoice.serializer(), value, expected)
        }
    }

    @Test
    fun `decided by variants keep their serial names`() {
        val cases: List<Pair<DecidedBy, String>> = listOf(
            DecidedBy.Rule("Read(**)", "user") to """{"type":"rule","rule":"Read(**)","source":"user"}""",
            DecidedBy.Hook("pre-tool") to """{"type":"hook","name":"pre-tool"}""",
            DecidedBy.Mode(PermissionMode.BypassPermissions) to """{"type":"mode","mode":"bypassPermissions"}""",
            DecidedBy.Classifier to """{"type":"classifier"}""",
            DecidedBy.User to """{"type":"user"}""",
            DecidedBy.Timeout to """{"type":"timeout"}""",
        )
        for ((value, expected) in cases) {
            roundTrips(DecidedBy.serializer(), value)
            assertWire(DecidedBy.serializer(), value, expected)
        }
    }

    // ---- 枚举逐值 ----

    private fun <T : Enum<T>> enumCases(serializer: KSerializer<T>, cases: Map<T, String>) {
        for ((value, wire) in cases) {
            assertEquals("\"$wire\"", AgentJson.encodeToString(serializer, value))
            assertEquals(value, AgentJson.decodeFromString(serializer, "\"$wire\""))
        }
    }

    @Test
    fun `session event enums keep their serial names`() {
        enumCases(
            Delivery.serializer(),
            mapOf(Delivery.Queue to "queue", Delivery.Steer to "steer"),
        )
        enumCases(
            TurnTrigger.serializer(),
            mapOf(TurnTrigger.User to "user", TurnTrigger.Resume to "resume"),
        )
        enumCases(
            ModelFailureKind.serializer(),
            mapOf(
                ModelFailureKind.Network to "network",
                ModelFailureKind.RateLimited to "rate_limited",
                ModelFailureKind.Overloaded to "overloaded",
                ModelFailureKind.ContextOverflow to "context_overflow",
                ModelFailureKind.Auth to "auth",
                ModelFailureKind.BadRequest to "bad_request",
                ModelFailureKind.Server to "server",
                ModelFailureKind.Other to "other",
            ),
        )
        enumCases(
            ResumePolicy.serializer(),
            mapOf(
                ResumePolicy.Replay to "replay",
                ResumePolicy.Reattach to "reattach",
                ResumePolicy.Unknown to "unknown",
            ),
        )
        enumCases(
            InterruptedBy.serializer(),
            mapOf(
                InterruptedBy.User to "user",
                InterruptedBy.ProcessDeath to "process_death",
                InterruptedBy.Limit to "limit",
                InterruptedBy.Budget to "budget",
            ),
        )
        enumCases(
            NoteLevel.serializer(),
            mapOf(NoteLevel.Info to "info", NoteLevel.Warning to "warning", NoteLevel.Error to "error"),
        )
    }

    @Test
    fun `permission enums keep their serial names`() {
        enumCases(
            PathScope.serializer(),
            mapOf(PathScope.Workspace to "workspace", PathScope.Rootfs to "rootfs", PathScope.Device to "device"),
        )
        enumCases(
            AccessKind.serializer(),
            mapOf(AccessKind.Read to "read", AccessKind.Write to "write"),
        )
    }

    @Test
    fun `tool enums keep their serial names`() {
        enumCases(
            ModelFamily.serializer(),
            mapOf(
                ModelFamily.Claude to "claude",
                ModelFamily.Gpt to "gpt",
                ModelFamily.Gemini to "gemini",
                ModelFamily.DeepSeek to "deepseek",
                ModelFamily.Glm to "glm",
                ModelFamily.Kimi to "kimi",
                ModelFamily.Qwen to "qwen",
                ModelFamily.MiniMax to "minimax",
                ModelFamily.Doubao to "doubao",
                ModelFamily.Other to "other",
            ),
        )
        enumCases(
            EditStyle.serializer(),
            mapOf(EditStyle.StrReplace to "str_replace", EditStyle.ApplyPatch to "apply_patch"),
        )
    }

    // ---- 存储包裹层与杂项类型 ----

    @Test
    fun `event record and log header keep their wire shape`() {
        val record = EventRecord(seq = 7, atMs = 1_000, event = Note(NoteLevel.Info, "hello"))
        roundTrips(EventRecord.serializer(), record)
        assertWire(EventRecord.serializer(), record, """{"seq":7,"atMs":1000,"event":{"type":"note","level":"info","text":"hello"}}""")

        val header = LogHeader(formatVersion = 1, sessionId = SessionId("s1"), createdAtMs = 42)
        roundTrips(LogHeader.serializer(), header)
        assertWire(LogHeader.serializer(), header, """{"formatVersion":1,"sessionId":"s1","createdAtMs":42}""")
        // parent = null 不落盘；有值才写
        val orphan = AgentJson.encodeToString(LogHeader.serializer(), header)
        assertFalse("parent 不应出现：$orphan", "parent" in orphan)
        val child = header.copy(parent = SessionId("s0"))
        assertWire(LogHeader.serializer(), child, """{"formatVersion":1,"sessionId":"s1","createdAtMs":42,"parent":"s0"}""")
    }

    @Test
    fun `tool result omits defaults and writes the full shape when set`() {
        val bare = ToolResult(content = emptyList())
        roundTrips(ToolResult.serializer(), bare)
        assertWire(ToolResult.serializer(), bare, """{"content":[]}""")

        val full = ToolResult(
            content = listOf(TextPart("preview")),
            isError = true,
            artifact = "runs/1.txt",
            fullChars = 300,
        )
        assertWire(ToolResult.serializer(), full, """{"content":[{"type":"text","text":"preview"}],"isError":true,"artifact":"runs/1.txt","fullChars":300}""")
    }

    @Test
    fun `permission ask omits empty sections`() {
        val bare = PermissionAsk(tool = "Bash", summary = "run ls")
        roundTrips(PermissionAsk.serializer(), bare)
        assertWire(PermissionAsk.serializer(), bare, """{"tool":"Bash","summary":"run ls"}""")
    }

    @Test
    fun `command access omits null program and args`() {
        val bare = CommandAccess("ls -la")
        roundTrips(CommandAccess.serializer(), bare)
        assertWire(CommandAccess.serializer(), bare, """{"raw":"ls -la"}""")

        val full = CommandAccess("ls -la", program = "ls", args = listOf("-la"))
        assertWire(CommandAccess.serializer(), full, """{"raw":"ls -la","program":"ls","args":["-la"]}""")
    }

    @Test
    fun `exec handle omits null pid and log path`() {
        val bare = ExecHandle("h1")
        roundTrips(ExecHandle.serializer(), bare)
        assertWire(ExecHandle.serializer(), bare, """{"id":"h1"}""")

        val full = ExecHandle("h1", pid = 42, logPath = GuestPath("/tmp/x.log"))
        assertWire(ExecHandle.serializer(), full, """{"id":"h1","pid":42,"logPath":"/tmp/x.log"}""")
    }

    @Test
    fun `guest path is a bare string on the wire`() {
        assertEquals("\"/workspace/a\"", AgentJson.encodeToString(GuestPath.serializer(), GuestPath("/workspace/a")))
        assertEquals(GuestPath("/workspace/a"), AgentJson.decodeFromString(GuestPath.serializer(), "\"/workspace/a\""))
    }

    @Test
    fun `guest path rejects relative paths`() {
        assertTrue(throws { GuestPath("relative/path") } is IllegalArgumentException)
        // 从 JSON 读到相对路径同样失败：value class 反序列化要过构造器的 require
        assertTrue(throws { AgentJson.decodeFromString(GuestPath.serializer(), "\"a/b\"") } is IllegalArgumentException)
    }

    // ---- 前向兼容 ----

    @Test
    fun `unknown fields in event json are ignored`() {
        // 同一主版本内旧代码读新字段：忽略即可（字段只加不改不删，FORMAT.md）
        val json = """{"type":"note","level":"info","text":"hello","future_field":1}"""
        assertEquals(Note(NoteLevel.Info, "hello"), AgentJson.decodeFromString(SessionEvent.serializer(), json))
    }

    @Test
    fun `unknown event discriminator fails to decode`() {
        // 未知事件类型目前直接失败；存储层按文件头的主版本拒绝更新格式，见 FORMAT.md
        val thrown = throws {
            AgentJson.decodeFromString(SessionEvent.serializer(), """{"type":"not_a_real_event"}""")
        }
        assertTrue("实际抛出：$thrown", thrown is SerializationException)
    }

    /** kotlin.test 不在测试类路径，用一个 try/catch 断言「必须抛异常」 */
    private fun throws(block: () -> Unit): Throwable = try {
        block()
        throw AssertionError("期望抛异常，但没有")
    } catch (t: Throwable) {
        if (t is AssertionError) throw t else t
    }
}
