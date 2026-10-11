package dev.min.agent.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/loop` 的纯规则（[LoopCommand]）：间隔解析（前置 `5m`、尾随 `every 2 hours`）、秒向上取整到分钟、
 * 非整步长取最近的整步长并说明、loop.md 两处的优先级与 25,000 字节截断。
 * 期望对照 Claude Code 的 scheduled-tasks 页：「Seconds are rounded up to the nearest minute」
 * 「rounded to the nearest interval that does and Claude tells you what it picked」。
 */
class LoopCommandTest {
    // ---- 解析 ----

    @Test
    fun `a leading interval token is taken off the prompt`() {
        val r = LoopCommand.parse("5m check the deploy")
        assertEquals(300L, r.intervalSeconds)
        assertEquals("check the deploy", r.prompt)
    }

    @Test
    fun `a trailing every clause is taken off the prompt`() {
        val r = LoopCommand.parse("check the deploy every 2 hours")
        assertEquals(7200L, r.intervalSeconds)
        assertEquals("check the deploy", r.prompt)
    }

    @Test
    fun `all four units are understood in both positions`() {
        assertEquals(30L, LoopCommand.parse("30s x").intervalSeconds)
        assertEquals(90L * 60, LoopCommand.parse("90m x").intervalSeconds)
        assertEquals(3L * 3600, LoopCommand.parse("3h x").intervalSeconds)
        assertEquals(2L * 86400, LoopCommand.parse("2d x").intervalSeconds)
        assertEquals(60L, LoopCommand.parse("x every minute").intervalSeconds)
        assertEquals(86400L, LoopCommand.parse("x every day").intervalSeconds)
        assertEquals(10L * 60, LoopCommand.parse("x every 10 minutes").intervalSeconds)
    }

    @Test
    fun `no interval means a self-paced loop and the whole text is the prompt`() {
        val r = LoopCommand.parse("check whether CI passed")
        assertNull(r.intervalSeconds)
        assertEquals("check whether CI passed", r.prompt)
    }

    @Test
    fun `interval only leaves the prompt empty, so the default prompt applies`() {
        val r = LoopCommand.parse("15m")
        assertEquals(900L, r.intervalSeconds)
        assertEquals("", r.prompt)
        assertNull(LoopCommand.parse("").intervalSeconds)
    }

    @Test
    fun `a number with a bad unit is not an interval`() {
        val r = LoopCommand.parse("5w check")
        assertNull(r.intervalSeconds)
        assertEquals("5w check", r.prompt)
    }

    // ---- 取整 ----

    @Test
    fun `seconds round up to whole minutes`() {
        assertEquals(1L, LoopCommand.plan(30).minutes)
        assertEquals(2L, LoopCommand.plan(61).minutes)
        assertEquals("* * * * *", LoopCommand.plan(30).cron)
        assertNotNull(LoopCommand.plan(30).note)
        assertTrue(LoopCommand.plan(30).note!!.contains("向上取整"))
    }

    @Test
    fun `clean minute steps map to cron steps without a note`() {
        val p = LoopCommand.plan(5 * 60)
        assertEquals("*/5 * * * *", p.cron)
        assertEquals(5L, p.minutes)
        assertNull(p.note)
    }

    @Test
    fun `hour and day steps map to hour and day crons`() {
        assertEquals("0 * * * *", LoopCommand.plan(3600).cron)
        assertEquals("0 */2 * * *", LoopCommand.plan(2 * 3600).cron)
        assertEquals("0 0 * * *", LoopCommand.plan(24 * 3600).cron)
    }

    @Test
    fun `a non-clean minute interval rounds to the nearest clean step and says so`() {
        // 7 分钟：最近的整步长是 6（差 1），不是 10（差 3）
        val p = LoopCommand.plan(7 * 60)
        assertEquals(6L, p.minutes)
        assertEquals("*/6 * * * *", p.cron)
        assertTrue(p.note!!.contains("6 分钟"))
    }

    @Test
    fun `a tie goes to the shorter interval`() {
        // 90 分钟：60 与 120 同距，取较短的 60
        assertEquals(60L, LoopCommand.plan(90 * 60).minutes)
    }

    @Test
    fun `intervals beyond a day clamp to daily and say so`() {
        val p = LoopCommand.plan(3 * 86400)
        assertEquals("0 0 * * *", p.cron)
        assertNotNull(p.note)
    }

    // ---- loop.md ----

    @Test
    fun `the project loop md wins over the user one`() {
        assertEquals("project", LoopCommand.defaultPrompt(project = "project", user = "user"))
    }

    @Test
    fun `the user loop md applies when the project has none`() {
        assertEquals("user", LoopCommand.defaultPrompt(project = null, user = "user"))
    }

    @Test
    fun `no loop md anywhere means the built-in maintenance prompt`() {
        assertNull(LoopCommand.defaultPrompt(project = null, user = null))
        assertNull(LoopCommand.defaultPrompt(project = "   \n", user = null))
    }

    @Test
    fun `loop md longer than 25000 bytes is truncated, counted in bytes not chars`() {
        val long = "a".repeat(30_000)
        assertEquals(25_000, LoopCommand.defaultPrompt(project = long, user = null)!!.toByteArray(Charsets.UTF_8).size)
        // 每个汉字 3 字节：截断不能切在字符中间
        val cut = LoopCommand.defaultPrompt(project = "中".repeat(10_000), user = null)!!
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 25_000)
        assertTrue(cut.all { it == '中' })
    }
}
