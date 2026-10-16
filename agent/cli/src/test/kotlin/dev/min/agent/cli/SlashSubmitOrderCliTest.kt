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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CC 2.1.299：UserPromptSubmit 先于斜杠命令 / skill 展开。整条 `interact` 循环（真 DesktopApp、真 shell 跑 hook 与预执行、本地假模型）：
 * 命令正文里的 `` !`touch 标记文件` `` 在 hook 拦下时不能跑（攻击用例）；放行时 hook 收到的是用户敲的原文，模型收到展开后的文字；
 * 同一条 prompt 的 UserPromptSubmit 只跑一次；skill 自己 frontmatter 里的 UserPromptSubmit 从下一条 prompt 起才生效。
 */
class SlashSubmitOrderCliTest {
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

    private sealed interface Step
    private class Line(val text: String) : Step
    private class AwaitRequests(val count: Int) : Step
    private class Pause(val ms: Long) : Step

    private class Run(val stderr: String)

    private val home: Path get() = Files.createDirectories(tmp.root.toPath().resolve("home"))
    private val work: Path get() = Files.createDirectories(tmp.root.toPath().resolve("work"))

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    /** hook 脚本：把收到的 stdin 记一行到 [log]，再往 [order] 记一笔 [tag]；prompt 里带 BLOCKME 就 exit 2；否则 stdout 给一段上下文 */
    private fun script(name: String, log: String, tag: String, withContext: Boolean) = write(
        work.resolve("scripts/$name.sh"),
        "#!/bin/sh\nin=\$(cat)\nprintf '%s\\n' \"\$in\" >> \"\$CLAUDE_PROJECT_DIR/$log\"\nprintf '%s\\n' '$tag' >> \"\$CLAUDE_PROJECT_DIR/order.log\"\n" +
            "case \"\$in\" in *BLOCKME*) echo 'blocked by policy' >&2; exit 2;; esac\n" + (if (withContext) "echo 'extra context from hook'\n" else ""),
    )

    private fun hookEntry(event: String, script: String) =
        "\"$event\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"sh \\\"\$CLAUDE_PROJECT_DIR/scripts/$script.sh\\\"\"}]}]"

    /** 用户层 settings：UserPromptSubmit（记 submit.log）与 UserPromptExpansion（记 expansion.log）两个 hook；命令 /danger 正文里预执行 touch */
    private fun setUp() {
        script("submit", "submit.log", "submit", withContext = true)
        script("expansion", "expansion.log", "expansion", withContext = false)
        write(
            home.resolve("claude-home/.claude/settings.json"),
            "{\"hooks\":{" + hookEntry("UserPromptSubmit", "submit") + "," + hookEntry("UserPromptExpansion", "expansion") + "}}",
        )
        write(work.resolve(".claude/commands/danger.md"), "---\ndescription: dangerous\n---\nPRE-EXEC OUTPUT: !`touch pre-exec-marker.txt` ARGS=\$ARGUMENTS")
    }

    private fun lines(name: String): List<String> =
        work.resolve(name).let { if (Files.exists(it)) Files.readAllLines(it).filter(String::isNotBlank) else emptyList() }

    private fun drive(server: ModelServer, steps: List<Step>): Run = runBlocking {
        withTimeout(120_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", server.baseUrl, "--trust",
                    "--mode", "bypassPermissions", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()),
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
                        is Pause -> Thread.sleep(step.ms)
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
                Run(err.toString("UTF-8"))
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    @Test fun `a blocking UserPromptSubmit stops the command's embedded shell and the model never hears of it`() {
        setUp()
        ModelServer().use { server ->
            val run = drive(server, listOf(Line("/danger BLOCKME"), Pause(2_500)))
            assertFalse("hook 拦下，预执行的 touch 不能跑", Files.exists(work.resolve("pre-exec-marker.txt")))
            assertTrue("模型没收到任何请求：${server.bodies}", server.bodies.isEmpty())
            assertTrue(run.stderr, run.stderr.contains("blocked by policy"))
            // hook 收到的是用户敲的原文，不是展开后的正文
            val seen = lines("submit.log")
            assertEquals(seen.toString(), 1, seen.size)
            assertTrue(seen.single(), seen.single().contains("/danger BLOCKME") && !seen.single().contains("PRE-EXEC OUTPUT"))
            // 被拦下，后面的 UserPromptExpansion 一次都不问
            assertEquals(listOf("submit"), lines("order.log"))
            assertTrue(lines("expansion.log").isEmpty())
        }
    }

    @Test fun `a passing UserPromptSubmit sees the typed line once, then the pre-run happens and the model gets the expanded text`() {
        setUp()
        ModelServer().use { server ->
            drive(server, listOf(Line("/danger fine"), AwaitRequests(1)))
            assertTrue("放行后预执行照跑", Files.exists(work.resolve("pre-exec-marker.txt")))
            assertEquals(1, server.bodies.size)
            assertTrue(server.bodies.single(), server.bodies.single().contains("ARGS=fine"))
            assertTrue("hook 的 additionalContext 照旧交给模型：${server.bodies.single()}", server.bodies.single().contains("extra context from hook"))
            // 同一条 prompt，UserPromptSubmit 只跑一次，看到的是原文
            val seen = lines("submit.log")
            assertEquals(seen.toString(), 1, seen.size)
            assertTrue(seen.single(), seen.single().contains("/danger fine") && !seen.single().contains("ARGS="))
            // 先后：UserPromptSubmit（原文）→ UserPromptExpansion → 预执行 → 发送
            assertEquals(listOf("submit", "expansion"), lines("order.log"))
        }
    }

    @Test fun `a plain message still goes through UserPromptSubmit exactly once, inside the kernel`() {
        setUp()
        ModelServer().use { server ->
            drive(server, listOf(Line("hello there"), AwaitRequests(1)))
            val seen = lines("submit.log")
            assertEquals(seen.toString(), 1, seen.size)
            assertTrue(seen.single(), seen.single().contains("hello there"))
            assertTrue(server.bodies.single(), server.bodies.single().contains("extra context from hook"))
            assertTrue("不是斜杠命令就没有展开这一步", lines("expansion.log").isEmpty())
        }
    }

    @Test fun `a skill's own UserPromptSubmit hook skips the prompt that invokes it and runs from the next one`() {
        setUp()
        script("skillhook", "skill.log", "skill", withContext = false)
        write(
            work.resolve(".claude/skills/hooked/SKILL.md"),
            "---\ndescription: hooked\nhooks:\n  UserPromptSubmit:\n    - hooks:\n        - type: command\n          command: 'sh \"\$CLAUDE_PROJECT_DIR/scripts/skillhook.sh\"'\n---\nHOOKED SKILL BODY \$ARGUMENTS",
        )
        ModelServer().use { server ->
            drive(server, listOf(Line("/hooked go"), AwaitRequests(1), Line("second message"), AwaitRequests(2)))
            assertTrue(server.bodies[0], server.bodies[0].contains("HOOKED SKILL BODY go"))
            val skillSeen = lines("skill.log")
            assertEquals("skill 的 hook 只见到下一条：$skillSeen", 1, skillSeen.size)
            assertTrue(skillSeen.single(), skillSeen.single().contains("second message") && !skillSeen.single().contains("/hooked"))
            // 用户层的 UserPromptSubmit 两条都见到、各一次
            val seen = lines("submit.log")
            assertEquals(seen.toString(), 2, seen.size)
            assertTrue(seen[0].contains("/hooked go") && seen[1].contains("second message"))
        }
    }
}
