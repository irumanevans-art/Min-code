package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 发送协调器本身的时序：交棒 / 确认 / 兜底。走真 Manager 的测试（Escape / Characterization）验的是
 * 「发生了什么」，那里的兜底超时是 15 秒 / 20 秒，等不起；这里把超时调到几百毫秒，直接钉住
 * 「谁先到谁算数」——打断兜底和正常收尾抢同一个轮次号，只能有一边生效。
 */
class ClaudeCodeSendCoordinatorTest {

    private val root = Files.createTempDirectory("scoord-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(ClaudeCodeManager.SessionState(status = SessionStatus.Running, sessionId = "s1"))
    private val frames = CopyOnWriteArrayList<String>()
    private var closing = false
    private var counter = 0

    private fun coordinator() = ClaudeCodeSendCoordinator(
        scope = scope,
        state = state,
        write = { frames += it },
        isClosing = { closing },
        drafts = ComposerDraftStore(File(root, "drafts")),
        newId = { "id${counter++}" },
        interruptTimeoutMs = TIMEOUT_MS,
        handoffTimeoutMs = TIMEOUT_MS,
    )

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun userFrames() = frames.filter { it.contains("\"type\":\"user\"") }
    private fun interruptFrames() = frames.filter { it.contains("\"interrupt\"") }
    private fun notes() = state.value.items.filterIsInstance<ChatItem.Note>().map { it.text }
    private fun queuedFlags() = state.value.items.filterIsInstance<ChatItem.UserText>().map { it.text to it.queued }

    @Test
    fun `a message on an idle session starts a turn at once`() {
        val c = coordinator()
        c.send("hello", emptyList(), relay = null)
        assertEquals(1, userFrames().size)
        assertTrue(state.value.busy)
        assertEquals(listOf("hello" to false), queuedFlags())
        assertTrue(c.hasTurnInFlight)
    }

    /** 追加：帧立刻出手，副本留着等 CLI 确认；确认之前 Esc 还能把它要回来 */
    @Test
    fun `a message on a busy session is handed off and stays queued until confirmed`() {
        val c = coordinator()
        c.send("first", emptyList(), relay = null)
        c.send("second", emptyList(), relay = null)
        assertEquals(2, userFrames().size)
        assertEquals(listOf("first" to false, "second" to true), queuedFlags())
        assertTrue(c.hasQueuedWork())

        c.confirmHandedOff()
        assertEquals(listOf("first" to false, "second" to false), queuedFlags())
        assertFalse(c.hasQueuedWork())
    }

    /** 启动中打的字先攥着；握手完 flush：第一条开新的一轮，其余追加，写出去的次序就是打字的次序 */
    @Test
    fun `held messages are flushed in typing order, the first one starting the turn`() {
        state.update { it.copy(status = SessionStatus.Starting) }
        val c = coordinator()
        c.send("one", emptyList(), relay = null)
        c.send("two", emptyList(), relay = null)
        assertTrue(frames.isEmpty())
        assertEquals(listOf("one" to true, "two" to true), queuedFlags())

        state.update { it.copy(status = SessionStatus.Running) }
        assertTrue(c.flushPendingSend())
        assertEquals(2, userFrames().size)
        assertTrue(userFrames()[0].contains("one"))
        assertTrue(userFrames()[1].contains("two"))
        assertTrue(state.value.busy)
        // 第一条开了本轮，摘掉排队标记；第二条是追加，等确认
        assertEquals(listOf("one" to false, "two" to true), queuedFlags())
    }

    /** 进程正在关：写出去只会喂给死进程，所以什么都不发，消息留在队列里等新进程 */
    @Test
    fun `nothing is flushed while the process is closing`() {
        state.update { it.copy(status = SessionStatus.Starting) }
        val c = coordinator()
        c.send("one", emptyList(), relay = null)
        closing = true
        assertFalse(c.flushPendingSend())
        assertTrue(frames.isEmpty())
        assertTrue(c.hasQueuedWork())

        closing = false
        assertTrue(c.flushPendingSend())
        assertEquals(1, userFrames().size)
    }

    @Test
    fun `an interrupt the CLI never answers force-clears busy after the timeout`() = runBlocking<Unit> {
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        state.update { it.copy(turnProduced = true) }
        c.interrupt()
        assertEquals(1, interruptFrames().size)
        assertTrue(state.value.busy)

        delay(TIMEOUT_MS * 3)
        assertFalse(state.value.busy)
        assertTrue(notes().contains("中断超时，已强制解除等待状态"))
        assertEquals(ClaudeTurnEnd.Kind.Interrupted, state.value.lastTurnEnd?.kind)
        assertFalse(c.hasTurnInFlight)
    }

    /** 撤回档（一个字都还没出）超时：记成撤回而不是打断，▶ 不该出现 */
    @Test
    fun `a withdraw the CLI never answers is recorded as withdrawn`() = runBlocking<Unit> {
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        c.interrupt()
        // 消息已经退回输入框：对话流里没有它，草稿盘里有
        assertTrue(state.value.items.none { it is ChatItem.UserText })
        delay(TIMEOUT_MS * 3)
        assertEquals(ClaudeTurnEnd.Kind.Withdrawn, state.value.lastTurnEnd?.kind)
    }

    /** result 先到：它已经把轮次号推进了，兜底的比较交换失败，不能再把后面那一轮的 busy 清掉 */
    @Test
    fun `a result that lands before the interrupt timeout wins`() = runBlocking<Unit> {
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        state.update { it.copy(turnProduced = true) }
        c.interrupt()
        assertEquals(c.interruptedTurn, c.endTurn())

        delay(TIMEOUT_MS * 3)
        assertTrue("兜底不该动手", state.value.busy)
        assertFalse(notes().contains("中断超时，已强制解除等待状态"))
    }

    /** 兜底发现还有排队的：busy 继续为真，并把攥着的接着发 */
    @Test
    fun `the interrupt fallback flushes what was waiting`() = runBlocking<Unit> {
        state.update { it.copy(status = SessionStatus.Running) }
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        state.update { it.copy(turnProduced = true) }
        // 打断之后、兜底之前又来了一条：交棒后被 interrupt 要回，攥在手里
        c.send("later", emptyList(), relay = null)
        c.interrupt()
        val before = userFrames().size

        delay(TIMEOUT_MS * 3)
        assertEquals(before + 1, userFrames().size)
        assertTrue(userFrames().last().contains("later"))
        assertTrue(state.value.busy)
    }

    @Test
    fun `the handoff watchdog clears busy when the CLI never picks the message up`() = runBlocking<Unit> {
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        c.send("extra", emptyList(), relay = null)
        val ended = c.endTurn()
        c.watchHandedOff(c.currentTurn)
        assertEquals(0, ended)

        delay(TIMEOUT_MS * 3)
        assertFalse(state.value.busy)
        assertTrue(notes().contains("追加的消息发出去了，但 CLI 一直没接手"))
        assertFalse(c.hasQueuedWork())
    }

    /** CLI 真的接手开了新一轮：轮次号变了，看门狗就不该再动手 */
    @Test
    fun `the handoff watchdog stays quiet when a new turn started`() = runBlocking<Unit> {
        val c = coordinator()
        c.send("work", emptyList(), relay = null)
        c.send("extra", emptyList(), relay = null)
        c.endTurn()
        c.watchHandedOff(c.currentTurn)
        c.endTurn()

        delay(TIMEOUT_MS * 3)
        assertTrue(state.value.busy)
        assertNull(notes().firstOrNull { it.contains("一直没接手") })
    }

    private companion object {
        const val TIMEOUT_MS = 150L
    }
}
