package dev.min.agent.tools

import dev.min.agent.spi.PreviewFile
import dev.min.agent.spi.PreviewSink
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactToolTest {
    /** 记下宿主收到的产物；[accept] 决定有没有界面能打开它 */
    private class Recording(var accept: Boolean = true) : PreviewSink {
        val got = mutableListOf<PreviewFile>()
        override suspend fun show(file: PreviewFile): Boolean {
            got += file
            return accept
        }
    }

    private fun wrap(c: TestToolContext, sink: PreviewSink): ToolContext =
        object : ToolContext by c { override val artifactView: PreviewSink = sink }

    @Test
    fun `path is required, must be absolute, and only html, htm or md are accepted`() {
        assertEquals(listOf("path"), (ArtifactTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        ArtifactTool.reject()
        ArtifactTool.reject("path" to "report.html")
        ArtifactTool.reject("path" to "/work/a.exe")
        ArtifactTool.reject("path" to "/work/a.txt")
        ArtifactTool.reject("path" to "/work/a.html", "title" to 3)
        ArtifactTool.parsed("path" to "/work/a.html")
        ArtifactTool.parsed("path" to "/work/a.htm", "title" to "趋势")
        ArtifactTool.parsed("path" to "/work/notes.MD")
    }

    @Test
    fun `an html file in the workspace reaches the viewer with its mime and title`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also { it.fs.put("/work/out/page.html", "<h1>hi</h1>") }
        val result = ArtifactTool.call(wrap(c, sink), "path" to "/work/out/page.html", "title" to "周报")
        assertTrue(result.text, !result.isError)
        val f = sink.got.single()
        assertEquals("page.html", f.name)
        assertEquals("text/html", f.mime)
        assertEquals("<h1>hi</h1>", f.text)
        assertEquals("周报", f.title)
    }

    @Test
    fun `a markdown file is handed over with the markdown mime and no title by default`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also { it.fs.put("/work/plan.md", "# 计划\n") }
        ArtifactTool.call(wrap(c, sink), "path" to "/work/plan.md")
        val f = sink.got.single()
        assertEquals("text/markdown", f.mime)
        assertNull(f.title)
    }

    @Test
    fun `no viewer to show the artifact is an error the model can see`() = runBlocking {
        val sink = Recording(accept = false)
        val c = TestToolContext().also { it.fs.put("/work/a.html", "x") }
        val result = ArtifactTool.call(wrap(c, sink), "path" to "/work/a.html")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("没有"))
    }

    @Test
    fun `a file outside the workspace is refused and the viewer is not touched`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also { it.fs.put("/etc/a.html", "x") }
        val result = ArtifactTool.call(wrap(c, sink), "path" to "/etc/a.html")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("工作区"))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `a missing file, a directory, or a non-UTF-8 file is refused before the viewer is touched`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also {
            it.fs.mkdir("/work/dir.html")
            it.fs.put("/work/bad.html", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        }
        val w = wrap(c, sink)
        assertTrue(ArtifactTool.call(w, "path" to "/work/none.html").isError)
        assertTrue(ArtifactTool.call(w, "path" to "/work/dir.html").isError)
        val bad = ArtifactTool.call(w, "path" to "/work/bad.html")
        assertTrue(bad.isError)
        assertTrue(bad.text, bad.text.contains("UTF-8"))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `an oversize file is refused so it cannot be read into memory`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also { it.fs.put("/work/big.html", ByteArray(ArtifactTool.MAX_BYTES + 1) { 0x41 }) }
        assertTrue(ArtifactTool.call(wrap(c, sink), "path" to "/work/big.html").isError)
        assertTrue(sink.got.isEmpty())
    }

    // ---- 工作区边界：与 SendUserFile 同一条检查 ----

    @Test
    fun `a dot-dot path or a link that leaves the workspace is refused`() = runBlocking {
        val sink = Recording()
        val c = TestToolContext().also {
            it.fs.put("/etc/page.html", "<p>x</p>")
            it.fs.put("/work/link.html", "<p>y</p>")
        }
        val ctx = object : ToolContext by c {
            override val artifactView: PreviewSink = sink
            override fun canonicalPath(path: dev.min.agent.spi.GuestPath) =
                if (path.value == "/work/link.html") dev.min.agent.spi.GuestPath("/sdcard/page.html") else path
        }
        for (p in listOf("/work/../etc/page.html", "/work/link.html")) {
            val result = ArtifactTool.call(ctx, "path" to p)
            assertTrue(p, result.isError)
            assertTrue(p + " -> " + result.text, result.text.contains("不在工作区"))
        }
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `the permission ask names the file as a read`() {
        val ask = ArtifactTool.permissionAsk(ArtifactTool.parsed("path" to "/work/a.html"), TestToolContext())
        assertEquals(listOf("/work/a.html"), ask.paths.map { it.path.value })
        assertEquals(dev.min.agent.spi.AccessKind.Read, ask.paths.single().kind)
    }
}
