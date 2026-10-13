package dev.min.agent.tools

import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.testkit.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「先读后写」判据与大小上限的共用件；各工具自己的措辞由各自的测试钉住，这里补 ApplyPatch 与共用函数本身 */
class StaleReadTest {
    private val ctx = TestToolContext()
    private val path = GuestPath("/work/a")

    private suspend fun stat(): FileStat = checkNotNull(ctx.fs.stat(path))

    @Test fun `unread stale and fresh files are told apart`() = runTest {
        ctx.fs.put("/work/a", "x")
        val current = stat()
        assertEquals("修改 /work/a 之前先用 Read 读一遍。", staleReadProblem(ctx, path, current))
        ctx.see("/work/a")
        assertNull(staleReadProblem(ctx, path, current))
        ctx.fs.put("/work/a", "xy")
        assertEquals("/work/a 在你读过之后被改过，先重新 Read 再改。", staleReadProblem(ctx, path, stat()))
    }

    @Test fun `callers can override both messages`() = runTest {
        ctx.fs.put("/work/a", "x")
        assertEquals("U", staleReadProblem(ctx, path, stat(), unreadMessage = "U", staleMessage = "S"))
        ctx.see("/work/a")
        ctx.fs.put("/work/a", "xy")
        assertEquals("S", staleReadProblem(ctx, path, stat(), unreadMessage = "U", staleMessage = "S"))
    }

    @Test fun `apply patch keeps its own wording for unread and stale files`() = runTest {
        ctx.fs.put("/work/a", "old\n")
        val patch = "*** Begin Patch\n*** Update File: a\n@@\n-old\n+new\n*** End Patch"
        val unread = ApplyPatchTool.call(ctx, "input" to patch)
        assertTrue(unread.text, unread.isError)
        assertEquals("修改 /work/a 之前先用 Read 读一遍。", unread.text)
        ctx.see("/work/a")
        ctx.fs.put("/work/a", "old!\n")
        val stale = ApplyPatchTool.call(ctx, "input" to patch)
        assertEquals("/work/a 在你读过之后被改过，先重新 Read。", stale.text)
    }

    @Test fun `all three editors share one size limit`() {
        assertEquals(10L * 1024 * 1024, MAX_EDITABLE_FILE_BYTES)
        assertEquals(MAX_EDITABLE_FILE_BYTES, MAX_NOTEBOOK_BYTES)
    }
}
