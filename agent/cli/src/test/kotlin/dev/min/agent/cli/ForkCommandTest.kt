package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.spi.InputReceived
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
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
 * `/fork [提示词]` 与 `/subtask <任务>`（CC commands 页）：
 * - `/fork 提示词`：把当前对话复制成另一个会话，**留在这里**，复制出来的那个立刻开始干这句话；不带提示词就等着第一句话。
 *   Min 没有后台会话，「后台」就是本进程里的另一个根会话（`/resume` 切过去看）；
 * - `/subtask 任务`：派一个继承完整对话的分身去做，结果回到本会话（走模型的 Agent 工具，subagent_type=fork、后台运行）。
 * 真 DesktopApp + 本地假模型；原会话的日志不被动。
 */
class ForkCommandTest {
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

    private class Run(val stderr: String, val app: DesktopApp, val original: dev.min.agent.kernel.SessionRuntime, val home: Path)

    /** 逐行喂 [lines]，等假模型收到 [expectedRequests] 个请求（0 = 不等）再喂 /exit，免得退出把后台的分身掐掉 */
    private fun drive(server: ModelServer, work: Path, lines: List<String>, expectedRequests: Int): Run = runBlocking {
        withTimeout(90_000) {
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", server.baseUrl,
                    "--mode", "bypassPermissions", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()),
                work,
            )
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val original = app.openSession()
            val writer = PipedWriter()
            val reader = BufferedReader(PipedReader(writer))
            val feeder = Thread {
                lines.forEach { writer.write(it + "\n"); writer.flush() }
                val deadline = System.currentTimeMillis() + 30_000
                while (server.bodies.size < expectedRequests && System.currentTimeMillis() < deadline) Thread.sleep(50)
                Thread.sleep(300) // 分身答完、落盘
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
                Run(err.toString("UTF-8"), app, original, home)
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    private fun work(git: Boolean = false): Path {
        val w = Files.createDirectories(tmp.root.toPath().resolve("work"))
        if (git) Files.createDirectories(w.resolve(".git"))
        return w
    }

    private suspend fun userInputs(home: Path, id: dev.min.agent.protocol.SessionId): List<String> =
        FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() }).peek(id)!!.read().toList()
            .mapNotNull { it.event as? InputReceived }.map { input -> input.parts.filterIsInstance<dev.min.agent.protocol.TextPart>().joinToString("") { it.text } }

    @Test fun `the fork prompt carries the worktree instruction only when the copy would edit the same checkout`() {
        assertEquals("写一首诗", forkPrompt("写一首诗", isolate = false))
        val isolated = forkPrompt("写一首诗", isolate = true)
        assertTrue(isolated, isolated.startsWith("写一首诗"))
        assertTrue(isolated, isolated.contains("EnterWorktree"))
    }

    @Test fun `the subtask prompt names the fork type and background run that the Agent tool really has`() {
        val text = subtaskPrompt("查一下登录为什么慢")
        assertTrue(text, text.contains("查一下登录为什么慢"))
        assertTrue(text, text.contains("subagent_type") && text.contains("fork"))
        assertTrue(text, text.contains("run_in_background"))
        // 说明里点名的字段必须是 Agent 工具真有的：工具改了名，这里要跟着红
        val schema = dev.min.agent.tools.AgentTool.inputSchema.toString()
        assertTrue(schema, schema.contains("\"subagent_type\"") && schema.contains("\"run_in_background\""))
    }

    @Test fun `slash fork with a prompt copies the session and the copy starts working on it while this session stays untouched`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf("/fork 写一首关于海的诗"), expectedRequests = 1)
            assertEquals(run.stderr, 1, server.bodies.size)
            assertTrue(server.bodies.single(), server.bodies.single().contains("写一首关于海的诗"))
            assertTrue(run.stderr, run.stderr.contains("/resume"))
            runBlocking {
                // 原会话没有收到这句话（它没有开回合）；分身是另一个根会话，来历指向原会话
                assertFalse(userInputs(run.home, run.original.sessionId).any { it.contains("写一首关于海的诗") })
                val copies = run.app.hub().online.value.filter { it != run.original.sessionId }
                assertEquals(1, copies.size)
                val copy = run.app.hub().get(copies.single())!!
                assertEquals(run.original.sessionId, copy.state.value.branched?.from)
                assertTrue(userInputs(run.home, copy.sessionId).any { it.contains("写一首关于海的诗") })
            }
        }
    }

    @Test fun `slash fork without a prompt copies the session and starts no turn`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf("/fork"), expectedRequests = 0)
            assertEquals(0, server.bodies.size)
            assertTrue(run.stderr, run.stderr.contains("等它的第一句话"))
            assertEquals("退出时只卸下了当前会话，分身仍在 Hub 里", 1, runBlocking { run.app.hub().online.value.count { it != run.original.sessionId } })
        }
    }

    @Test fun `a fork inside a git checkout is told to make its own worktree, one outside is not`() {
        ModelServer().use { server ->
            drive(server, work(git = true), listOf("/fork 重构一下"), expectedRequests = 1)
            assertTrue(server.bodies.single(), server.bodies.single().contains("EnterWorktree"))
        }
    }

    @Test fun `a fork outside any git checkout gets the prompt as typed`() {
        ModelServer().use { server ->
            drive(server, work(git = false), listOf("/fork 重构一下"), expectedRequests = 1)
            // 工具表里本来就有 EnterWorktree 这个名字；看的是用户消息里没有额外的指令句
            assertFalse(server.bodies.single(), server.bodies.single().contains("先用 EnterWorktree"))
        }
    }

    @Test fun `slash subtask asks this session to delegate a background fork and starts no other session`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf("/subtask 把 README 里的错别字找出来"), expectedRequests = 1)
            val body = server.bodies.single()
            assertTrue(body, body.contains("把 README 里的错别字找出来"))
            assertTrue(body, body.contains("run_in_background"))
            assertTrue("没有另起会话", runBlocking { run.app.hub().online.value.none { it != run.original.sessionId } })
        }
    }

    @Test fun `slash subtask without a task prints the usage and sends nothing`() {
        ModelServer().use { server ->
            val run = drive(server, work(), listOf("/subtask"), expectedRequests = 0)
            assertEquals(0, server.bodies.size)
            assertTrue(run.stderr, run.stderr.contains("/subtask <任务>"))
        }
    }
}
