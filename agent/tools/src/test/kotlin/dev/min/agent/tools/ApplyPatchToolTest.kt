package dev.min.agent.tools

import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ApplyPatchToolTest {
    private val ctx = TestToolContext()
    private fun patch(body: String) = "*** Begin Patch\n$body\n*** End Patch"
    private suspend fun apply(body: String, context: ToolContext = ctx) = ApplyPatchTool.call(context, "input" to patch(body))

    @Test fun `add update delete and move report every file with backups`() = runTest {
        val saved = mutableMapOf<ContentId, ByteArray>()
        val context = object : ToolContext by ctx {
            override suspend fun preserve(content: ByteArray): ContentId = ContentId("c${saved.size}").also { saved[it] = content }
        }
        val added = apply("*** Add File: a\n+head\n+old\n+tail", context)
        assertFalse(added.text, added.isError)
        assertEquals("head\nold\ntail\n", ctx.fs.text("/work/a"))
        assertTrue(added.fileEdits.single().created)
        assertArrayEquals(ctx.fs.bytes("/work/a"), saved[added.fileEdits.single().after])
        ctx.see("/work/a")
        val moved = apply("*** Update File: a\n*** Move to: dir/b\n@@ head\n-old\n+new\n tail", context)
        assertFalse(moved.text, moved.isError)
        assertNull(ctx.fs.text("/work/a"))
        assertEquals("head\nnew\ntail\n", ctx.fs.text("/work/dir/b"))
        assertEquals(2, moved.fileEdits.size)
        assertTrue(moved.fileEdits.first { it.path.value == "/work/a" }.deleted)
        assertArrayEquals("head\nold\ntail\n".encodeToByteArray(), saved[moved.fileEdits.first { it.deleted }.before])
        ctx.see("/work/dir/b")
        val deleted = apply("*** Delete File: dir/b", context)
        assertFalse(deleted.text, deleted.isError)
        assertTrue(deleted.fileEdits.single().deleted)
        assertNull(deleted.fileEdits.single().after)
        assertNull(ctx.fs.text("/work/dir/b"))
    }

    @Test fun `invalid context validates whole patch before any write`() = runTest {
        ctx.fs.put("/work/b", "actual\n"); ctx.see("/work/b")
        val result = apply("*** Add File: a\n+created\n*** Update File: b\n@@\n-wrong\n+new")
        assertTrue(result.isError)
        assertTrue(ctx.fs.writes.isEmpty())
        assertNull(ctx.fs.text("/work/a"))
        assertEquals("actual\n", ctx.fs.text("/work/b"))
    }

    @Test fun `write failure and cancellation roll back new updated and deleted paths`() = runTest {
        for (cancel in listOf(false, true)) {
            ctx.fs.put("/work/a", "old\n"); ctx.see("/work/a")
            ctx.fs.put("/work/d", "deleted\n"); ctx.see("/work/d")
            var failed = false
            val fs = object : WorkspaceFs by ctx.fs {
                override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
                    if (path.value == "/work/z" && !failed) {
                        failed = true
                        if (cancel) throw CancellationException("cancel") else throw IllegalStateException("disk full")
                    }
                    ctx.fs.writeAtomic(path, bytes)
                }
            }
            val context = object : ToolContext by ctx { override val fs = fs }
            var cancelled = false
            try {
                val result = apply("*** Update File: a\n@@\n-old\n+new\n*** Delete File: d\n*** Add File: c\n+created\n*** Add File: z\n+last", context)
                assertTrue(result.isError)
                assertTrue(result.fileEdits.isEmpty())
            } catch (e: CancellationException) { cancelled = true }
            assertEquals(cancel, cancelled)
            assertTrue(failed)
            assertEquals("old\n", ctx.fs.text("/work/a"))
            assertEquals("deleted\n", ctx.fs.text("/work/d"))
            assertNull(ctx.fs.text("/work/c"))
            assertNull(ctx.fs.text("/work/z"))
        }
    }

    @Test fun `strict grammar rejects malformed patches and duplicate targets`() {
        for (body in listOf("", "*** Add File: a\nnot plus", "*** Delete File: a\n+oops", "*** Update File: a\n@@\n?oops", "*** Add File: a\n+x\n*** Delete File: a", "*** Update File: a\n*** Move to: a", "*** Add File: ../outside\n+x", "*** Add File: C:\\outside\n+x", "*** Add File: /\n+x", "*** Add File: a\n+x\n*** Add File: ./a\n+y")) {
            assertTrue(body, ApplyPatchTool.parse(args("input" to patch(body))) is ToolInput.Invalid)
        }
    }

    @Test fun `unread changed existing and ambiguous targets fail without writes`() = runTest {
        ctx.fs.put("/work/a", "old\n")
        assertTrue(apply("*** Delete File: a").isError)
        ctx.see("/work/a"); ctx.fs.put("/work/a", "longer\n")
        assertTrue(apply("*** Update File: a\n@@\n-longer\n+new").isError)
        ctx.fs.put("/work/a", "same\nsame\n"); ctx.see("/work/a")
        assertTrue(apply("*** Update File: a\n@@\n-same\n+new").isError)
        assertTrue(apply("*** Add File: b\n+x\n*** Add File: /work/b\n+y").isError)
        assertTrue(ctx.fs.writes.isEmpty()); assertNull(ctx.fs.text("/work/b"))
    }

    @Test fun `different aliases of the same target are rejected before writing`() = runTest {
        val context = object : ToolContext by ctx {
            override fun canonicalPath(path: GuestPath) = GuestPath("/work/same-real-file")
        }
        val result = apply("*** Add File: a\n+x\n*** Add File: b\n+y", context)
        assertTrue(result.isError); assertTrue(ctx.fs.writes.isEmpty())
        assertNull(ctx.fs.text("/work/a")); assertNull(ctx.fs.text("/work/b"))
    }

    @Test fun `multiple hunks EOF anchor and BOM CRLF preserve file form`() = runTest {
        ctx.fs.put("/work/a", "\uFEFFhead\r\none\r\ntwo\r\nlast\r\n"); ctx.see("/work/a")
        val result = apply("*** Update File: a\n@@ head\n-one\n+first\n two\n@@\n-last\n+end\n*** End of File")
        assertFalse(result.text, result.isError)
        assertEquals("\uFEFFhead\r\nfirst\r\ntwo\r\nend\r\n", ctx.fs.text("/work/a"))
    }

    @Test fun `permission ask covers source destination and all files`() {
        val ask = ApplyPatchTool.permissionAsk(ApplyPatchTool.parsed("input" to patch("*** Update File: a\n*** Move to: secret/b\n@@\n-x\n+y\n*** Add File: c\n+new")), ctx)
        assertEquals("apply_patch", ask.tool)
        assertEquals(listOf("/work/a", "/work/secret/b", "/work/c"), ask.paths.map { it.path.value })
        assertTrue(ask.paths.all { it.kind == AccessKind.Write })
    }
}
