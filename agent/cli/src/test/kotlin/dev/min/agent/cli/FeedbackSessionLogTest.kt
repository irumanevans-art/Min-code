package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.spi.FeedbackSink
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.tools.SendFeedbackTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * SendFeedback 排队时，内核把本会话日志附在草稿上交给宿主（诊断包要带日志）。真文件、真日志、真工具调用。
 * 这里只断言「附上了」；去凭证与成品扫描在 FeedbackBundleTest 里。
 */
class FeedbackSessionLogTest {
    private val root = Files.createTempDirectory("min feedback log")
    private val work = Files.createDirectories(root.resolve("work"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong()

    @After fun close() { scopes.forEach { it.cancel() }; root.toFile().deleteRecursively() }

    private class Capturing : FeedbackSink {
        val drafts = java.util.concurrent.CopyOnWriteArrayList<FeedbackDraft>()
        override suspend fun queue(draft: FeedbackDraft): Boolean { drafts += draft; return true }
    }

    private class FeedbackThenReply : ModelClient {
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            if (calls.incrementAndGet() == 1) emitAll(reply(ToolCallPart(CallId("f1"), "SendFeedback", JsonObject(mapOf(
                "title" to JsonPrimitive("工具反复失败"), "body" to JsonPrimitive("rg 连续超时三次"),
            ))), stop = StopReason.ToolUse)(request))
            else emitAll(reply(TextPart("草稿已放进队列"))(request))
        }
    }

    private suspend fun session(sink: FeedbackSink): SessionRuntime {
        val model = FeedbackThenReply()
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logs = Files.createDirectories(root.resolve("exec"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(SendFeedbackTool),
            host = HostPorts(fs = paths.fs, search = JvmSearch(paths.fs), exec = LocalExec(paths::hostOf, logs, guest(logs)), artifacts = { FakeArtifacts() }),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = guest(root.resolve("home")),
            hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
            trustProjectHooks = { false }, pluginDirs = emptyList(), feedbackSink = sink,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        return hub.create(guest(work), TEST_MODEL, PermissionMode.BypassPermissions)
    }

    private fun guest(path: java.nio.file.Path) = paths.guestOfHost(path)

    @Test fun `SendFeedback hands the sink this session's log, so the bundle can carry it`(): Unit = runBlocking { withTimeout(120_000) {
        val sink = Capturing()
        val rt = session(sink)
        rt.send(ClientMsgId("u1"), listOf(TextPart("hello-marker-7731")))
        while (sink.drafts.isEmpty()) delay(50)
        val draft = sink.drafts.single()
        assertEquals("工具反复失败", draft.title)
        val log = requireNotNull(draft.sessionLog) { "草稿没有附上会话日志" }
        assertTrue(log, log.contains("hello-marker-7731"))
        assertTrue(log, log.contains("SendFeedback"))
        // 日志是磁盘格式：每行一条 JSON
        assertTrue(log, log.lines().filter { it.isNotBlank() }.all { it.startsWith("{") })
    } }
}
