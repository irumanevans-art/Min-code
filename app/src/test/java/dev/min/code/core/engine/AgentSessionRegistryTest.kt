package dev.min.code.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.min.code.core.session.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 注册表的纯编排逻辑，用假引擎测。异步部分用真调度器，不引入 coroutines-test（会改产品的 build.gradle） */
class AgentSessionRegistryTest {

    private class FakeSession(
        override val ref: SessionRef,
        override val capabilities: EngineCapabilities,
    ) : AgentSession {
        override val state: StateFlow<AgentSessionState> = MutableStateFlow(AgentSessionState.initial(ref))
        override val stream: StateFlow<LiveStream> = MutableStateFlow(LiveStream.Empty)
        override val signals: SharedFlow<SessionSignal> = MutableSharedFlow()
        val submitted = mutableListOf<SessionCommand>()
        var closedWith: CloseReason? = null
        override suspend fun submit(command: SessionCommand): CommandResult {
            submitted += command
            return CommandResult.Accepted
        }
        override suspend fun searchFiles(query: String, limit: Int): List<String> = emptyList()
        override suspend fun close(reason: CloseReason) { closedWith = reason }
    }

    private class FakeEngine(
        override val kind: EngineKind,
        override val capabilities: EngineCapabilities,
    ) : AgentEngine {
        override val availability = MutableStateFlow<EngineAvailability>(EngineAvailability.Ready)
        override val live = MutableStateFlow<List<LiveSessionSummary>>(emptyList())
        override val catalog: SessionCatalog get() = error("not used")
        val sessions = mutableMapOf<String, FakeSession>()
        val counter = AtomicInteger()
        var failNext: Throwable? = null
        var closeAllReason: CloseReason? = null

        override suspend fun create(options: StartOptions): Result<AgentSession> {
            failNext?.let { failNext = null; return Result.failure(it) }
            val id = "${kind.name}-${counter.incrementAndGet()}"
            return Result.success(FakeSession(SessionRef(kind, id), capabilities).also { sessions[id] = it })
        }

        override suspend fun open(id: String): Result<AgentSession> {
            failNext?.let { failNext = null; return Result.failure(it) }
            return Result.success(sessions.getOrPut(id) { FakeSession(SessionRef(kind, id), capabilities) })
        }

        override suspend fun closeAll(reason: CloseReason) { closeAllReason = reason }
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val cc = FakeEngine(EngineKind.ClaudeCode, EngineCapabilities.ClaudeCode.copy(maxConcurrent = 2))
    private val min = FakeEngine(EngineKind.MinAgent, EngineCapabilities.MinAgent.copy(maxConcurrent = 2))
    private val registry = AgentSessionRegistry(listOf(cc, min), scope)
    private val opts = StartOptions(cwd = "/workspace")

    @After fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T = first(predicate)

    private fun ok(result: AttachResult): AgentSession = (result as AttachResult.Ok).session

    // ---------------------------------------------------------------- 创建与上限

    @Test
    fun `create attaches the new session and makes it active`() = run {
        val s = ok(registry.create(EngineKind.ClaudeCode, opts))
        assertEquals(s.ref, registry.activeRef.value)
        assertSame(s, registry.active.value)
        assertEquals(listOf(s), registry.attached.value)
    }

    @Test
    fun `create stops at the per-engine capacity and counts engines separately`() = run {
        ok(registry.create(EngineKind.ClaudeCode, opts))
        ok(registry.create(EngineKind.ClaudeCode, opts))
        assertEquals(AttachResult.AtCapacity(2), registry.create(EngineKind.ClaudeCode, opts))
        // 别的引擎不受影响
        ok(registry.create(EngineKind.MinAgent, opts))
    }

    @Test
    fun `an unavailable engine is refused with its availability`() = run {
        cc.availability.value = EngineAvailability.NeedsInstall
        assertEquals(AttachResult.Unavailable(EngineAvailability.NeedsInstall), registry.create(EngineKind.ClaudeCode, opts))
        assertTrue(registry.attached.value.isEmpty())
    }

    @Test
    fun `an engine failure surfaces as Failed and attaches nothing`() = run {
        cc.failNext = IllegalStateException("起不来")
        assertEquals(AttachResult.Failed("起不来"), registry.create(EngineKind.ClaudeCode, opts))
        assertTrue(registry.attached.value.isEmpty())
        assertNull(registry.active.value)
    }

    @Test
    fun `concurrent creates cannot overshoot the capacity`() = run {
        val results = (1..6).map { scope.async { registry.create(EngineKind.ClaudeCode, opts) } }.awaitAll()
        assertEquals(2, results.count { it is AttachResult.Ok })
        assertEquals(4, results.count { it is AttachResult.AtCapacity })
        assertEquals(2, registry.attached.value.size)
    }

    @Test
    fun `a background session that keeps the process alive occupies a slot even when not attached`() = run {
        min.live.value = listOf(
            summary(ref = SessionRef(EngineKind.MinAgent, "bg1"), keepAlive = true),
            summary(ref = SessionRef(EngineKind.MinAgent, "bg2"), keepAlive = true),
        )
        assertEquals(AttachResult.AtCapacity(2), registry.create(EngineKind.MinAgent, opts))
    }

    @Test
    fun `an idle unattached session does not occupy a slot`() = run {
        min.live.value = listOf(summary(ref = SessionRef(EngineKind.MinAgent, "idle"), keepAlive = false))
        ok(registry.create(EngineKind.MinAgent, opts))
        ok(registry.create(EngineKind.MinAgent, opts))
    }

    // ---------------------------------------------------------------- 打开与切换

    @Test
    fun `opening an attached session just switches to it without reopening the engine`() = run {
        val a = ok(registry.create(EngineKind.ClaudeCode, opts))
        val b = ok(registry.create(EngineKind.ClaudeCode, opts))
        assertSame(b, registry.active.value)
        assertSame(a, ok(registry.open(a.ref)))
        assertSame(a, registry.active.value)
        assertEquals("没有重复挂", 2, registry.attached.value.size)
    }

    @Test
    fun `opening a session over capacity is refused, unless a running background session is already holding that slot`() = run {
        ok(registry.create(EngineKind.ClaudeCode, opts))
        ok(registry.create(EngineKind.ClaudeCode, opts))
        assertEquals(AttachResult.AtCapacity(2), registry.open(SessionRef(EngineKind.ClaudeCode, "old")))

        val held = SessionRef(EngineKind.MinAgent, "held")
        min.live.value = listOf(summary(ref = held, keepAlive = true), summary(ref = SessionRef(EngineKind.MinAgent, "other"), keepAlive = true))
        assertEquals(AttachResult.AtCapacity(2), registry.open(SessionRef(EngineKind.MinAgent, "new")))
        assertEquals("占着位的会话再打开不算新增", held, ok(registry.open(held)).ref)
    }

    @Test
    fun `switchTo ignores sessions that are not attached`() = run {
        val a = ok(registry.create(EngineKind.ClaudeCode, opts))
        registry.switchTo(SessionRef(EngineKind.ClaudeCode, "ghost"))
        assertEquals(a.ref, registry.activeRef.value)
    }

    @Test
    fun `engine returning an already attached ref replaces instead of duplicating`() = run {
        val first = ok(registry.open(SessionRef(EngineKind.MinAgent, "s1")))
        registry.close(first.ref, CloseReason.Evicted)
        ok(registry.open(SessionRef(EngineKind.MinAgent, "s1")))
        assertEquals(1, registry.attached.value.size)
    }

    // ---------------------------------------------------------------- 关闭

    @Test
    fun `closing the active session falls back to the last remaining one`() = run {
        val a = ok(registry.create(EngineKind.ClaudeCode, opts))
        val b = ok(registry.create(EngineKind.ClaudeCode, opts))
        val c = ok(registry.create(EngineKind.MinAgent, opts))
        registry.close(c.ref, CloseReason.UserEnded)
        assertEquals(b.ref, registry.activeRef.value)
        assertSame(b, registry.active.value)
        assertEquals((c as FakeSession).closedWith, CloseReason.UserEnded)
        registry.close(b.ref, CloseReason.UserEnded)
        assertEquals(a.ref, registry.activeRef.value)
        registry.close(a.ref, CloseReason.UserEnded)
        assertNull(registry.activeRef.value)
        assertNull(registry.active.value)
    }

    @Test
    fun `closing a background session leaves the active one alone`() = run {
        val a = ok(registry.create(EngineKind.ClaudeCode, opts))
        val b = ok(registry.create(EngineKind.ClaudeCode, opts))
        registry.close(a.ref, CloseReason.UserEnded)
        assertEquals(b.ref, registry.activeRef.value)
    }

    @Test
    fun `closing a session that is not attached does nothing`() = run {
        registry.close(SessionRef(EngineKind.ClaudeCode, "ghost"), CloseReason.UserEnded)
        assertTrue(registry.attached.value.isEmpty())
    }

    @Test
    fun `closeAll clears attachment and hands the reason to every engine`() = run {
        ok(registry.create(EngineKind.ClaudeCode, opts))
        ok(registry.create(EngineKind.MinAgent, opts))
        registry.closeAll(CloseReason.StopAll)
        assertTrue(registry.attached.value.isEmpty())
        assertNull(registry.activeRef.value)
        assertEquals(CloseReason.StopAll, cc.closeAllReason)
        assertEquals(CloseReason.StopAll, min.closeAllReason)
    }

    // ---------------------------------------------------------------- 提交

    @Test
    fun `submit to an attached session goes straight to it`() = run {
        val s = ok(registry.create(EngineKind.MinAgent, opts)) as FakeSession
        val command = SessionCommand.Resume("c1", "o1")
        assertEquals(CommandResult.Accepted, registry.submit(s.ref, command))
        assertEquals(listOf<SessionCommand>(command), s.submitted)
    }

    @Test
    fun `submit to a session that is not open opens it through the engine without attaching it`() = run {
        val ref = SessionRef(EngineKind.MinAgent, "bg")
        val command = SessionCommand.Answer("c1", "bg/ap1", InteractionAnswer.Choose(ApprovalOption.ALLOW))
        assertEquals(CommandResult.Accepted, registry.submit(ref, command))
        assertEquals(listOf<SessionCommand>(command), min.sessions.getValue("bg").submitted)
        assertTrue("通知应答不该把会话塞进用户的界面", registry.attached.value.isEmpty())
        assertNull(registry.active.value)
    }

    @Test
    fun `submit to a session the engine cannot open is rejected with the reason`() = run {
        cc.failNext = IllegalStateException("会话已删除")
        val result = registry.submit(SessionRef(EngineKind.ClaudeCode, "gone"), SessionCommand.Resume("c", "o"))
        assertEquals(CommandResult.Rejected("会话已删除"), result)
    }

    // ---------------------------------------------------------------- live 合并

    @Test
    fun `live merges engines and marks the active and attached sessions`() = run {
        val s = ok(registry.create(EngineKind.ClaudeCode, opts))
        val other = SessionRef(EngineKind.MinAgent, "bg")
        cc.live.value = listOf(summary(ref = s.ref))
        min.live.value = listOf(summary(ref = other, busy = true))
        val merged = registry.live.awaitValue { it.size == 2 && it.any { x -> x.isActive } }
        assertEquals(setOf(s.ref, other), merged.map { it.ref }.toSet())
        assertTrue(merged.single { it.ref == s.ref }.isActive)
        assertTrue(merged.single { it.ref == s.ref }.attached)
        assertFalse(merged.single { it.ref == other }.isActive)
        assertFalse(merged.single { it.ref == other }.attached)
    }

    @Test
    fun `anyKeepAlive and anyBusy follow the merged live list`() = run {
        assertFalse(registry.anyKeepAlive.value)
        assertFalse(registry.anyBusy.value)
        min.live.value = listOf(summary(ref = SessionRef(EngineKind.MinAgent, "bg"), keepAlive = true, busy = true))
        registry.anyKeepAlive.awaitValue { it }
        registry.anyBusy.awaitValue { it }
        min.live.value = listOf(summary(ref = SessionRef(EngineKind.MinAgent, "bg"), keepAlive = false, busy = false, status = SessionStatus.Idle))
        registry.anyKeepAlive.awaitValue { !it }
        registry.anyBusy.awaitValue { !it }
    }

    @Test
    fun `an unknown engine kind fails loudly and duplicate engines are refused at construction`() {
        val lone = AgentSessionRegistry(listOf(cc), scope)
        try {
            lone.engine(EngineKind.Codex)
            error("应该抛")
        } catch (_: NoSuchElementException) {
        }
        try {
            AgentSessionRegistry(listOf(cc, cc), scope)
            error("应该抛")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `a registry with no engines is empty and quiet`() {
        val empty = AgentSessionRegistry(emptyList(), scope)
        assertTrue(empty.live.value.isEmpty())
        assertFalse(empty.anyKeepAlive.value)
    }
}
