package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CLAUDE_ENV_FILE` 的前导：两个宿主共用的拼法 */
class ShellPreambleTest {
    @Test fun `no files means no preamble and the script is untouched`() {
        assertNull(ShellPreamble.forFiles(emptyList()))
        assertEquals("echo hi", ShellPreamble.apply(null, "echo hi"))
        assertEquals("echo hi", ShellPreamble.apply("", "echo hi"))
    }

    @Test fun `each file is sourced only if it exists, in the given order`() {
        val p = ShellPreamble.forFiles(listOf("/a/one.sh", "/b/two.sh"))!!
        assertEquals("if [ -f '/a/one.sh' ]; then . '/a/one.sh'; fi\nif [ -f '/b/two.sh' ]; then . '/b/two.sh'; fi", p)
        assertEquals("$p\necho hi", ShellPreamble.apply(p, "echo hi"))
    }

    @Test fun `a path with quotes and spaces stays one word`() {
        val p = ShellPreamble.forFiles(listOf("/a b/it's.sh"))!!
        // 期望是 shell 的 '\'' 四个字符；Kotlin 字面量里反斜杠要写两个，写成 '\'' 会得到三个连续单引号（坏的 shell）
        assertTrue(p, p.contains("'/a b/it'\\''s.sh'"))
        assertTrue("不留下没转义的单引号把 shell 拆开：$p", !p.contains("it's.sh'"))
    }
}
