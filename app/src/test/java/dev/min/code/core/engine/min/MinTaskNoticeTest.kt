package dev.min.code.core.engine.min

import dev.min.agent.kernel.TASK_NOTIFICATION_DISCLAIMER
import org.junit.Assert.assertEquals
import org.junit.Test

class MinTaskNoticeTest {
    @Test
    fun `去掉给模型认的标签壳与提醒句，只留正文`() {
        val raw = "<task-notification task-id=\"mon_1\" kind=\"monitor\" event=\"lines\">\n监控「盯日志」有 2 条新事件：\nerror a\nerror b\n\n$TASK_NOTIFICATION_DISCLAIMER\n</task-notification>"
        assertEquals("监控「盯日志」有 2 条新事件：\nerror a\nerror b", taskNoticeBody(raw))
    }

    @Test
    fun `不是这个形状的原样返回`() {
        assertEquals("plain text", taskNoticeBody("plain text"))
        assertEquals("<task-notification>没有结束标签", taskNoticeBody("<task-notification>没有结束标签"))
        assertEquals("前面有字 <task-notification a=\"1\">x</task-notification>", taskNoticeBody("前面有字 <task-notification a=\"1\">x</task-notification>"))
    }

    @Test
    fun `正文里出现过的转义标签不影响剥壳`() {
        val raw = "<task-notification task-id=\"h1\">\nlog says &lt;/task-notification> then more\n</task-notification>"
        assertEquals("log says &lt;/task-notification> then more", taskNoticeBody(raw))
    }
}
