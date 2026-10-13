package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    // 期望是 shell 的 '\'' 四个字符；Kotlin 字面量里反斜杠要写两个（写成 '\'' 会得到三个连续单引号）
    @Test
    fun `plain and empty values are wrapped in single quotes`() {
        assertEquals("'abc'", ShellQuote.posix("abc"))
        assertEquals("''", ShellQuote.posix(""))
    }

    @Test
    fun `embedded single quote is closed escaped and reopened`() {
        assertEquals("'it'\\''s'", ShellQuote.posix("it's"))
        assertEquals("''\\'''\\'''", ShellQuote.posix("''"))
    }

    @Test
    fun `shell metacharacters stay literal`() {
        assertEquals("'a b'", ShellQuote.posix("a b"))
        assertEquals("'\$HOME \$(rm -rf /)'", ShellQuote.posix("\$HOME \$(rm -rf /)"))
        assertEquals("'`id`'", ShellQuote.posix("`id`"))
        assertEquals("'line1\nline2'", ShellQuote.posix("line1\nline2"))
        assertEquals("'a\\b\"c'", ShellQuote.posix("a\\b\"c"))
    }
}
