package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLI 退出后界面上那句话（[cliExitError]） */
class ClaudeCodeCliExitTest {

    @Test
    fun `a SIGKILL explains the child-process limit and where to turn it off`() {
        val text = cliExitError(EXIT_SIGKILL, unexpected = true, previous = null)!!
        assertEquals(CLI_KILLED_BY_SYSTEM, text)
        assertTrue(text, text.contains("exit 137") && text.contains("子进程") && text.contains("「停用子进程限制」"))
    }

    /** 被杀时 claude 来不及打印任何东西，先前挂着的 stderr 报错不是这次退出的原因 */
    @Test
    fun `a SIGKILL replaces an older error`() {
        assertEquals(CLI_KILLED_BY_SYSTEM, cliExitError(EXIT_SIGKILL, unexpected = true, previous = "会话中断: Stream closed"))
    }

    @Test
    fun `other unexpected exits keep the stderr reason or fall back to the code`() {
        assertEquals("Error: bad flag", cliExitError(1, unexpected = true, previous = "Error: bad flag"))
        assertEquals("claude 进程退出 (exit 1)", cliExitError(1, unexpected = true, previous = null))
    }

    /** 我们自己关的（停止、换档重启）：137 也不加话，destroy 收树时同样可能是 137 */
    @Test
    fun `an exit we caused adds nothing`() {
        assertNull(cliExitError(EXIT_SIGKILL, unexpected = false, previous = null))
        assertEquals("kept", cliExitError(143, unexpected = false, previous = "kept"))
    }
}
