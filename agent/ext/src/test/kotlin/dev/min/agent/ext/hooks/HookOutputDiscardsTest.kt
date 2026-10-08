package dev.min.agent.ext.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「丢弃表」的逐事件钉子。期望值照 CC 原文手写，**不从 HOOK_OUTPUT_DISCARDS 推导**——否则拿掉表里一项，
 * 期望跟着变，测试永远绿。
 */
class HookOutputDiscardsTest {
    /** 一份把所有会被丢的字段都写上的输出：decision / reason / continue / stopReason / systemMessage。 */
    private val everything = """{"decision":"block","reason":"r","continue":false,"stopReason":"s","systemMessage":"m"}"""

    private class Kept(val decision: HookDecision, val reason: String?, val systemMessage: String?)

    // 十个事件原文里 continue / stopReason 一律被丢，故统一断言 shouldContinue=true 且无 stopReason
    private val expectations = mapOf(
        HookEvent.Setup to Kept(HookDecision.Neutral, null, null),
        HookEvent.InstructionsLoaded to Kept(HookDecision.Neutral, null, null),
        HookEvent.MessageDisplay to Kept(HookDecision.Neutral, null, null),
        HookEvent.StopFailure to Kept(HookDecision.Neutral, null, null),
        HookEvent.PostCompact to Kept(HookDecision.Neutral, null, null),
        // 能阻断的：决定与理由保留
        HookEvent.TaskCreated to Kept(HookDecision.Block, "r", "m"),
        HookEvent.PreCompact to Kept(HookDecision.Block, "r", null),
        // ConfigChange：阻断保留，理由接受但不展示，通知丢弃
        HookEvent.ConfigChange to Kept(HookDecision.Block, null, null),
        // 无决策控制、但原文只点名丢 continue 的：其余照收
        HookEvent.DirectoryAdded to Kept(HookDecision.Neutral, "r", "m"),
        HookEvent.FileChanged to Kept(HookDecision.Neutral, "r", "m"),
    )

    @Test fun `十个事件各自按原文丢弃输出字段`() {
        expectations.forEach { (event, kept) ->
            val v = interpreted(event, everything)
            assertEquals("$event decision", kept.decision, v.decision)
            assertEquals("$event reason", kept.reason, v.reason?.text)
            assertEquals("$event systemMessage", kept.systemMessage, v.systemMessage?.text)
            assertTrue("$event continue 被丢弃", v.shouldContinue)
            assertNull("$event stopReason 被丢弃", v.stopReason)
        }
    }

    @Test fun `MessageDisplay 只留 displayContent`() {
        val v = interpreted(HookEvent.MessageDisplay, """{"hookSpecificOutput":{"hookEventName":"MessageDisplay","displayContent":"plain"},"systemMessage":"m","continue":false}""")
        assertEquals("plain", v.specificOutput?.get("displayContent")?.toString()?.trim('"'))
        assertNull(v.systemMessage); assertTrue(v.shouldContinue)
    }

    @Test fun `TaskCreated 的 exit 2 取消建任务并把 stderr 当消息`() {
        val v = interpreted(HookEvent.TaskCreated, "", 2, "bad subject")
        assertEquals(HookDecision.Block, v.decision); assertEquals("bad subject", v.reason?.text)
    }

    @Test fun `ConfigChange 的 exit 2 照样阻断但不带任何消息`() {
        val v = interpreted(HookEvent.ConfigChange, "", 2, "no way")
        assertEquals(HookDecision.Block, v.decision); assertNull(v.reason); assertNull(v.systemMessage)
    }

    @Test fun `Setup InstructionsLoaded StopFailure 退出码与输出校验错误都被忽略`() {
        listOf(HookEvent.Setup, HookEvent.InstructionsLoaded, HookEvent.StopFailure).forEach { event ->
            listOf(1, 3).forEach { code ->
                assertNull("$event exit $code", interpreted(event, "oops", code, "stderr text").nonBlockingError)
            }
        }
        // 对照：SessionEnd 原文是「stderr 给用户看」，错误保留
        assertNotNull(interpreted(HookEvent.SessionEnd, "oops", 1, "stderr text").nonBlockingError)
        // 没跑起来不是「退出码」，仍要报
        val missing = interpret(HookEvent.Setup, HandlerRun(handler(HookEvent.Setup), 127, "", "not found"))
        assertTrue(missing.failedToStart); assertNotNull(missing.nonBlockingError)
    }

    @Test fun `表里没有的事件不受影响`() {
        val v = interpreted(HookEvent.Stop, everything)
        assertEquals(HookDecision.Block, v.decision); assertFalse(v.shouldContinue); assertEquals("m", v.systemMessage?.text)
    }
}
