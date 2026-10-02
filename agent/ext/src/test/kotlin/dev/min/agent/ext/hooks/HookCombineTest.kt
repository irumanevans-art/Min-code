package dev.min.agent.ext.hooks

import org.junit.Assert.*
import org.junit.Test

class HookCombineTest {
    private val e = HookEvent.PreToolUse
    private fun v(decision: HookDecision) = HookVerdict(handler(), decision)

    @Test fun `决定优先级阻断 deny ask allow 与空批中性`() {
        assertEquals(HookDecision.Neutral, combine(e, emptyList()).decision)
        assertEquals(HookDecision.Allow, combine(e, listOf(v(HookDecision.Neutral), v(HookDecision.Allow))).decision)
        assertEquals(HookDecision.Ask, combine(e, listOf(v(HookDecision.Allow), v(HookDecision.Ask))).decision)
        assertEquals(HookDecision.Deny, combine(e, listOf(v(HookDecision.Ask), v(HookDecision.Deny))).decision)
        assertEquals(HookDecision.Block, combine(e, listOf(v(HookDecision.Deny), v(HookDecision.Block))).decision)
    }

    @Test fun `全部上下文系统消息理由按处理器顺序保留`() {
        val vs = (1..3).map { i -> v(HookDecision.Neutral).copy(additionalContext = listOf(HookText("ctx$i")),
            systemMessage = HookText("sys$i"), reason = HookText("why$i"), nonBlockingError = "err$i", notes = listOf("note$i")) }
        val c = combine(e, vs)
        assertEquals(listOf("ctx1", "ctx2", "ctx3"), c.additionalContext.map { it.text })
        assertEquals(listOf("sys1", "sys2", "sys3"), c.systemMessages.map { it.text })
        assertEquals(listOf("why1", "why2", "why3"), c.reasons.map { it.text })
        assertEquals(listOf("err1", "err2", "err3"), c.nonBlockingErrors)
        assertEquals(listOf("note1", "note2", "note3"), c.notes)
    }

    @Test fun `唯一改写采用多个相同改写可采用`() {
        val input = objectOf("""{"a":1,"b":2}""")
        val one = v(HookDecision.Allow).copy(updatedInput = input)
        assertEquals(input, combine(e, listOf(v(HookDecision.Neutral), one)).updatedInput)
        assertEquals(input, combine(e, listOf(one, one.copy(updatedInput = objectOf("""{"b":2,"a":1}""")))).updatedInput)
        assertEquals(HookDecision.Allow, combine(e, listOf(one, one)).decision)
    }

    @Test fun `多个不同改写拒绝并给模型冲突理由`() {
        val vs = listOf(v(HookDecision.Allow).copy(updatedInput = objectOf("""{"x":1}""")),
            v(HookDecision.Allow).copy(updatedInput = objectOf("""{"x":2}""")))
        val c = combine(e, vs)
        assertEquals(HookDecision.Deny, c.decision); assertNull(c.updatedInput)
        assertTrue(c.reasons.last().text.contains("两个 hook 改写冲突"))
    }

    @Test fun `任一 continue false 优先于所有决定与改写冲突`() {
        val vs = listOf(v(HookDecision.Allow).copy(shouldContinue = false, stopReason = HookText("stop")),
            v(HookDecision.Block).copy(stopReason = HookText("another")))
        val c = combine(e, vs)
        assertFalse(c.shouldContinue); assertEquals(listOf("stop", "another"), c.stopReasons.map { it.text })
        assertTrue(combine(e, emptyList()).shouldContinue)
    }

    @Test fun `合并保留超时启动失败附件及单处理器结论`() {
        val attachment = HookAttachmentRequest(listOf("stdout"), HookText("x".repeat(10_001)))
        val vs = listOf(v(HookDecision.Neutral).copy(timedOut = true, attachmentRequests = listOf(attachment)),
            v(HookDecision.Neutral).copy(failedToStart = true))
        val c = combine(e, vs)
        assertTrue(c.timedOut); assertTrue(c.failedToStart); assertEquals(listOf(attachment), c.attachmentRequests)
        assertEquals(vs, c.verdicts)
    }

    @Test(expected = IllegalArgumentException::class) fun `不允许跨事件合并`() {
        combine(e, listOf(HookVerdict(handler(HookEvent.Stop))))
    }
}
