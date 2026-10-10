package dev.min.code.core.engine.min

import dev.min.agent.spi.InterruptedBy
import dev.min.code.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 报告卡上的状态文案：内核会写进 `status` 属性的每个取值都要有资源，不能把英文原串漏给界面 */
class MinReportStatusTest {
    /** 内核 `Reports.statusOf` 的全部取值（改了那边，这里和资源要一起跟上） */
    private val kernelStatuses = listOf(
        "completed", "failed", "interrupted", "interrupted-by-restart", "turn-limit", "stopped-by-hook", "stopped-by-budget",
    )

    @Test
    fun everyStatusTheKernelWritesHasAString() {
        kernelStatuses.forEach { assertNotNull("状态 $it 没有文案", reportStatusRes(it)) }
    }

    @Test
    fun conversationEndedHasItsOwnString() {
        assertEquals(R.string.min_agent_report_conversation_ended, reportStatusRes("conversation-ended"))
    }

    @Test
    fun hookStoppedHasItsOwnString() {
        assertEquals(R.string.min_agent_report_hook_stopped, reportStatusRes("stopped-by-hook"))
    }

    @Test
    fun anInterruptedByValueAddedLaterIsCaughtHere() {
        // 内核对每个 InterruptedBy 写一个状态；新增取值时这里要有人想起来补文案
        assertEquals("InterruptedBy 的取值变了：补内核的 statusOf、资源和上面的状态表", 6, InterruptedBy.entries.size)
    }

    @Test
    fun unknownStatusFallsBackToNull() {
        assertNull(reportStatusRes("something-new"))
    }
}
