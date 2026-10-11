package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCwdProbeTest {
    @Test fun `parse takes the first absolute line`() {
        assertEquals("/work/sub", ShellCwdProbe.parse("/work/sub\n"))
        assertEquals("/d/my dir/x", ShellCwdProbe.parse("/d/my dir/x\r\n"))
        assertEquals("/", ShellCwdProbe.parse("/"))
        // Windows 的 Git Bash 用 pwd -W 打盘符写法
        assertEquals("C:/Users/x y", ShellCwdProbe.parse("C:/Users/x y\n"))
        assertEquals("d:\\work", ShellCwdProbe.parse("d:\\work"))
    }

    @Test fun `parse rejects anything that is not a plain absolute path`() {
        assertNull(ShellCwdProbe.parse(""))
        assertNull(ShellCwdProbe.parse("\n/work"))
        assertNull(ShellCwdProbe.parse("relative/dir"))
        assertNull(ShellCwdProbe.parse("C:work"))
        assertNull(ShellCwdProbe.parse("1:/work"))
        assertNull(ShellCwdProbe.parse("/work/\u001b[31mred"))
        assertNull(ShellCwdProbe.parse("/" + "a".repeat(5000)))
    }

    @Test fun `wrap quotes the user script and the file`() {
        val script = "echo 'it''s' \"x\"\ncd sub <<EOF\nunterminated"
        val wrapped = ShellCwdProbe.wrap(script, "/tmp/it's.cwd")
        // 用户文本整个在一对单引号里（内部的单引号被转义），探针不会被未收口的 heredoc 吞掉
        assertTrue(wrapped, wrapped.startsWith("eval 'echo '\\''it'\\'''\\''s'\\'' \"x\"\ncd sub <<EOF\nunterminated'\n"))
        assertTrue(wrapped, wrapped.contains("command pwd >| '/tmp/it'\\''s.cwd'"))
        assertTrue("探针之后以原退出码退出", wrapped.trimEnd().endsWith("exit \$__min_rc"))
        assertTrue(ShellCwdProbe.wrap("x", "/f", pwdCommand = "command pwd -W").contains("{ command pwd -W >| '/f'; }"))
    }
}
