package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `!` 命令：识别前缀、交给模型的上下文、回放时摘掉、工具卡上的结果 */
class UserShellTest {

    @Test
    fun `a line starting with a bang is a shell command, a full-width bang too`() {
        assertEquals("pwd", userShellCommand("!pwd"))
        assertEquals("ls -la", userShellCommand("  !ls -la  "))
        // 中文输入法默认打出的是全角
        assertEquals("pwd", userShellCommand("！pwd"))
        assertEquals("a && b", userShellCommand("! a && b"))
    }

    @Test
    fun `a bare bang or a bang mid sentence is not a command`() {
        assertNull(userShellCommand("!"))
        assertNull(userShellCommand("！"))
        assertNull(userShellCommand("!   "))
        assertNull(userShellCommand("not a !command"))
        assertNull(userShellCommand(""))
    }

    @Test
    fun `the context block quotes the command and keeps the tail of long output`() {
        val run = UserShellRun("git status", 0, " M a.kt\n M b.kt", "")
        assertEquals(
            "<bash-input>git status</bash-input>\n<bash-stdout> M a.kt\n M b.kt</bash-stdout><bash-stderr></bash-stderr>",
            userShellContext(run),
        )
        val huge = UserShellRun("cat big", 0, "x".repeat(20_000), "")
        val context = userShellContext(huge)
        assertTrue(context, context.contains("…${"x".repeat(8_000)}</bash-stdout>"))
        assertTrue(context, !context.contains("x".repeat(8_001)))
    }

    @Test
    fun `a timeout is marked in the context`() {
        val context = userShellContext(UserShellRun("watch", 143, "", "killed", timedOut = true))
        assertTrue(context, context.endsWith("<bash-stderr>killed\n(timed out)</bash-stderr>"))
    }

    @Test
    fun `replay strips the context block and keeps what the person said`() {
        val sent = "<bash-input>git status</bash-input>\n<bash-stdout>clean</bash-stdout><bash-stderr></bash-stderr>\n\n" +
            "把这些改了"
        assertEquals("把这些改了", stripUserShellContext(sent))
        // 两条命令攒在一起
        val two = userShellContext(UserShellRun("pwd", 0, "/workspace", "")) + "\n" +
            userShellContext(UserShellRun("ls", 0, "a.txt", "")) + "\n\nhello"
        assertEquals("hello", stripUserShellContext(two))
        // 人自己说的话里出现这些标签不该被误伤
        assertEquals("see <bash-input> in the docs", stripUserShellContext("see <bash-input> in the docs"))
    }

    @Test
    fun `the card shows output as is, and the exit code only when it failed`() {
        assertEquals("ok", userShellCardResult(UserShellRun("true", 0, "ok\n", "")))
        assertEquals("boom\n[exit 127]", userShellCardResult(UserShellRun("nope", 127, "", "boom")))
        val timed = userShellCardResult(UserShellRun("watch", 143, "partial", "", timedOut = true))
        assertTrue(timed, timed.startsWith("partial\n[timed out after 120s]"))
        assertEquals("[exit 1]", userShellCardResult(UserShellRun("false", 1, "", "")))
    }
}
