package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.EscapeAction
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.escapeAction
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 停止键（Esc 三档）的现行为（M2-B 的 B0）。
 *
 * 判档函数 [escapeAction] 是纯函数，[ClaudeCodeEscapeTest] 已经钉了代表性的几格；
 * 这里补两样：**8 格真值表一次钉全**（B2 的 `composerKeys` 对拍要用），以及 manager 层
 * 「判出哪一档 → 实际做了什么」里 [ClaudeCodeManagerCharacterizationTest] 没覆盖的两档
 * （Nothing 不写任何帧、InterruptThenQueued 不撤回已发的那条）。
 * Withdraw 与 Interrupt 的 manager 层行为在 CharacterizationTest 里已有（interrupt_before_output / interrupt_midturn）。
 */
class ClaudeCodeEscapeBehaviorTest {

    @Test
    fun `truth table over busy, hasQueued and turnProduced`() {
        // (busy, hasQueued, turnProduced) -> 档位。排队优先于撤回；没在跑一律什么都不做
        val table = listOf(
            Triple(false, false, false) to EscapeAction.Nothing,
            Triple(false, false, true) to EscapeAction.Nothing,
            Triple(false, true, false) to EscapeAction.Nothing,
            Triple(false, true, true) to EscapeAction.Nothing,
            Triple(true, false, false) to EscapeAction.Withdraw,
            Triple(true, false, true) to EscapeAction.Interrupt,
            Triple(true, true, false) to EscapeAction.InterruptThenQueued,
            Triple(true, true, true) to EscapeAction.InterruptThenQueued,
        )
        table.forEach { (input, expected) ->
            val (busy, queued, produced) = input
            assertEquals(
                "busy=$busy hasQueued=$queued turnProduced=$produced",
                expected,
                escapeAction(busy = busy, hasQueued = queued, turnProduced = produced),
            )
        }
    }

    @Test
    fun `Nothing - pressing stop on an idle session writes no interrupt frame`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.awaitState("用量读回") { it.contextTokens != null }
            val before = h.process.written.size
            h.manager.interrupt()
            delay(300)
            val extra = h.process.written.drop(before).filter {
                it["type"]?.jsonPrimitive?.content == "control_request" &&
                    it["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.content == "interrupt"
            }
            assertTrue("闲置时按停止不该发 interrupt：$extra", extra.isEmpty())
        }
    }

    @Test
    fun `InterruptThenQueued - the message already sent stays in the transcript and the draft store, only the CLI queue is cancelled`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val sessionId = h.manager.state.value.sessionId!!
            h.process.holdTurns = true
            h.manager.send("first")
            h.awaitState("busy") { it.busy }
            h.manager.send("second")
            h.awaitCondition("第二条交棒") {
                h.process.written.count { it["type"]?.jsonPrimitive?.content == "user" } == 2
            }

            h.manager.interrupt()
            h.awaitCondition("interrupt 写出") {
                h.process.written.any {
                    it["type"]?.jsonPrimitive?.content == "control_request" &&
                        it["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.content == "interrupt"
                }
            }
            val interrupt = h.process.written.first {
                it["type"]?.jsonPrimitive?.content == "control_request" &&
                    it["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.content == "interrupt"
            }["request"]!!.jsonObject
            // CLI 侧的排队被 cancel_queued 清掉；我们留的副本由发送队列攥回去，等本轮收尾再发
            assertEquals("true", interrupt["cancel_queued"]?.jsonPrimitive?.content)
            // 不是撤回：开启本轮的那条还在对话流里，也没有被退进草稿
            val users = h.manager.state.value.items.filterIsInstance<ChatItem.UserText>().map { it.text }
            assertTrue("first 应仍在流里：$users", "first" in users)
            assertEquals("", h.drafts.load(sessionId).text)
            h.process.releaseTurn()
        }
    }

    // ---------------------------------------------------------------- interruptReturningQueued（■ 的默认档，设计 §10.2 拍板 2）

    private fun ManagerHarness.userFrames() = process.written.count { it["type"]?.jsonPrimitive?.content == "user" }
    private fun ManagerHarness.interruptRequests() = process.written.filter {
        it["type"]?.jsonPrimitive?.content == "control_request" &&
            it["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.content == "interrupt"
    }.map { it["request"]!!.jsonObject }

    @Test
    fun `interruptReturningQueued - queued messages leave the transcript, come back as one draft and are not resent`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val sessionId = h.manager.state.value.sessionId!!
            h.process.holdTurns = true
            h.manager.send("first")
            h.awaitState("busy") { it.busy }
            h.manager.send("second")
            h.manager.send("third")
            h.awaitCondition("两条追加都交棒") { h.userFrames() == 3 }
            val got = CopyOnWriteArrayList<ComposerDraft>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.manager.withdrawnMessages.collect { got += it } }

            h.manager.interruptReturningQueued()

            h.awaitCondition("interrupt 写出") { h.interruptRequests().isNotEmpty() }
            // CLI 侧的排队由 cancel_queued 清掉；我们这边不再留副本，否则收尾时会被原样重发
            assertEquals("true", h.interruptRequests().single()["cancel_queued"]?.jsonPrimitive?.content)
            h.awaitCondition("退回的草稿到了") { got.isNotEmpty() }
            delay(200)
            assertEquals("多条排队合成一次退回：$got", 1, got.size)
            assertEquals("second\n\nthird", got.single().text)
            val users = h.manager.state.value.items.filterIsInstance<ChatItem.UserText>()
            assertEquals("开启本轮的那条留着，排队的摘掉：${users.map { it.text }}", listOf("first"), users.map { it.text })
            assertTrue(users.none { it.queued })
            h.process.releaseTurn()
            h.awaitTurnEnd()
            delay(200)
            assertEquals("收尾后不该再把排队的发一遍", 3, h.userFrames())
            assertEquals("有订阅者时不落盘", "", h.drafts.load(sessionId).text)
            collector.cancel()
        }
    }

    @Test
    fun `interruptReturningQueued - with nobody listening the returned text lands in the draft store`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val sessionId = h.manager.state.value.sessionId!!
            h.process.holdTurns = true
            h.manager.send("first")
            h.awaitState("busy") { it.busy }
            h.manager.send("second")
            h.awaitCondition("追加交棒") { h.userFrames() == 2 }

            h.manager.interruptReturningQueued()

            assertEquals("second", h.drafts.load(sessionId).text)
            h.process.releaseTurn()
            h.awaitTurnEnd()
        }
    }

    @Test
    fun `interruptReturningQueued - images from several queued messages are merged and renumbered`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.holdTurns = true
            h.manager.send("first")
            h.awaitState("busy") { it.busy }
            h.manager.send("a", listOf(ClaudeCodeImage("image/png", "AAA")))
            h.manager.send("", listOf(ClaudeCodeImage("image/jpeg", "BBB")))
            h.awaitCondition("追加交棒") { h.userFrames() == 3 }
            val draft = async(start = CoroutineStart.UNDISPATCHED) { h.manager.withdrawnMessages.first() }

            h.manager.interruptReturningQueued()

            val merged = withTimeout(5_000) { draft.await() }
            assertEquals("a", merged.text)
            assertEquals(listOf("AAA", "BBB"), merged.images.map { it.base64 })
            assertEquals("图片名按合并后的次序重排", listOf("图片 1", "图片 2"), merged.images.map { it.name })
            h.process.releaseTurn()
            h.awaitTurnEnd()
        }
    }

    @Test
    fun `interruptReturningQueued - on an idle session nothing is written and nothing comes back`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.awaitState("用量读回") { it.contextTokens != null }
            val got = CopyOnWriteArrayList<ComposerDraft>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.manager.withdrawnMessages.collect { got += it } }
            h.manager.interruptReturningQueued()
            delay(300)
            assertTrue(h.interruptRequests().isEmpty())
            assertTrue(got.isEmpty())
            collector.cancel()
        }
    }


    @Test
    fun `interruptReturningQueued - with nothing queued the ordinary tiers apply, a turn that produced nothing is withdrawn as before`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val sessionId = h.manager.state.value.sessionId!!
            h.process.holdTurns = true
            h.manager.send("only")
            h.awaitState("busy") { it.busy }
            h.manager.interruptReturningQueued()
            h.awaitCondition("interrupt 写出") { h.interruptRequests().isNotEmpty() }
            // 与 interrupt() 完全一致：没有排队时不多做事，开启本轮的那条走撤回档退回草稿
            assertEquals("only", h.drafts.load(sessionId).text)
            assertTrue(h.manager.state.value.items.none { it is ChatItem.UserText && it.text == "only" })
            h.process.releaseTurn()
            h.awaitTurnEnd()
        }
    }
}
