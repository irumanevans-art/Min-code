package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 交互里 skills 的热更新与 `/reload-skills`（skills / commands 文档），以及 `/fork` 对 `--tools` 白名单会话的拒绝（agent-view 文档）。
 * 真 DesktopApp + 本地假模型，整条 `interact` 循环：输入一行、等回合收尾、改磁盘、再输入下一行。
 */
class SkillsLiveCliTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private class ModelServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/chat/completions") { exchange ->
                bodies += exchange.requestBody.use { it.readBytes().decodeToString() }
                val chunk = buildJsonObject {
                    put("id", "r")
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("role", "assistant"); put("content", "好的") })
                        put("finish_reason", "stop")
                    }) })
                }
                val payload = ("data: $chunk\n\ndata: [DONE]\n\n").encodeToByteArray()
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    /** 一步：输入一行（[Line]）、等假模型累计收到 n 个请求并让回合收尾（[AwaitRequests]）、或动一下磁盘（[Disk]） */
    private sealed interface Step
    private class Line(val text: String) : Step
    private class AwaitRequests(val count: Int) : Step
    private class Disk(val action: () -> Unit) : Step

    private class Run(val stderr: String, val app: DesktopApp, val original: dev.min.agent.kernel.SessionRuntime)

    private fun drive(server: ModelServer, work: Path, steps: List<Step>, vararg extra: String): Run = runBlocking {
        withTimeout(90_000) {
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", server.baseUrl,
                    "--mode", "bypassPermissions", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()) + extra,
                work,
            )
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val original = app.openSession()
            val writer = PipedWriter()
            val reader = BufferedReader(PipedReader(writer))
            val feeder = Thread {
                steps.forEach { step ->
                    when (step) {
                        is Line -> { writer.write(step.text + "\n"); writer.flush(); Thread.sleep(200) }
                        is AwaitRequests -> {
                            val deadline = System.currentTimeMillis() + 30_000
                            while (server.bodies.size < step.count && System.currentTimeMillis() < deadline) Thread.sleep(50)
                            Thread.sleep(500) // 回合收尾、落盘，循环回到提示符
                        }
                        is Disk -> step.action()
                    }
                }
                Thread.sleep(300)
                writer.write("/exit\n"); writer.flush()
            }.also { it.isDaemon = true; it.start() }
            val originalErr = System.err
            val originalOut = System.out
            val err = ByteArrayOutputStream()
            System.setErr(PrintStream(err, true, "UTF-8"))
            System.setOut(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
            try {
                assertEquals(0, interact(app, original, args, scope, reader))
                feeder.join(5_000)
                Run(err.toString("UTF-8"), app, original)
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun skill(description: String, body: String) = "---\ndescription: $description\n---\n$body"

    @Test fun `a skill created while the session runs expands from the slash entry`() {
        ModelServer().use { server ->
            val work = work()
            val run = drive(
                server, work, listOf(
                    Line("hello"), AwaitRequests(1),
                    Disk { write(work.resolve(".claude/skills/fresh/SKILL.md"), skill("brand new", "FRESH BODY for \$ARGUMENTS")) },
                    Line("/fresh 海"), AwaitRequests(2),
                ),
            )
            assertEquals(run.stderr, 2, server.bodies.size)
            assertTrue(server.bodies[1], server.bodies[1].contains("FRESH BODY for 海"))
        }
    }

    @Test fun `a skill whose text was edited expands with the new text, not the one read at startup`() {
        ModelServer().use { server ->
            val work = work()
            write(work.resolve(".claude/skills/tool/SKILL.md"), skill("a tool", "OLD BODY"))
            drive(
                server, work, listOf(
                    Line("hello"), AwaitRequests(1),
                    Disk { write(work.resolve(".claude/skills/tool/SKILL.md"), skill("a tool", "NEW BODY")) },
                    Line("/tool"), AwaitRequests(2),
                ),
            )
            assertTrue(server.bodies[1], server.bodies[1].contains("NEW BODY") && !server.bodies[1].contains("OLD BODY"))
        }
    }

    @Test fun `a skill deleted while the session runs no longer expands`() {
        ModelServer().use { server ->
            val work = work()
            write(work.resolve(".claude/skills/gone/SKILL.md"), skill("short lived", "GONE BODY"))
            val run = drive(
                server, work, listOf(
                    Line("hello"), AwaitRequests(1),
                    Disk { Files.delete(work.resolve(".claude/skills/gone/SKILL.md")) },
                    Line("/gone now"),
                ),
            )
            assertEquals(run.stderr, 1, server.bodies.size)
            assertTrue(run.stderr, run.stderr.contains("没有名为 /gone"))
        }
    }

    @Test fun `slash reload-skills picks up a new skill and a new command and prints the counts`() {
        ModelServer().use { server ->
            val work = work()
            val run = drive(
                server, work, listOf(
                    Disk {
                        write(work.resolve(".claude/skills/fresh/SKILL.md"), skill("brand new", "x"))
                        write(work.resolve(".claude/commands/ship.md"), skill("ship it", "SHIP BODY"))
                    },
                    Line("/reload-skills"),
                    Line("/ship"), AwaitRequests(1),
                ),
            )
            assertTrue(run.stderr, run.stderr.contains("新增 2（") && run.stderr.contains("fresh") && run.stderr.contains("ship"))
            assertTrue(server.bodies.single(), server.bodies.single().contains("SHIP BODY"))
        }
    }

    @Test fun `slash reload-skills is in the help text`() {
        assertTrue(INTERACTIVE_HELP.contains("/reload-skills"))
    }

    @Test fun `slash fork is refused for a session started with a tools allowlist, and nothing is copied`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf(Line("/fork 做点什么")), "--tools", "Read,Bash")
            assertEquals(0, server.bodies.size)
            assertTrue(run.stderr, run.stderr.contains("--tools") && run.stderr.contains("不能 /fork"))
            assertEquals("没有复制出另一个会话", 0, runBlocking { run.app.hub().online.value.count { it != run.original.sessionId } })
        }
    }

    @Test fun `slash branch still works for a session started with a tools allowlist`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf(Line("/branch")), "--tools", "Read,Bash")
            assertTrue(run.stderr, !run.stderr.contains("不能 /fork"))
        }
    }
}
