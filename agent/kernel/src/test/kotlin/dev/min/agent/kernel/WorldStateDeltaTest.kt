package dev.min.agent.kernel

import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import dev.min.agent.store.LOG_FORMAT_VERSION
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * 世界状态增量（docs/agent/04-子系统设计.md §4.5）：日期跨天、权限模式、模型变了，只在下一条消息末尾追加一段提醒，
 * 系统提示与之前的消息一个字不动（缓存前缀不断）；没变就什么都不加。每个测试结尾都做 `fold(log) == 运行时状态`
 */
class WorldStateDeltaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 东八区：UTC 的 16:00 已经是第二天，测的是「按宿主时区算日期」 */
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = AtomicLong(at("2026-10-10T02:00:00Z"))
    private var counter = 0

    private fun at(instant: String) = Instant.parse(instant).toEpochMilli()

    private class Env(val hub: SessionHub, val store: FileSessionStore, val model: ScriptedModel)

    private suspend fun withHub(replies: Int, block: suspend (Env) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        val model = ScriptedModel(*Array(replies) { reply(TextPart("ok")) })
        try {
            val deps = KernelDeps(clock = { now.get() }, ids = { "${it}_${ids.incrementAndGet()}" },
                models = { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), model) }, tools = emptyList(),
                host = HostPorts(FakeFs(), FakeSearch(), FakeExec()) { FakeArtifacts() }, platform = "test", zone = zone)
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            block(Env(SessionHub(store, deps, scope), store, model))
        } finally { scope.cancel() }
    }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun assertReplays(env: Env, rt: SessionRuntime) {
        val log = env.store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private suspend fun events(env: Env, rt: SessionRuntime) = env.store.peek(rt.sessionId)!!.read().toList().map { it.event }

    /** 最后一条用户消息里的 system-reminder 文字 */
    private fun ModelRequest.lastReminders(): List<String> =
        messages.last { it.role == Role.User }.parts.filterIsInstance<TextPart>().map { it.text }.filter { "<system-reminder>" in it }

    private fun ModelRequest.allText() = messages.flatMap { it.parts }.filterIsInstance<TextPart>().joinToString("\n") { it.text }

    @Test
    fun `nothing changed, nothing is added and no event is written`() = run {
        withHub(2) { env ->
            val rt = env.hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)
            rt.say("a")
            rt.say("b")
            env.model.requests.forEach { assertFalse(it.allText(), "update:" in it.allText()) }
            assertTrue(events(env, rt).none { it is WorldNoted })
            assertNotNull("第一次提升静默记下了基准", rt.state.value.world)
            assertReplays(env, rt)
        }
    }

    @Test
    fun `crossing midnight in the host zone adds one date note at the end and keeps the prefix`() = run {
        withHub(3) { env ->
            val rt = env.hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)
            rt.say("a")
            // UTC 16:30 = 东八区次日 00:30
            now.set(at("2026-10-10T16:30:00Z"))
            rt.say("b")
            rt.say("c")
            val (first, second, third) = env.model.requests
            assertEquals("系统提示一个字不动", first.system, second.system)
            assertEquals("之前的消息一个字不动", first.messages, second.messages.take(first.messages.size))
            val note = second.lastReminders().single()
            assertTrue(note, note.contains("today is 2026-10-11"))
            assertTrue("同一天再说话不再提醒：${third.lastReminders()}", third.lastReminders().isEmpty())
            assertEquals(1, events(env, rt).count { it is WorldNoted })
            assertReplays(env, rt)
        }
    }

    @Test
    fun `switching into plan mode and back is told once each way`() = run {
        withHub(4) { env ->
            val rt = env.hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)
            rt.say("a")
            assertNull(rt.changeSettings(mode = PermissionMode.Plan))
            rt.say("b")
            rt.say("c")
            assertNull(rt.changeSettings(mode = PermissionMode.Default))
            rt.say("d")
            val notes = env.model.requests.map { it.lastReminders() }
            assertTrue(notes[0].isEmpty())
            assertTrue(notes[1].single(), notes[1].single().contains("plan mode is on"))
            assertTrue(notes[2].isEmpty())
            assertTrue(notes[3].single(), notes[3].single().contains("plan mode is off") && notes[3].single().contains("default"))
            assertReplays(env, rt)
        }
    }

    @Test
    fun `a session that starts in plan mode says so on its first message`() = run {
        withHub(1) { env ->
            val rt = env.hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Plan)
            rt.say("a")
            val note = env.model.requests.single().lastReminders().single()
            assertTrue(note, note.contains("plan mode is on"))
            assertReplays(env, rt)
        }
    }

    @Test
    fun `switching the model is told with both ids, the first message stays silent`() = run {
        withHub(2) { env ->
            val rt = env.hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)
            rt.say("a")
            assertNull(rt.changeSettings(model = ModelRef("test", "other")))
            rt.say("b")
            val note = env.model.requests[1].lastReminders().single()
            assertTrue(note, note.contains("now runs on other") && note.contains("scripted"))
            assertReplays(env, rt)
        }
    }

    @Test
    fun `the delta is computed from the facts the model was last told`() {
        val base = SessionState(
            header = LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0),
            created = SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default),
            lastSeq = 0,
            settings = SessionSettings(GuestPath("/w"), TEST_MODEL, PermissionMode.Default, null),
            inbox = emptyList(), seenInputs = emptySet(), history = emptyList(), toolResults = emptyMap(), turn = null,
            usage = Usage(),
        )
        assertNull("与系统提示的日期相同、默认模式、没记过模型：不提醒", worldDelta(base, "2026-10-10", "2026-10-10"))
        val told = base.copy(world = WorldFacts("2026-10-11", PermissionMode.Default, "scripted"))
        assertNull("已经告知过新日期，不再按系统提示的旧日期比", worldDelta(told, "2026-10-10", "2026-10-11"))
        val next = worldDelta(told, "2026-10-10", "2026-10-12")!!
        assertEquals(WorldNoted("2026-10-12", PermissionMode.Default, "scripted", next.reminder), next)
        val accept = worldDelta(told.copy(settings = told.settings.copy(mode = PermissionMode.AcceptEdits)), "2026-10-10", "2026-10-11")!!
        assertTrue(accept.reminder, accept.reminder.contains("acceptEdits") && "plan" !in accept.reminder)
    }
}
