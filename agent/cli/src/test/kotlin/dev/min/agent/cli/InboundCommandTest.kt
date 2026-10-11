package dev.min.agent.cli

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import dev.min.agent.kernel.FilePeerTransport
import dev.min.agent.kernel.PeerEntry
import dev.min.agent.kernel.PeerEnvelope
import dev.min.agent.kernel.PeerInboundPolicy
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/inbound`：终端里的收件设置与批准（CC 的 `/config` 行「Messages from your other sessions」与待批准消息的批准框）。
 * Min 的终端没有 `/config` 面板，取舍是一个专用命令：
 * - `/inbound accept|hold|refuse|unset` 写用户设置里的 `crossSessionInbound`（CC：这一行把值写进用户设置），当场生效；
 *   managed 设置或 `--settings` 已经设了这个键时不写（CC：这一行此时不出现，用户层的值反正不会生效）；
 * - `/inbound` 无参：当前口径与等批准的消息；`/inbound approve|deny [id|all]` 就是批准框的两个按钮。
 */
class InboundCommandTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun layer(source: ConfigSource, key: String = "crossSessionInbound", value: String = "hold") = SettingsLayer(
        source, GuestPath("/x/$key"), GuestPath("/x"), JsonObject(mapOf(key to JsonPrimitive(value))),
    )

    // ---- 写设置 ----

    @Test fun `the value is written into the user settings file next to the keys already there`() {
        val file = tmp.root.toPath().resolve("claude/.claude/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"model":"sonnet"}""")
        writeCrossSessionInbound(file, PeerInboundPolicy.Refuse)
        val obj = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals("refuse", (obj["crossSessionInbound"] as JsonPrimitive).content)
        assertEquals("sonnet", (obj["model"] as JsonPrimitive).content)
    }

    @Test fun `unset removes the key again and a missing file is created`() {
        val file = tmp.root.toPath().resolve("fresh/.claude/settings.json")
        writeCrossSessionInbound(file, PeerInboundPolicy.Hold)
        assertTrue(Files.readString(file).contains("hold"))
        writeCrossSessionInbound(file, PeerInboundPolicy.Auto)
        assertFalse(Files.readString(file).contains("crossSessionInbound"))
    }

    @Test fun `a settings file that is not a json object is never overwritten`() {
        val file = tmp.root.toPath().resolve("bad/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "[1,2]")
        try {
            writeCrossSessionInbound(file, PeerInboundPolicy.Accept)
            fail("应拒绝覆盖")
        } catch (e: IllegalStateException) {
            assertEquals("[1,2]", Files.readString(file))
        }
    }

    @Test fun `managed settings and the settings flag lock the key, user project and local do not`() {
        assertEquals("managed", inboundLockedBy(listOf(layer(ConfigSource.User), layer(ConfigSource.Managed))))
        assertEquals("--settings", inboundLockedBy(listOf(layer(ConfigSource.Cli, value = "accept"))))
        assertNull(inboundLockedBy(listOf(layer(ConfigSource.User), layer(ConfigSource.Project), layer(ConfigSource.Local))))
        assertNull("别的键不算", inboundLockedBy(listOf(layer(ConfigSource.Managed, key = "model"))))
    }

    // ---- 终端里 ----

    private fun argv(work: Path): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString())

    private class Terminal(val home: Path, val sender: FilePeerTransport, val sessionId: dev.min.agent.protocol.SessionId, val app: DesktopApp)

    /** 起一个交互会话，在另一个线程里按 [script] 往 stdin 写行并等条件；返回终端上给人看的全部输出（stderr + stdout） */
    private fun terminal(script: (Terminal, (String) -> Unit, (String, () -> Boolean) -> Unit) -> Unit): String = runBlocking {
        withTimeout(90_000) {
            val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(argv(work), work)
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val runtime = app.openSession()
            val sender = FilePeerTransport(home.resolve("peers"), pid = ProcessHandle.current().pid())
            sender.publish(PeerEntry("s_sender", "sender", "/elsewhere", busy = false))
            val writer = PipedWriter()
            val reader = BufferedReader(PipedReader(writer))
            val term = Terminal(home, sender, runtime.sessionId, app)
            var failure: Throwable? = null
            val feeder = Thread {
                try {
                    script(term, { line -> writer.write(line + "\n"); writer.flush() }) { what, cond ->
                        val deadline = System.currentTimeMillis() + 30_000
                        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                        if (!cond()) throw AssertionError("等不到：$what")
                    }
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    writer.write("/exit\n"); writer.flush()
                }
            }.also { it.isDaemon = true; it.start() }
            val err = ByteArrayOutputStream()
            val out = ByteArrayOutputStream()
            val originalErr = System.err
            val originalOut = System.out
            System.setErr(PrintStream(err, true, "UTF-8"))
            System.setOut(PrintStream(out, true, "UTF-8"))
            try {
                assertEquals(0, interact(app, runtime, args, scope, reader))
                feeder.join(5_000)
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
            failure?.let { throw it }
            err.toString("UTF-8") + out.toString("UTF-8")
        }
    }

    private fun Terminal.deliver(id: String, text: String, bypass: Boolean = false) {
        assertTrue(sender.deliver(sessionId.value, PeerEnvelope(id, from = "s_sender", to = sessionId.value, text = text, senderBypass = bypass, sentAtMs = 1L)))
    }

    private fun Terminal.peerInputs(): List<InputReceived> = runBlocking {
        FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() }).peek(sessionId)!!.read().toList()
            .mapNotNull { it.event as? InputReceived }.filter { it.origin is InputOrigin.Peer }
    }

    private fun Terminal.userSettings(): String = Files.readString(app.settingsUserFile())

    @Test fun `slash inbound refuse writes the user setting and the next message is refused at once`() {
        var settings = ""
        val text = terminal { t, say, waitFor ->
            say("/inbound refuse")
            waitFor("设置写入") { Files.exists(t.app.settingsUserFile()) }
            settings = t.userSettings()
            t.deliver("m1", "还能收吗")
            // 拒收：什么也没进对话，发件人那边收到结论
            waitFor("拒收结论") { t.sender.take("s_sender").any { it.envelope.kind == PeerEnvelope.Kind.Outcome && it.envelope.text.contains("不接收") } }
            assertTrue(t.peerInputs().isEmpty())
        }
        assertTrue(settings, settings.contains("\"crossSessionInbound\"") && settings.contains("refuse"))
        assertTrue(text, text.contains("refuse"))
    }

    @Test fun `a held message is announced in the terminal and approving it delivers it`() {
        val text = terminal { t, say, waitFor ->
            say("/inbound hold")
            waitFor("设置写入") { Files.exists(t.app.settingsUserFile()) }
            t.deliver("m2", "请批准我这条")
            waitFor("消息挂起") { runBlocking { t.app.hub().heldPeerMessages(t.sessionId).isNotEmpty() } }
            say("/inbound")
            Thread.sleep(500)
            say("/inbound approve")
            waitFor("消息进对话") { t.peerInputs().isNotEmpty() }
        }
        assertTrue("挂起时终端上提了一句", text.contains("[跨会话]"))
        assertTrue(text, text.contains("请批准我这条"))
        assertTrue(text, text.contains("m2"))
        assertTrue(text, text.contains("批准"))
    }

    @Test fun `denying a held message sends the sender a verdict and nothing reaches the conversation`() {
        terminal { t, say, waitFor ->
            say("/inbound hold")
            waitFor("设置写入") { Files.exists(t.app.settingsUserFile()) }
            t.deliver("m3", "我会被拒")
            waitFor("消息挂起") { runBlocking { t.app.hub().heldPeerMessages(t.sessionId).isNotEmpty() } }
            say("/inbound deny all")
            waitFor("发件人收到结论") { t.sender.take("s_sender").any { it.envelope.kind == PeerEnvelope.Kind.Outcome && it.envelope.text.contains("拒收") } }
            assertTrue(t.peerInputs().isEmpty())
        }
    }

    @Test fun `an unknown value is refused without touching the settings file`() {
        var wrote = true
        val text = terminal { t, say, _ ->
            say("/inbound maybe")
            Thread.sleep(800)
            wrote = Files.exists(t.app.settingsUserFile())
        }
        assertFalse(wrote)
        assertTrue(text, text.contains("accept") && text.contains("hold") && text.contains("refuse"))
    }
}
