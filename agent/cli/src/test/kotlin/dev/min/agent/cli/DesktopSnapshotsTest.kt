package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.SnapshotResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files

class DesktopSnapshotsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun composition_records_real_checkpoint_restores_after_restart_and_collects_on_close() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat/completions") { exchange ->
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".encodeToByteArray()) }
            }; start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cwd = tmp.newFolder("project").toPath(); val home = tmp.newFolder("agent").toPath()
            Files.write(cwd.resolve("x"), "before".encodeToByteArray())
            val args = parseArgs(arrayOf("--provider", "checkpoint-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:${server.address.port}", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER"), cwd)
            val app = DesktopApp(args, scope, home, claudeHome = tmp.newFolder("claude-home").toPath())
            val runtime = app.openSession()
            runtime.send(ClientMsgId("first"), listOf(TextPart("hello")))
            withTimeout(10_000) { runtime.awaitIdle() }
            val checkpoint = runtime.state.value.checkpoints.single().event
            assertNotNull(checkpoint.manifest)
            assertNull(checkpoint.reason)
            Files.write(cwd.resolve("x"), "after".encodeToByteArray())
            app.closeSession(runtime).join()
            val fresh = DesktopApp(args.copy(resume = runtime.sessionId.value), scope, home, claudeHome = home.resolve("claude-home"))
            val reopened = fresh.openSession()
            val result = fresh.snapshots.store(reopened.state.value.created.cwd).restore(checkpoint.manifest, emptyList())
            assertTrue(result.skipped.isEmpty()); assertEquals("before", Files.readAllBytes(cwd.resolve("x")).decodeToString())
            assertTrue(fresh.snapshots.store(reopened.state.value.created.cwd).snapshot(dev.min.agent.spi.SnapshotBudget.DESKTOP) is SnapshotResult.Taken)
            fresh.closeSession(reopened).join()
            assertTrue(Files.walk(home.resolve("snapshots")).use { paths -> paths.anyMatch { it.fileName.toString() == "gc-time" } })
        } finally { scope.cancel(); server.stop(0) }
    }
}
