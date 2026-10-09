package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import dev.min.agent.testkit.*

/** 工具结果对会话的副作用由内核落盘；alwaysAsk 先于权限引擎；「进计划模式之前的模式」由折叠记下 */
class SessionEffectsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val id = SessionId("s1")

    /** 每一次追加的那批事件：用来断言「结果与设置是同一次追加」，而不只是日志里相邻 */
    private val batches = Collections.synchronizedList(mutableListOf<List<SessionEvent>>())
    private val recording = object : SessionStore {
        override suspend fun create(header: LogHeader) = record(store.create(header))
        override suspend fun open(id: SessionId) = store.peek(id)?.let(::record)
        override suspend fun list() = store.list()

        private fun record(log: SessionLog): SessionLog = object : SessionLog by log {
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                batches += events
                return log.append(events, durability)
            }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }
    private fun call(id: String, tool: String) = ToolCallPart(CallId(id), tool, JsonObject(emptyMap()))
    private fun text(t: String) = listOf(TextPart(t))

    private suspend fun newSession(model: ScriptedModel, tools: List<Tool<*>>, mode: PermissionMode) = SessionRuntime.create(
        recording,
        LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0),
        SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode),
        testDeps(model, tools),
        scope,
    )

    private suspend fun records(): List<EventRecord> = store.peek(id)!!.read().toList()

    private suspend fun assertLogIsTruth(runtime: SessionRuntime) {
        val log = store.peek(id)!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    @Test
    fun `a declared mode change lands with the outcome and governs the next call`() = run {
        var wrote = 0
        val switch = FakeTool("Switch", ToolTraits(readOnly = true)) {
            ToolResult(text("切到计划模式"), effects = listOf(SessionEffect.ModeChange(PermissionMode.Plan)))
        }
        val write = FakeTool("Write", WRITES) { wrote++; ToolResult(text("写好了")) }
        val model = ScriptedModel(
            reply(call("c1", "Switch"), call("c2", "Write"), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = newSession(model, listOf(switch, write), PermissionMode.AcceptEdits)
        rt.send(ClientMsgId("u1"), text("先计划"))
        val waiting = rt.state.first { it.phaseOf(CallId("c2")) is CallPhase.AwaitingApproval }
        assertEquals(0, wrote)
        rt.answerApproval((waiting.phaseOf(CallId("c2")) as CallPhase.AwaitingApproval).approvalId, ApprovalChoice.Deny("计划模式先别改"))
        rt.awaitIdle()

        val s = rt.state.value
        assertEquals(PermissionMode.Plan, s.settings.mode)
        assertEquals(PermissionMode.AcceptEdits, s.settings.modeBeforePlan)
        // 第二个调用按新模式询问，用户拒绝后不执行，不能沿用切模式之前的自动编辑权限。
        assertEquals(0, wrote)
        val deniedParts = model.requests[1].messages.last().parts
        val denied = deniedParts[1] as ToolResultPart
        assertTrue(denied.isError)
        assertFalse((denied.content.single() as TextPart).text.contains("计划模式"))
        assertEquals(TextPart("用户拒绝了你刚才的 Write 调用，并留言：计划模式先别改"), deniedParts[2])

        // 结果与设置是同一次追加，设置紧跟在结果之后
        val batch = batches.single { b -> b.any { (it as? ToolOutcome)?.callId == CallId("c1") } }
        assertEquals(listOf(ToolOutcome::class, SettingsChanged::class), batch.map { it::class })
        assertEquals(SettingsChanged(mode = PermissionMode.Plan), batch[1])
        assertEquals(1, records().count { it.event is ApprovalRequested })
        assertLogIsTruth(rt)
    }

    @Test
    fun `alwaysAsk asks even in bypass and a denial keeps the tool from running`() = run {
        var ran = 0
        val gated = FakeTool("Gated", ToolTraits(readOnly = true, alwaysAsk = true)) { ran++; ToolResult(text("跑了")) }
        val model = ScriptedModel(
            reply(call("c1", "Gated"), stop = StopReason.ToolUse),
            reply(call("c2", "Gated"), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = newSession(model, listOf(gated), PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), text("跑两次"))

        suspend fun answer(callId: String, choice: ApprovalChoice) {
            val waiting = rt.state.first { it.phaseOf(CallId(callId)) is CallPhase.AwaitingApproval }
            rt.answerApproval((waiting.phaseOf(CallId(callId)) as CallPhase.AwaitingApproval).approvalId, choice)
        }
        answer("c1", ApprovalChoice.Deny("先别跑"))
        answer("c2", ApprovalChoice.Once)
        rt.awaitIdle()

        assertEquals("拒绝的那次不执行，批准的那次执行", 1, ran)
        assertEquals(2, records().count { it.event is ApprovalRequested })
        assertLogIsTruth(rt)
    }

    @Test
    fun `effort can be cleared back to uncontrolled, and clearing nothing writes nothing`() = run {
        val rt = newSession(ScriptedModel(), emptyList(), PermissionMode.Default)
        rt.changeSettings(effort = dev.min.agent.protocol.Effort.High)
        rt.changeSettings(clearEffort = true)
        assertNull(rt.state.value.settings.effort)
        val written = batches.flatten().filterIsInstance<SettingsChanged>()
        rt.changeSettings(clearEffort = true)
        assertEquals("已经是不控制时不再写事件", written.size, batches.flatten().filterIsInstance<SettingsChanged>().size)
        val log = store.peek(id)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test
    fun `the mode before plan is remembered only when entering plan`() {
        val header = LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0)
        fun settingsAfter(created: PermissionMode, vararg events: SessionEvent): SessionSettings {
            val all = listOf(SessionCreated(GuestPath("/w"), TEST_MODEL, created)) + events
            return fold(header, all.mapIndexed { i, e -> EventRecord(i + 1L, 0, e) }).settings
        }
        assertNull(settingsAfter(PermissionMode.Default).modeBeforePlan)
        assertNull("一开始就在计划模式：退出时用 Default", settingsAfter(PermissionMode.Plan).modeBeforePlan)
        val entered = settingsAfter(PermissionMode.Default, SettingsChanged(mode = PermissionMode.AcceptEdits), SettingsChanged(mode = PermissionMode.Plan))
        assertEquals(PermissionMode.AcceptEdits, entered.modeBeforePlan)
        assertEquals(
            "在计划模式里再切计划模式不覆盖",
            PermissionMode.BypassPermissions,
            settingsAfter(PermissionMode.BypassPermissions, SettingsChanged(mode = PermissionMode.Plan), SettingsChanged(mode = PermissionMode.Plan)).modeBeforePlan,
        )
        assertEquals(
            "离开计划模式后保留，直到下次进入时覆盖",
            PermissionMode.Auto,
            settingsAfter(
                PermissionMode.Auto,
                SettingsChanged(mode = PermissionMode.Plan),
                SettingsChanged(mode = PermissionMode.Default),
                SettingsChanged(effort = null),
            ).modeBeforePlan,
        )
        assertEquals(
            PermissionMode.Default,
            settingsAfter(
                PermissionMode.Auto,
                SettingsChanged(mode = PermissionMode.Plan),
                SettingsChanged(mode = PermissionMode.Default),
                SettingsChanged(mode = PermissionMode.Plan),
            ).modeBeforePlan,
        )
    }
}
