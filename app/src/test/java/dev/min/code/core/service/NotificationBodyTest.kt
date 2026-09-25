package dev.min.code.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** [notificationBody]：完成通知正文的压行、截断与兜底规则 */
class NotificationBodyTest {

    @Test
    fun null_or_empty_falls_back_to_default() {
        assertEquals("任务已完成", notificationBody(null))
        assertEquals("任务已完成", notificationBody(""))
    }

    @Test
    fun only_whitespace_falls_back_to_default() {
        assertEquals("任务已完成", notificationBody("   "))
        assertEquals("任务已完成", notificationBody("\n\r\n\t "))
    }

    @Test
    fun newlines_become_spaces_and_runs_collapse() {
        assertEquals("第一行 第二行 第三行", notificationBody("第一行\n第二行\r\n\t第三行"))
        assertEquals("Hello world", notificationBody("Hello\n    world"))
        assertEquals("前后贴住", notificationBody("  前后贴住  "))
    }

    @Test
    fun truncates_to_40_chars_without_ellipsis() {
        assertEquals("x".repeat(40), notificationBody("x".repeat(45)))
        assertEquals("汉".repeat(40), notificationBody("汉".repeat(41)))
        assertEquals("x".repeat(40), notificationBody("x".repeat(40)))
    }

    @Test
    fun does_not_cut_at_sentence_punctuation() {
        // 不是按句号截：整段处理完再截 40，截断处可能越过句号
        val input = "短句。" + "长".repeat(45)
        assertEquals("短句。" + "长".repeat(37), notificationBody(input))
        // 句号后面还有正文时原样带出，不丢
        assertEquals("一句话。另一句话", notificationBody("一句话。另一句话"))
    }
}
