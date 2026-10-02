package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.EscapeAction
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.escapeAction
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
