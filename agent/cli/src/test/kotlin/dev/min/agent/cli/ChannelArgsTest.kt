package dev.min.agent.cli

import dev.min.agent.ext.mcp.ChannelRef
import dev.min.agent.ext.mcp.ChannelRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--channels` / `--dangerously-load-development-channels`（CC channels）的 CLI 面：语法、只有点名的才开、
 * 开发参数要确认才生效。内核侧的订阅与投递见 kernel 的 McpChannelsTest。
 */
class ChannelArgsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun parse(vararg argv: String) = parseArgs(arrayOf("--model", "m", *argv), Path.of("/w"))

    private fun reader(text: String) = BufferedReader(StringReader(text))

    private fun ask(entries: List<ChannelRef>, input: String): Pair<Boolean, String> {
        val out = ByteArrayOutputStream()
        val ok = confirmDevelopmentChannels(entries, reader(input), PrintStream(out, true, "UTF-8"))
        return ok to out.toString("UTF-8")
    }

    @Test fun `nothing is on unless named`() {
        val args = parse()
        assertTrue(args.channels.isEmpty()); assertTrue(args.developmentChannels.isEmpty())
    }

    @Test fun `channels takes a space separated list of plugin entries and may repeat`() {
        val args = parse("--channels", "plugin:telegram@claude-plugins-official", "plugin:discord@claude-plugins-official", "--max-turns", "3", "--channels", "plugin:a@b")
        assertEquals(listOf(ChannelRef.Plugin("telegram", "claude-plugins-official"), ChannelRef.Plugin("discord", "claude-plugins-official"), ChannelRef.Plugin("a", "b")), args.channels)
        assertEquals(3, args.maxTurns)
    }

    @Test fun `a bare server entry is not accepted under --channels`() {
        val e = assertThrows(UsageException::class.java) { parse("--channels", "server:webhook") }
        assertTrue(e.message, e.message!!.contains("--dangerously-load-development-channels"))
    }

    @Test fun `the development flag takes plugin and server entries`() {
        val args = parse("--dangerously-load-development-channels", "server:webhook", "plugin:mine@local")
        assertEquals(listOf(ChannelRef.Server("webhook"), ChannelRef.Plugin("mine", "local")), args.developmentChannels)
        assertTrue("开发参数不混进 --channels", args.channels.isEmpty())
    }

    @Test fun `malformed or empty entries are usage errors`() {
        for (bad in listOf("telegram", "plugin:telegram", "mcp:x", "server:")) {
            assertThrows(bad, UsageException::class.java) { parse("--channels", bad) }
            assertThrows(bad, UsageException::class.java) { parse("--dangerously-load-development-channels", bad) }
        }
        assertThrows(UsageException::class.java) { parse("--channels") }
        assertThrows(UsageException::class.java) { parse("--channels", "--max-turns", "1") }
    }

    @Test fun `help describes both flags`() {
        val e = assertThrows(UsageException::class.java) { parse("--help") }
        assertTrue(e.message!!.contains("--channels") && e.message!!.contains("--dangerously-load-development-channels"))
    }

    // ---- 开发参数要确认 ----

    @Test fun `the confirmation lists every entry and only an explicit yes continues`() {
        val entries = listOf(ChannelRef.Server("webhook"), ChannelRef.Plugin("mine", "local"))
        for (yes in listOf("1\n", "y\n", "YES\n", "是\n")) {
            val (ok, out) = ask(entries, yes)
            assertTrue(yes, ok); assertTrue(out, out.contains("server:webhook") && out.contains("plugin:mine@local"))
        }
        for (no in listOf("2\n", "\n", "n\n", "", "好的吧\n")) assertFalse("[$no]", ask(entries, no).first)
    }

    @Test fun `no entries means no question`() {
        val (ok, out) = ask(emptyList(), "")
        assertTrue(ok); assertEquals("", out)
    }

    // ---- 交给 hub ----

    private fun app(vararg extra: String): DesktopApp {
        val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
        val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
        val argv = arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()) + extra
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DesktopApp(parseArgs(argv, work), scope, home, claudeHome = home.resolve("claude-home"))
    }

    @Test fun `development entries reach the hub only after confirmation`() = runBlocking { withTimeout(60_000) {
        val app = app("--channels", "plugin:a@m", "--dangerously-load-development-channels", "server:webhook")
        app.applyChannels(developmentConfirmed = false)
        assertEquals(listOf(ChannelRequest(ChannelRef.Plugin("a", "m"), development = false)), app.hub().defaultChannels)
        app.applyChannels(developmentConfirmed = true)
        assertEquals(
            listOf(ChannelRequest(ChannelRef.Plugin("a", "m"), false), ChannelRequest(ChannelRef.Server("webhook"), true)),
            app.hub().defaultChannels,
        )
    } }

    @Test fun `without flags the hub has no channels`() = runBlocking { withTimeout(60_000) {
        val app = app()
        app.applyChannels(developmentConfirmed = true)
        assertTrue(app.hub().defaultChannels.isEmpty())
    } }
}
