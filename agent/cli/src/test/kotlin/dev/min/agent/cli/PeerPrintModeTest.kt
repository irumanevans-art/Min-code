package dev.min.agent.cli

import dev.min.agent.kernel.FilePeerTransport
import dev.min.agent.kernel.PeerEntry
import dev.min.agent.kernel.PeerEnvelope
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `-p` 会话也能收跨会话消息（CC cross-session-messaging「Non-interactive sessions」）：它像交互会话一样登记、有人取信，
 * 所以别的会话能在名单里看到它、给它发消息；`--bare` 不登记（原文：bare 模式不绑收件 socket，列表里没有它，也收不了信）。
 */
class PeerPrintModeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    private fun home(): Path = Files.createDirectories(tmp.root.toPath().resolve("home"))
    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()) + extra

    private fun app(home: Path, vararg extra: String): DesktopApp {
        val work = work()
        return DesktopApp(parseArgs(argv(work, *extra), work), newScope(), home, claudeHome = home.resolve("claude-home"))
    }

    private fun registryFile(home: Path, id: String) = home.resolve("peers").resolve("sessions").resolve("$id.json")

    @Test fun `a print-mode session registers itself so other sessions can see it`() = runBlocking {
        val home = home()
        val session = app(home, "-p", "hello").openSession()
        assertTrue(Files.exists(registryFile(home, session.sessionId.value)))
    }

    @Test fun `a print-mode session takes a message from another session through its heartbeat`() = runBlocking {
        val home = home()
        val session = app(home, "-p", "hello").openSession()
        // 另一个进程里的会话：登记在同一个用户目录下，给它发一封信
        val sender = FilePeerTransport(home.resolve("peers"), pid = ProcessHandle.current().pid())
        sender.publish(PeerEntry("s_sender", "sender", "/elsewhere", busy = false))
        assertTrue(sender.deliver(session.sessionId.value, PeerEnvelope("m1", from = "s_sender", to = session.sessionId.value, text = "来自别的会话", senderBypass = false, sentAtMs = 1L)))

        val store = FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })
        val got = withTimeout(30_000) {
            var found: List<InputReceived> = emptyList()
            while (found.isEmpty()) {
                found = store.peek(session.sessionId)!!.read().toList().mapNotNull { it.event as? InputReceived }.filter { it.origin is InputOrigin.Peer }
                if (found.isEmpty()) delay(200)
            }
            found
        }
        assertTrue(got.single().parts.toString(), got.single().parts.toString().contains("来自别的会话"))
    }

    @Test fun `a bare print-mode session does not register and cannot be messaged`() = runBlocking {
        val home = home()
        val session = app(home, "--bare", "-p", "hello").openSession()
        assertFalse(Files.exists(registryFile(home, session.sessionId.value)))
    }

    @Test fun `an interactive session still registers`() = runBlocking {
        val home = home()
        val session = app(home).openSession()
        assertTrue(Files.exists(registryFile(home, session.sessionId.value)))
    }
}
