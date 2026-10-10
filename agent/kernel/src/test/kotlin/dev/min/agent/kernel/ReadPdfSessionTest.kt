package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSnapshots
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.ReadTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Read 读 PDF 在真会话里的样子：工具结果里的文档进日志（重放得到同一状态），
 * 下一次请求里模型若不收 PDF，文档换成文字说明，日志里仍是原件。
 */
class ReadPdfSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()
    private val snapshots = FakeSnapshots(fs)
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)

    @After
    fun tearDown() = scope.cancel()

    private fun minimalPdf(pages: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        sb.append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        val kids = (0 until pages).joinToString(" ") { "${3 + it} 0 R" }
        sb.append("2 0 obj << /Type /Pages /Kids [$kids] /Count $pages >> endobj\n")
        for (i in 0 until pages) sb.append("${3 + i} 0 obj << /Type /Page /Parent 2 0 R >> endobj\n")
        sb.append("trailer << /Size ${3 + pages} /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun deps(model: ScriptedModel) = testDeps(model, listOf(ReadTool)).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = listOf(ReadTool),
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, snapshots = snapshots),
            platform = base.platform, zone = base.zone, retry = base.retry,
            env = { null },
        )
    }

    @Test
    fun `a pdf read is logged as a document part, replays to the same state, and is degraded for a text-only model`() = runBlocking {
        withTimeout(20_000) {
            val pdf = minimalPdf(2)
            fs.put("/workspace/a.pdf", pdf)
            val model = ScriptedModel(
                reply(ToolCallPart(CallId("r1"), "Read", args("file_path" to "/workspace/a.pdf")), stop = StopReason.ToolUse),
                reply(TextPart("好")),
            )
            val rt = SessionRuntime.create(
                store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
                deps(model), scope,
            )
            rt.send(ClientMsgId("u1"), listOf(TextPart("看这份")))
            rt.awaitIdle()

            // 日志里是原件（base64 整份），不是占位文字
            val log = store.peek(rt.sessionId)!!
            val events = log.read().toList().map { it.event }
            val outcome = events.filterIsInstance<ToolOutcome>().single()
            assertEquals(
                DocumentPart("application/pdf", ImageSource.Base64(Base64.getEncoder().encodeToString(pdf))),
                outcome.result.content.filterIsInstance<DocumentPart>().single(),
            )
            // 重放得到同一状态
            assertEquals(fold(log.header, log.read().toList()), rt.state.value)

            // 第二次请求：TEST_MODEL 不收 PDF，文档换成文字，工具结果的 callId 不变
            val second = model.requests[1].messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>().single()
            assertEquals(CallId("r1"), second.callId)
            assertFalse(second.content.any { it is DocumentPart })
            assertTrue(second.content.filterIsInstance<TextPart>().any { it.text.contains("当前模型不支持读 PDF") })
        }
    }
}
