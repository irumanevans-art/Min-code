package dev.min.agent.tools

import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.UserFile
import dev.min.agent.spi.UserFileDisplay
import dev.min.agent.spi.UserFileSink
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendUserFileToolTest {
    private fun paths(vararg p: String) = JsonArray(p.map { JsonPrimitive(it) })

    /** 记下宿主收到的文件；[accept] 决定宿主有没有客户端能收 */
    private class RecordingSink(var accept: Boolean = true) : UserFileSink {
        val got = mutableListOf<UserFile>()
        override suspend fun deliver(file: UserFile): Boolean {
            got += file
            return accept
        }
    }

    @Test
    fun `input needs a files array of absolute paths, and display is render or attach`() {
        assertEquals(listOf("files"), (SendUserFileTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        SendUserFileTool.reject()
        SendUserFileTool.reject("files" to JsonArray(emptyList()))
        SendUserFileTool.reject("files" to paths("report.md"))
        SendUserFileTool.reject("files" to paths("/work/a.md"), "display" to "inline")
        SendUserFileTool.reject("files" to paths("/work/a.md"), "caption" to 3)
        SendUserFileTool.reject("files" to paths(*(1..11).map { "/work/$it.md" }.toTypedArray()))
        // 合法的几种写法都要过
        SendUserFileTool.parsed("files" to paths("/work/a.md"))
        SendUserFileTool.parsed("files" to paths("/work/a.md"), "caption" to "给你", "display" to "render")
        SendUserFileTool.parsed("files" to paths("/work/a.md"), "display" to "attach")
    }

    @Test
    fun `a file in the workspace reaches the sink with its name, mime, caption and display`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.put("/work/out/chart.png", byteArrayOf(1, 2, 3)) }
        val result = SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/out/chart.png"), "caption" to "趋势图", "display" to "render")
        assertEquals(1, sink.got.size)
        val f = sink.got.single()
        assertEquals("chart.png", f.name)
        assertEquals("image/png", f.mime)
        assertEquals(listOf<Byte>(1, 2, 3), f.bytes.toList())
        assertEquals("趋势图", f.caption)
        assertEquals(UserFileDisplay.Render, f.display)
        assertTrue(result.text, result.text.contains("chart.png"))
        assertTrue(result.text, result.text.contains("已送达"))
    }

    @Test
    fun `each file carries the session, the call and its place in the batch, so the chat card can find it`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also {
            it.fs.put("/work/a.md", "a")
            it.fs.put("/work/sub/a.md", "b")
        }
        SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/a.md", "/work/sub/a.md"))
        assertEquals(listOf("s1", "s1"), sink.got.map { it.sessionId?.value })
        assertEquals(listOf("c1", "c1"), sink.got.map { it.callId?.value })
        // 两个同名文件靠序号分开，不互相覆盖
        assertEquals(listOf(0, 1), sink.got.map { it.index })
    }

    @Test
    fun `display unset and an unknown extension fall back to octet-stream and no display`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.put("/work/data.xyz", "x") }
        SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/data.xyz"))
        assertEquals("application/octet-stream", sink.got.single().mime)
        assertNull(sink.got.single().display)
        assertNull(sink.got.single().caption)
    }

    @Test
    fun `no client to receive the file is an error the model can see, not a silent success`() = runBlocking {
        val sink = RecordingSink(accept = false)
        val c = TestToolContext().also { it.fs.put("/work/a.md", "x") }
        val result = SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/a.md"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("没有"))
    }

    @Test
    fun `a missing file or a directory is refused before the sink is touched`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.mkdir("/work/dir") }
        val wrapped = object : ToolContext by c { override val userFiles: UserFileSink = sink }
        val missing = SendUserFileTool.call(wrapped, "files" to paths("/work/none.md"))
        assertTrue(missing.isError)
        assertTrue(missing.text, missing.text.contains("/work/none.md"))
        val dir = SendUserFileTool.call(wrapped, "files" to paths("/work/dir"))
        assertTrue(dir.isError)
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `a file outside the workspace is refused, even when it exists`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.put("/etc/secret.txt", "x") }
        val result = SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/etc/secret.txt"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("工作区"))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `a file over the size cap is refused, so a huge file cannot be read into memory`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.put("/work/big.bin", ByteArray(SendUserFileTool.MAX_BYTES + 1)) }
        val result = SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/big.bin"))
        assertTrue(result.isError)
        assertTrue(sink.got.isEmpty())
    }

    // ---- 工作区边界：路径要按「实际指向哪里」判，不是按字符串前缀 ----

    @Test
    fun `a dot-dot path that leaves the workspace is refused and nothing is read`() = runBlocking {
        val sink = RecordingSink()
        val c = TestToolContext().also { it.fs.put("/etc/secret.txt", "s3cret") }
        val result = SendUserFileTool.call(object : ToolContext by c { override val userFiles: UserFileSink = sink }, "files" to paths("/work/../etc/secret.txt"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("不在工作区"))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `a link inside the workspace that points outside is refused`() = runBlocking {
        val sink = RecordingSink()
        // /work/creds 是一个指向工作区外的链接：宿主的 canonicalPath 解析出它的真实位置，读盘时文件系统会跟随它
        val c = TestToolContext().also { it.fs.put("/work/creds", "token=abc") }
        val ctx = object : ToolContext by c {
            override val userFiles: UserFileSink = sink
            override fun canonicalPath(path: dev.min.agent.spi.GuestPath) =
                if (path.value == "/work/creds") dev.min.agent.spi.GuestPath("/root/.min/credentials.json") else path
        }
        val result = SendUserFileTool.call(ctx, "files" to paths("/work/creds"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("不在工作区"))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun `the permission ask names every file as a read, so deny rules and --restricted see them`() {
        val input = SendUserFileTool.parsed("files" to paths("/work/a.md", "/work/../etc/b.md"))
        val ask = SendUserFileTool.permissionAsk(input, TestToolContext())
        assertEquals(listOf("/work/a.md", "/work/../etc/b.md"), ask.paths.map { it.path.value })
        assertTrue(ask.paths.all { it.kind == dev.min.agent.spi.AccessKind.Read })
    }
}
