package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** 状态行上那两个数字的格式。窄栏里每个字符都要挣位置 */
class ClaudeCodeTurnMeterTest {

    @Test
    fun `一分钟以内给整秒`() {
        assertEquals("0s", formatDuration(400))
        assertEquals("24s", formatDuration(24_300))
        assertEquals("59s", formatDuration(59_999))
    }

    @Test
    fun `超过一分钟给分秒`() {
        assertEquals("1m", formatDuration(60_000))
        assertEquals("1m32s", formatDuration(92_000))
        assertEquals("59m59s", formatDuration(3_599_000))
    }

    @Test
    fun `超过一小时给时分`() {
        assertEquals("1h0m", formatDuration(3_600_000))
        assertEquals("2h5m", formatDuration(7_500_000))
    }

    /** 负数只可能来自时钟回拨，不能显示成 `-3s` */
    @Test
    fun `时钟回拨时不显示负数`() {
        assertEquals("0s", formatDuration(-5_000))
    }

    @Test
    fun `token 上千收成 k`() {
        assertEquals("488", formatTokens(488))
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1000))
        assertEquals("1.2k", formatTokens(1234))
        assertEquals("34.6k", formatTokens(34_567))
    }
}
