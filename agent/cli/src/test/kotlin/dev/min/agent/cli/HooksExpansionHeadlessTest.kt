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
import java.io.PrintStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UserPromptExpansion 走真正的无头入口（`-p "/名字 参数"` → interpretInput → promptExpansionBlock → 发送）：
 * 真 DesktopApp + 本地假模型。样本 65 的 settings / 脚本原样装进用户层。
 * 内核侧的细节（matcher、上下文挂靠）在 [HooksDecisionKernelTest]，这里只钉「宿主在展开之后、发送之前真的问了 hook」。
 */
class HooksExpansionHeadlessTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() = scopes.forEach { it.cancel() }

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
                        put("delta", buildJsonObject { put("role", "assistant"); put("content", "好") })
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

    private class Run(val exit: Int, val stderr: String)

    private fun sampleDir(name: String): Path =
        Paths.get(URI(javaClass.classLoader.getResource("hooks-compat/$name")!!.toString()))

    private fun write(base: Path, rel: String, text: String) {
        val file = base.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    /** 工作区里放一个 skill、用户层装 hooks；`sample` 非空时装样本的 settings 与脚本，否则用 [script] 自己写一个 */
    private fun setUp(home: Path, work: Path, sample: String? = null, script: String? = null) {
        write(work, ".claude/skills/example-skill/SKILL.md", "---\ndescription: example\n---\nDo the example thing: \$ARGUMENTS")
        val claudeHome = Files.createDirectories(home.resolve("claude-home"))
        if (sample != null) {
            val dir = sampleDir(sample)
            Files.walk(dir.resolve("scripts")).use { files ->
                files.filter(Files::isRegularFile).forEach { file ->
                    val dest = work.resolve("scripts").resolve(dir.resolve("scripts").relativize(file).toString())
                    Files.createDirectories(dest.parent)
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.createDirectories(claudeHome.resolve(".claude"))
            Files.copy(dir.resolve("settings.json"), claudeHome.resolve(".claude/settings.json"), StandardCopyOption.REPLACE_EXISTING)
        } else {
            write(work, "scripts/expansion.sh", "#!/bin/sh\ncat > /dev/null\n$script\n")
            write(
                claudeHome, ".claude/settings.json",
                """{"hooks":{"UserPromptExpansion":[{"matcher":"example-skill","hooks":[{"type":"command","command":"sh \"${'$'}CLAUDE_PROJECT_DIR/scripts/expansion.sh\""}]}]}}""",
            )
        }
    }

    private fun headless(home: Path, work: Path, server: ModelServer, prompt: String, mode: String = "bypassPermissions", trust: Boolean = false, approve: String = "no"): Run = runBlocking {
        withTimeout(60_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf(
                    "--provider", "desktop-test", "--model", "scripted", "--base-url", server.baseUrl,
                    "--wire", "openai_chat", "--mode", mode, "--approve", approve,
                    "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
                    "--cwd", work.toString(), "-p", prompt,
                ) + if (trust) arrayOf("--trust") else emptyArray(),
                work,
            )
            val err = ByteArrayOutputStream()
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val runtime = app.openSession()
            try {
                val exit = runHeadless(
                    app, runtime, args, scope, BufferedReader(StringReader("")),
                    PrintStream(ByteArrayOutputStream(), true, "UTF-8"), PrintStream(err, true, "UTF-8"),
                )
                Run(exit, err.toString("UTF-8"))
            } finally {
                app.exitSession(runtime).join()
            }
        }
    }

    private fun dirs(): Pair<Path, Path> =
        Files.createDirectories(tmp.root.toPath().resolve("home")) to Files.createDirectories(tmp.root.toPath().resolve("work"))

    @Test fun R2_headless_local_git_tracking_and_stderr() {
        val (home, work) = dirs()
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", work.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        git("init")
        git("config", "core.autocrlf", "false")
        write(work, ".claude/settings.local.json", """{"permissions":{"allow":["Bash(curl *)"],"additionalDirectories":["../external"]}}""")
        ModelServer().use { server ->
            val own = headless(home, work, server, "just talk", mode = "default")
            assertEquals(0, own.exit)
            assertTrue(own.stderr, !own.stderr.contains("this workspace has not been trusted"))
            git("add", ".claude/settings.local.json")
            val tracked = headless(home, work, server, "just talk", mode = "default")
            assertEquals(0, tracked.exit)
            assertTrue(tracked.stderr, tracked.stderr.contains("this workspace has not been trusted"))
            val accepted = headless(home, work, server, "just talk", mode = "default", trust = true)
            assertEquals(0, accepted.exit)
            assertTrue(accepted.stderr, !accepted.stderr.contains("this workspace has not been trusted"))
        }
    }

    @Test fun BUG_headless_warns_about_untrusted_grants_and_automatic_human_approval() {
        val (home, work) = dirs()
        write(work, ".claude/settings.json", """{"permissions":{"allow":["Bash(*)"],"additionalDirectories":["/external"]}}""")
        ModelServer().use { server ->
            val untrusted = headless(home, work, server, "just talk", mode = "auto", approve = "yes")
            assertEquals(0, untrusted.exit)
            assertTrue(untrusted.stderr, untrusted.stderr.contains("this workspace has not been trusted"))
            assertTrue(untrusted.stderr, untrusted.stderr.contains("转人的调用会被自动批准"))
            val trusted = headless(home, work, server, "just talk", mode = "auto", trust = true)
            assertEquals(0, trusted.exit)
            assertTrue(trusted.stderr, !trusted.stderr.contains("this workspace has not been trusted"))
            assertTrue(trusted.stderr, !trusted.stderr.contains("转人的调用会被自动批准"))
        }
    }

    @Test
    fun `a blocking UserPromptExpansion hook stops the slash command before the model sees it (sample 65)`() {
        val (home, work) = dirs()
        setUp(home, work, sample = "65-userpromptexpansion-decision")
        ModelServer().use { server ->
            val run = headless(home, work, server, "/example-skill arg1 arg2")
            assertEquals(2, run.exit)
            assertTrue(run.stderr, run.stderr.contains("This slash command is not available"))
            assertEquals("被拦下的命令没有发给模型", 0, server.bodies.size)
        }
    }

    @Test
    fun `an allowing hook adds its context next to the expanded prompt, a non-matching command skips the hook`() {
        val (home, work) = dirs()
        setUp(home, work, script = """printf '%s' '{"hookSpecificOutput":{"hookEventName":"UserPromptExpansion","additionalContext":"Checklist before deploy"}}'""")
        ModelServer().use { server ->
            val run = headless(home, work, server, "/example-skill arg1")
            assertEquals(run.stderr, 0, run.exit)
            assertEquals(1, server.bodies.size)
            val body = server.bodies.single()
            assertTrue(body, body.contains("Do the example thing: arg1"))
            assertTrue(body, body.contains("Checklist before deploy"))
        }
    }

    @Test
    fun `plain text and unmatched commands never reach the hook`() {
        val (home, work) = dirs()
        setUp(home, work, script = "echo ran > \"${'$'}CLAUDE_PROJECT_DIR/hook-ran\"; exit 2")
        write(work, ".claude/skills/other/SKILL.md", "---\ndescription: other\n---\nOther body")
        ModelServer().use { server ->
            assertEquals(0, headless(home, work, server, "just talk").exit)
            assertEquals(0, headless(home, work, server, "/other now").exit)
            assertTrue("matcher 是 example-skill，别的命令和普通输入不触发", !Files.exists(work.resolve("hook-ran")))
        }
    }

    /**
     * 交互模式（不带 -p）：stdin 一行一句，走 Main.say 那条路。小尾巴：交互路径的 UserPromptExpansion 之前只有 -p 的测试。
     * 返回 interact 的退出码与终端（stderr）上给人看的内容
     */
    private fun interactive(home: Path, work: Path, server: ModelServer, lines: List<String>): Run = runBlocking {
        withTimeout(60_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf(
                    "--provider", "desktop-test", "--model", "scripted", "--base-url", server.baseUrl,
                    "--wire", "openai_chat", "--mode", "bypassPermissions",
                    "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
                    "--cwd", work.toString(),
                ),
                work,
            )
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val runtime = app.openSession()
            val originalErr = System.err
            val originalOut = System.out
            val err = ByteArrayOutputStream()
            System.setErr(PrintStream(err, true, "UTF-8"))
            System.setOut(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
            try {
                val exit = interact(app, runtime, args, scope, BufferedReader(StringReader(lines.joinToString("\n", postfix = "\n"))))
                Run(exit, err.toString("UTF-8"))
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    @Test
    fun `in the interactive loop a blocking UserPromptExpansion hook stops the slash command and shows the reason (sample 65)`() {
        val (home, work) = dirs()
        setUp(home, work, sample = "65-userpromptexpansion-decision")
        ModelServer().use { server ->
            val run = interactive(home, work, server, listOf("/example-skill arg1 arg2", "/exit"))
            assertEquals(run.stderr, 0, run.exit)
            assertTrue(run.stderr, run.stderr.contains("This slash command is not available"))
            assertEquals("被拦下的命令没有发给模型", 0, server.bodies.size)
        }
    }

    @Test
    fun `in the interactive loop an allowing hook adds its context and plain text never reaches the hook`() {
        val (home, work) = dirs()
        setUp(home, work, script = "echo ran >> \"${'$'}CLAUDE_PROJECT_DIR/hook-ran\"; printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptExpansion\",\"additionalContext\":\"Checklist before deploy\"}}'")
        ModelServer().use { server ->
            val run = interactive(home, work, server, listOf("just talk", "/example-skill arg1", "/exit"))
            assertEquals(run.stderr, 0, run.exit)
            assertEquals("普通输入和斜杠命令各发出一次", 2, server.bodies.size)
            val expanded = server.bodies.last()
            assertTrue(expanded, expanded.contains("Do the example thing: arg1"))
            assertTrue(expanded, expanded.contains("Checklist before deploy"))
            assertEquals("hook 只为匹配的斜杠命令跑了一次", 1, Files.readAllLines(work.resolve("hook-ran")).size)
        }
    }
}
