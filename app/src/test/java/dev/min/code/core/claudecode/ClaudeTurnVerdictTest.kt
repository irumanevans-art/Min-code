package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一轮收尾的判定：result 帧 + Manager 记下的"谁打断 / 谁拒绝了哪一轮"标记 -> 结论。
 * 轮次号对不上的标记必须被忽略，否则上一轮遗留的标记会把下一轮误判成用户停的。
 */
class ClaudeTurnVerdictTest {

    private fun result(
        isError: Boolean = false,
        subtype: String = if (isError) "error_during_execution" else "success",
        text: String? = null,
    ) = ClaudeCodeEvent.Result(
        isError = isError, subtype = subtype, durationMs = null, numTurns = null,
        totalCostUsd = null, sessionId = null, resultText = text,
    )

    private fun judge(
        result: ClaudeCodeEvent.Result,
        endedTurn: Int = 3,
        interruptedTurn: Int? = null,
        interruptWithdrew: Boolean = false,
        deniedTurn: Int? = null,
    ) = judgeTurnEnd(result, endedTurn, interruptedTurn, interruptWithdrew, deniedTurn, atMs = 7L)

    @Test
    fun `a normal result completes without a note`() {
        val v = judge(result())
        assertEquals(ClaudeTurnEnd(ClaudeTurnEnd.Kind.Completed, 7L), v.turnEnd)
        assertFalse(v.interrupted)
        assertFalse(v.deniedStop)
        assertFalse(v.noteError)
    }

    @Test
    fun `an error result fails with the CLI's words and asks for a note`() {
        val v = judge(result(isError = true, text = "API Error: overloaded"))
        assertEquals(ClaudeTurnEnd(ClaudeTurnEnd.Kind.Failed, 7L, "API Error: overloaded"), v.turnEnd)
        assertTrue(v.noteError)
    }

    @Test
    fun `hitting a limit is its own kind, not a failure`() {
        val v = judge(result(isError = true, subtype = "error_max_turns", text = "max turns"))
        assertEquals(ClaudeTurnEnd.Kind.Limit, v.turnEnd.kind)
    }

    /** 用户按停止时 CLI 回的是 is_error 的 result：要认成"我们打断的"，且仍补一条「已中断」 */
    @Test
    fun `an interrupt on this turn is not a failure`() {
        val v = judge(result(isError = true), interruptedTurn = 3)
        assertTrue(v.interrupted)
        assertFalse(v.withdrew)
        assertEquals(ClaudeTurnEnd.Kind.Interrupted, v.turnEnd.kind)
        assertTrue(v.noteError)
    }

    @Test
    fun `a withdraw is an interrupt that also gave the message back`() {
        val v = judge(result(isError = true), interruptedTurn = 3, interruptWithdrew = true)
        assertTrue(v.withdrew)
        assertEquals(ClaudeTurnEnd.Kind.Withdrawn, v.turnEnd.kind)
    }

    /** interruptWithdrew 是上一次打断留下的：只有轮次号对得上才算数 */
    @Test
    fun `an interrupt mark for another turn is ignored`() {
        val v = judge(result(isError = true, text = "boom"), interruptedTurn = 2, interruptWithdrew = true)
        assertFalse(v.interrupted)
        assertFalse(v.withdrew)
        assertEquals(ClaudeTurnEnd.Kind.Failed, v.turnEnd.kind)
    }

    /** 用户点「拒绝」并要求停下的一轮：CLI 报错，但那是用户要的结果，不补红字 */
    @Test
    fun `a denied stop ends as interrupted and stays quiet`() {
        val v = judge(result(isError = true), deniedTurn = 3)
        assertTrue(v.deniedStop)
        assertEquals(ClaudeTurnEnd.Kind.Interrupted, v.turnEnd.kind)
        assertFalse(v.noteError)
    }

    @Test
    fun `a denied mark for another turn is ignored`() {
        val v = judge(result(isError = true, text = "boom"), deniedTurn = 4)
        assertFalse(v.deniedStop)
        assertEquals(ClaudeTurnEnd.Kind.Failed, v.turnEnd.kind)
        assertTrue(v.noteError)
    }

    @Test
    fun `denials already explained elsewhere are not blamed on a rule`() {
        fun denial(id: String) = ClaudeCodeEvent.PermissionDenial(toolName = "Bash", toolUseId = id)
        val kept = deniedByRule(
            denials = listOf(denial("hosted"), denial("by-user"), denial("by-rule")),
            exclusiveHosted = setOf("hosted"),
            userDenied = setOf("by-user"),
        )
        assertEquals(listOf("by-rule"), kept.map { it.toolUseId })
    }
}
