package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/add-dir`（commands 文档）经真 CLI 的 `interact` 循环：输入一行斜杠命令，真 DesktopApp、真会话、真 shell 跑 DirectoryAdded hook。
 * 不用模型（`/add-dir` 是本地命令，不发请求）。网络路径、不存在的路径在碰文件系统之前就被挡下。
 */
class AddDirCommandTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private class Run(val stderr: String, val app: DesktopApp, val runtime: SessionRuntime)

    private fun userSettings(home: Path, json: String) {
        val file = home.resolve("claude-home/.claude/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun drive(work: Path, lines: List<String>, setup: (Path) -> Unit = {}): Run = runBlocking {
        withTimeout(120_000) {
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            setup(home)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(
                arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9",
                    "--mode", "bypassPermissions", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString()),
                work,
            )
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            val runtime = app.openSession()
            val writer = PipedWriter()
            val reader = BufferedReader(PipedReader(writer))
            val feeder = Thread {
                lines.forEach { writer.write(it + "\n"); writer.flush(); Thread.sleep(400) }
                Thread.sleep(300)
                writer.write("/exit\n"); writer.flush()
            }.also { it.isDaemon = true; it.start() }
            val originalErr = System.err
            val originalOut = System.out
            val err = ByteArrayOutputStream()
            System.setErr(PrintStream(err, true, "UTF-8"))
            System.setOut(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
            try {
                assertEquals(0, interact(app, runtime, args, scope, reader))
                feeder.join(5_000)
                Run(err.toString("UTF-8"), app, runtime)
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))
    private fun other(): Path = Files.createDirectories(tmp.root.toPath().resolve("other repo"))

    private fun waitFor(file: Path) {
        val deadline = System.currentTimeMillis() + 30_000
        while ((!Files.exists(file) || Files.size(file) == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    @Test fun `the slash command adds the directory and fires DirectoryAdded with a real shell`() {
        val work = work()
        val other = other()
        val marker = tmp.root.toPath().resolve("marker.json")
        val markerShell = marker.toString().replace('\\', '/')
        val run = drive(work, listOf("/add-dir $other")) { home ->
            userSettings(home, """{"autoMemoryEnabled":false,"hooks":{"DirectoryAdded":[{"matcher":"slash_command","hooks":[{"type":"command","command":"cat > '$markerShell'"}]}]}}""")
        }
        waitFor(marker)
        assertTrue(run.stderr, run.stderr.contains("已加工作目录"))
        assertEquals(listOf(run.app.guestOfUserPath(run.runtime, other.toString())), run.runtime.state.value.additionalDirectories)
        val body = Files.readString(marker)
        assertTrue(body, body.contains("\"hook_event_name\":\"DirectoryAdded\""))
        assertTrue(body, body.contains("\"source\":\"slash_command\""))
        // hook 的 shell 看到的是 guest 写法（Windows 上 Git Bash 的 /c/...）：directory 是那个绝对路径
        val guestOther = run.app.guestOfUserPath(run.runtime, other.toString()).value
        assertTrue("directory 字段：$body", body.contains("\"directory\":\"$guestOther\""))
    }

    @Test fun `a relative path is resolved against the session directory`() {
        val work = work()
        val sibling = Files.createDirectories(work.resolve("packages/api"))
        val run = drive(work, listOf("/add-dir packages/api"))
        // 在工作目录里面：什么都没加，不重复
        assertTrue(run.stderr, run.stderr.contains("已经是工作目录"))
        assertTrue(run.runtime.state.value.additionalDirectories.isEmpty())
        assertTrue(Files.exists(sibling))
    }

    @Test fun `a missing directory, a file and a network path are refused with a reason`() {
        val work = work()
        val file = Files.writeString(tmp.root.toPath().resolve("a.txt"), "x")
        val run = drive(work, listOf("/add-dir ${tmp.root.toPath().resolve("nope")}", "/add-dir $file", "/add-dir \\\\server\\share", "/add-dir //server/share", "/add-dir"))
        assertTrue(run.stderr, run.stderr.contains("目录不存在"))
        assertTrue(run.stderr, run.stderr.contains("不是目录"))
        assertEquals(run.stderr, 2, Regex("不能把网络路径加成工作目录").findAll(run.stderr).count())
        assertTrue(run.stderr, run.stderr.contains("用法：/add-dir"))
        assertTrue(run.runtime.state.value.additionalDirectories.isEmpty())
    }

    @Test fun `adding the same directory twice fires the hook once`() {
        val work = work()
        val other = other()
        val counter = tmp.root.toPath().resolve("count.txt")
        val counterShell = counter.toString().replace('\\', '/')
        val run = drive(work, listOf("/add-dir $other", "/add-dir $other")) { home ->
            userSettings(home, """{"autoMemoryEnabled":false,"hooks":{"DirectoryAdded":[{"hooks":[{"type":"command","command":"echo x >> '$counterShell'"}]}]}}""")
        }
        waitFor(counter)
        Thread.sleep(500)
        assertEquals(run.stderr, 1, Files.readAllLines(counter).size)
        assertEquals(1, Regex("已经是工作目录").findAll(run.stderr).count())
        assertFalse(run.stderr.contains("Exception"))
    }
}
