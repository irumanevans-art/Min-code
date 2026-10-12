package dev.min.agent.cli

import dev.min.agent.spi.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * MessageDisplay（官方 hooks 页）经真 CLI：真 DesktopApp、本地假模型、真 shell 跑 hook。
 * -p 的 text 输出：最终回复只触发一次（index 0、final true、全文）、stdout 显示替换后的文字；json 输出是给程序读的，永远是原文。
 * 交互终端：流式正文按完成的行上屏，显示的是替换后的文字；会话日志里的助手文字仍是原文（模型下一轮看到的也是原文）。
 */
class MessageDisplayCliTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    @After fun stop() { scopes.forEach { it.cancel() } }

    private val original = "first line\nsecond line\nlast tail"

    /** 把整个 stdin 读掉（hook 的输入落进 [seen]），再输出替换文字 */
    private fun hookSettings(seen: Path): String {
        val s = seen.toString().replace('\\', '/')
        val json = "{\\\"hookSpecificOutput\\\":{\\\"hookEventName\\\":\\\"MessageDisplay\\\",\\\"displayContent\\\":\\\"[SHOWN]\\\"}}"
        return """{"autoMemoryEnabled":false,"hooks":{"MessageDisplay":[{"hooks":[{"type":"command","command":"cat >> '$s'; echo >> '$s'; printf '$json'"}]}]}}"""
    }

    private fun setup(name: String, seen: Path): Pair<Path, Path> {
        val home = Files.createDirectories(tmp.root.toPath().resolve("$name-home"))
        val settings = home.resolve("claude-home/.claude/settings.json")
        Files.createDirectories(settings.parent)
        Files.writeString(settings, hookSettings(seen))
        return home to Files.createDirectories(tmp.root.toPath().resolve("$name-work"))
    }

    private fun printRun(name: String, vararg extra: String): Triple<String, Path, ModelServer> {
        val seen = tmp.root.toPath().resolve("$name-seen.log")
        val (home, work) = setup(name, seen)
        val server = ModelServer { textResponse(original) }
        val text = runBlocking {
            withTimeout(120_000) {
                val args = parseArgs(baselineArgv(server, work) + arrayOf("-p", "hello") + extra, work)
                val out = ByteArrayOutputStream()
                val app = DesktopApp(args, scope(), home, claudeHome = home.resolve("claude-home"))
                app.checkKey()
                val runtime = app.openSession()
                try {
                    runHeadless(app, runtime, args, scope(), BufferedReader(StringReader("")), PrintStream(out, true, "UTF-8"), PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
                    out.toString("UTF-8")
                } finally {
                    app.exitSession(runtime).join()
                }
            }
        }
        return Triple(text, seen, server)
    }

    @Test fun `-p text output shows the replaced reply and fires the hook once with the whole message`() {
        val (stdout, seen, server) = printRun("pt")
        server.use {
            assertEquals("[SHOWN]", stdout.trim())
            val log = Files.readString(seen)
            assertEquals("每条消息只触发一次：$log", 1, Regex("\"hook_event_name\":\"MessageDisplay\"").findAll(log).count())
            assertTrue(log, log.contains("\"index\":0") && log.contains("\"final\":true"))
            assertTrue("全文在 delta：$log", log.contains("first line\\nsecond line\\nlast tail"))
        }
    }

    @Test fun `-p json output keeps the original, display is display only`() {
        val (stdout, _, server) = printRun("pj", "--output-format", "json")
        server.use {
            assertTrue(stdout, stdout.contains("first line\\nsecond line\\nlast tail"))
            assertFalse(stdout, stdout.contains("[SHOWN]"))
        }
    }

    @Test fun `the interactive terminal shows the replaced text per batch while the session log keeps the original`() {
        val seen = tmp.root.toPath().resolve("it-seen.log")
        val (home, work) = setup("it", seen)
        ModelServer { textResponse(original) }.use { server ->
            val result = runBlocking {
                withTimeout(120_000) {
                    val scope = scope()
                    val args = parseArgs(baselineArgv(server, work), work)
                    val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
                    app.checkKey()
                    val runtime = app.openSession()
                    val writer = PipedWriter()
                    val reader = BufferedReader(PipedReader(writer))
                    val out = ByteArrayOutputStream()
                    val feeder = Thread {
                        writer.write("hello\n"); writer.flush()
                        // 等两批都上了屏再退出（每批要真起一次 shell 跑 hook，机器忙时可能要几秒）
                        val deadline = System.currentTimeMillis() + 90_000
                        while (Regex("\\[SHOWN\\]").findAll(out.toString("UTF-8")).count() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(100)
                        Thread.sleep(500)
                        writer.write("/exit\n"); writer.flush()
                    }.also { it.isDaemon = true; it.start() }
                    val originalOut = System.out
                    val originalErr = System.err
                    System.setOut(PrintStream(out, true, "UTF-8"))
                    System.setErr(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
                    try {
                        assertEquals(0, interact(app, runtime, args, scope, reader))
                        feeder.join(5_000)
                    } finally {
                        System.setOut(originalOut)
                        System.setErr(originalErr)
                    }
                    val events: List<SessionEvent> = app.records(runtime.sessionId, 0).map { it.event }
                    out.toString("UTF-8") to events
                }
            }
            val (stdout, events) = result
            // 两个完成的行是一批、没满一行的尾巴是 final 一批：屏上是两次替换
            assertEquals(stdout, 2, Regex("\\[SHOWN\\]").findAll(stdout).count())
            assertFalse("原文没有上屏：$stdout", stdout.contains("first line"))
            val log = Files.readString(seen)
            assertEquals(2, Regex("\"hook_event_name\":\"MessageDisplay\"").findAll(log).count())
            assertTrue(log, log.contains("\"final\":false") && log.contains("\"final\":true"))
            // 日志里助手的文字仍是原文
            val assistant = events.filterIsInstance<dev.min.agent.spi.AssistantMessageCompleted>().joinToString(" ; ") { it.toString() }
            assertTrue("日志里助手的文字是原文：$assistant", assistant.contains("first line") && assistant.contains("last tail"))
            assertFalse("助手消息里没有替换文字", assistant.contains("[SHOWN]"))
        }
    }
}
