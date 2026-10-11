package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桌面工具表跟着 CLAUDE_CODE_DISABLE_CRON 走（解析本身在内核的 cronDisabledBy，测试在内核那边） */
class CronToolsOffTest {
    @Test
    fun `with scheduled tasks disabled the four cron tools are not offered at all`() {
        val cron = listOf("CronCreate", "CronDelete", "CronList", "ScheduleWakeup")
        val on = desktopTools().map { it.name }
        cron.forEach { assertTrue(it in on) }
        val off = desktopTools(cronEnabled = false).map { it.name }
        cron.forEach { assertFalse("$it 不该出现", it in off) }
        assertEquals("别的工具原样，顺序不变", on.filterNot { it in cron }, off)
    }
}
