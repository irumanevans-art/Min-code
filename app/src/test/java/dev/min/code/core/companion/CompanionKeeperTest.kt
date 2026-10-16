package dev.min.code.core.companion

import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.CompanionSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整理器的时序：空闲合批、会话离开立即整理、失败重试与放弃、关开关、没选模型 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionKeeperTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ref = SessionRef(EngineKind.ClaudeCode, "s1")
    private val timing = CompanionKeeper.Timing(idleMs = 1_000, goneMs = 10, retryMs = 5_000, maxAttempts = 3)
    private val ready = CompanionSettings(enabled = true, profileId = "p", modelId = "m")

    private inner class Rig(scope: TestScope, initial: CompanionSettings = ready) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val live = MutableStateFlow<List<LiveSessionSummary>>(emptyList())
        val config = MutableStateFlow(initial)
        val items = mutableListOf<ChatItem>()
        val prompts = mutableListOf<String>()
        val blocks = mutableListOf<String?>()
        var reply: () -> String = { """{"add":[{"kind":"life","text":"Has a cat"}]}""" }
        val store = CompanionStore(tmp.newFolder(), dispatcher)
        val keeper = CompanionKeeper(
            scope = scope.backgroundScope,
            live = live,
            peekItems = { r -> if (r == ref) items.toList() else null },
            config = config,
            rootfsChanges = MutableStateFlow(Unit),
            store = store,
            model = CompanionModel { _, _, _, user -> prompts += user; reply() },
            syncBlock = { blocks += it },
            serial = dispatcher,
            timing = timing,
            clock = { 1L },
        ).also { it.start() }

        private var n = 0

        /** 一轮：用户说一句、助手答一句，会话先忙后闲 */
        fun turn(user: String, scope: TestScope) {
            live.value = listOf(summary(busy = true))
            scope.runCurrent()
            items += ChatItem.UserText("u${n}", user)
            items += ChatItem.AssistantText("a${n++}", "reply to $user")
            live.value = listOf(summary(busy = false))
            scope.runCurrent()
        }

        fun summary(busy: Boolean) = LiveSessionSummary(ref, null, SessionStatus.Running, keepAlive = false, busy = busy)
    }

    /**
     * 走过所有计时器（空闲 1s、重试 5s × 2 都在内）。不用 advanceUntilIdle：整理器的协程都在 backgroundScope 里，
     * 它只剩后台任务时就停，计时器永远不到点
     */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun turns_within_the_idle_window_are_batched_into_one_call() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.turn("I miss my mom", this)
        advanceTimeBy(500)
        rig.turn("She lives far away", this)
        advanceTimeBy(900)
        runCurrent()
        assertEquals(0, rig.prompts.size) // 第二轮重置了空闲计时
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, rig.prompts.size)
        assertTrue(rig.prompts[0].contains("I miss my mom") && rig.prompts[0].contains("She lives far away"))
        assertEquals(listOf("Has a cat"), rig.store.memory.value.notes.map { it.text })

        // 游标推进了：下一轮只带新的两句
        rig.reply = { "{}" }
        rig.turn("Thanks", this)
        settle()
        assertEquals(2, rig.prompts.size)
        assertTrue(!rig.prompts[1].contains("I miss my mom") && rig.prompts[1].contains("Thanks"))
    }

    @Test
    fun session_leaving_live_flushes_without_waiting_for_idle() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.turn("rough day", this)
        rig.live.value = emptyList()
        runCurrent()
        advanceTimeBy(timing.goneMs + 1)
        runCurrent()
        assertEquals(1, rig.prompts.size)
    }

    @Test
    fun failures_retry_then_give_up_without_advancing_the_cursor() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.reply = { error("network down") }
        rig.turn("hello", this)
        settle()
        assertEquals(timing.maxAttempts, rig.prompts.size)
        assertNull(rig.store.cursor(ref.storageKey))
        assertTrue(rig.store.memory.value.notes.isEmpty())

        // 恢复后下一轮：放弃过的那几句重新切出来一起交
        rig.reply = { "{}" }
        rig.turn("again", this)
        settle()
        assertTrue(rig.prompts.last().contains("hello") && rig.prompts.last().contains("again"))
    }

    @Test
    fun unparseable_reply_counts_as_failure() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.reply = { "sorry, I can't" }
        rig.turn("hello", this)
        settle()
        assertEquals(timing.maxAttempts, rig.prompts.size)
    }

    @Test
    fun disabled_records_nothing_and_turning_off_drops_pending() = runTest {
        val rig = Rig(this, initial = ready.copy(enabled = false))
        runCurrent()
        rig.turn("secret", this)
        settle()
        assertEquals(0, rig.prompts.size)

        rig.config.value = ready
        runCurrent()
        rig.turn("pending one", this)
        rig.config.value = ready.copy(enabled = false)
        settle()
        assertEquals(0, rig.prompts.size)
    }

    @Test
    fun no_model_chosen_keeps_lines_for_later() = runTest {
        val rig = Rig(this, initial = ready.copy(profileId = null, modelId = null))
        runCurrent()
        rig.turn("first", this)
        settle()
        assertEquals(0, rig.prompts.size)
        assertNull(rig.store.cursor(ref.storageKey))

        rig.config.value = ready
        runCurrent()
        rig.turn("second", this)
        settle()
        assertTrue(rig.prompts.single().contains("first") && rig.prompts.single().contains("second"))
    }

    @Test
    fun block_follows_switch_and_memory() = runTest {
        val rig = Rig(this, initial = ready.copy(enabled = false))
        settle()
        assertEquals(listOf<String?>(null), rig.blocks)
        rig.config.value = ready
        settle()
        assertTrue(rig.blocks.last()!!.contains(COMPANION_BLOCK_START))
        rig.turn("I adopted a cat", this)
        settle()
        assertTrue(rig.blocks.last()!!.contains("Has a cat"))
        rig.config.value = ready.copy(enabled = false)
        settle()
        assertNull(rig.blocks.last())
    }
}
