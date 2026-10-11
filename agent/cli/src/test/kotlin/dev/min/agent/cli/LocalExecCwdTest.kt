package dev.min.agent.cli

import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ShellCommand.trackCwd] 在真 shell 上的行为（Windows 上是 Git Bash）：结束目录靠探针写文件拿到，
 * 不进输出；`exit`、超时、转后台、被截断的长输出、未收口的 heredoc 都要对。路径含空格与中文，覆盖 Windows 的引用与映射。
 */
class LocalExecCwdTest {
    private lateinit var temp: Path
    private lateinit var project: Path
    private lateinit var logs: Path
    private lateinit var paths: DesktopPaths
    private lateinit var exec: LocalExec
    private var cwd = GuestPath("/unset")
    private val handles = ArrayList<ExecHandle>()

    @Before fun setUp() {
        temp = Files.createTempDirectory("cwd probe 中文 ")
        project = Files.createDirectories(temp.resolve("proj"))
        Files.createDirectories(project.resolve("sub dir 中文/deep"))
        logs = temp.resolve("logs")
        paths = DesktopPaths(project, temp.resolve("scratch"))
        cwd = paths.guestOfHost(project)
        exec = LocalExec(paths::hostOf, logs, paths.guestOfHost(logs),
            listOf(if (System.getProperty("os.name").startsWith("Windows")) "C:/Program Files/Git/bin/bash.exe" else "bash", "-lc"),
            guestOfShell = paths::guestOfShell)
    }

    @After fun tearDown() {
        runBlocking { handles.forEach { exec.kill(it) } }
        temp.toFile().deleteRecursively()
    }

    private fun guest(vararg parts: String) = paths.guestOfHost(parts.fold(project) { p, n -> p.resolve(n) })
    private fun run(script: String, options: ExecOptions = ExecOptions(60_000), track: Boolean = true): ExecResult =
        runBlocking { exec.run(ShellCommand(script, cwd, trackCwd = track), options) }
    private fun leftovers(): List<String> =
        if (Files.exists(logs)) Files.list(logs).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".cwd") }.toList() } else emptyList()

    @Test fun `cd inside the command is reported and stays out of the output`() {
        val r = run("cd 'sub dir 中文' && pwd") as ExecResult.Exited
        assertEquals(guest("sub dir 中文"), r.endCwd)
        // 输出只有命令自己的 pwd：没有探针、没有 eval、没有标记
        assertEquals(1, r.output.trim().lines().size)
        assertTrue(r.output, r.output.trim().endsWith("/sub dir 中文"))
        assertFalse(r.output.contains("__min") || r.output.contains("eval") || r.output.contains(".cwd"))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `no cd reports the starting directory and nested cd works`() {
        assertEquals(cwd, (run("true") as ExecResult.Exited).endCwd)
        assertEquals(guest("sub dir 中文", "deep"), (run("cd 'sub dir 中文'; cd deep; cd ..; cd deep") as ExecResult.Exited).endCwd)
    }

    @Test fun `without trackCwd nothing is wrapped or reported`() {
        val r = run("cd 'sub dir 中文' && pwd", track = false) as ExecResult.Exited
        assertNull(r.endCwd)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `exit inside the script means no directory and the exit code is kept`() {
        val r = run("cd 'sub dir 中文'; exit 3") as ExecResult.Exited
        assertEquals(3, r.exitCode); assertNull(r.endCwd)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `exit code of the last command is kept through the probe`() {
        val r = run("cd 'sub dir 中文'; false") as ExecResult.Exited
        assertEquals(1, r.exitCode)
        assertEquals("退出码照原样，目录照样报告", guest("sub dir 中文"), r.endCwd)
    }

    @Test fun `set -e failure ends the script before the probe, no directory`() {
        val r = run("set -e; cd 'sub dir 中文'; false; echo unreachable") as ExecResult.Exited
        assertEquals(1, r.exitCode); assertNull(r.endCwd)
        assertFalse(r.output.contains("unreachable"))
    }

    @Test fun `own EXIT trap still runs and does not break the report`() {
        val r = run("trap 'echo bye' EXIT; cd 'sub dir 中文'") as ExecResult.Exited
        assertEquals(0, r.exitCode)
        assertEquals("bye", r.output.trim())
        assertEquals(guest("sub dir 中文"), r.endCwd)
    }

    @Test fun `a user defined pwd function cannot spoof the report`() {
        val r = run("pwd() { echo /etc; }; cd 'sub dir 中文'") as ExecResult.Exited
        assertEquals(guest("sub dir 中文"), r.endCwd)
    }

    @Test fun `unterminated heredoc and odd quoting do not swallow the probe`() {
        val r = run("cat <<EOF\nline with 'quote' and \"double\"\ncd sub") as ExecResult.Exited
        assertTrue(r.output, r.output.contains("line with 'quote'"))
        assertFalse(r.output.contains("__min"))
        assertEquals(cwd, r.endCwd)
    }

    @Test fun `syntax error keeps a failing exit code`() {
        val r = run("cd 'sub dir 中文' && (") as ExecResult.Exited
        assertNotEquals(0, r.exitCode)
    }

    @Test fun `truncated output keeps head and tail, still reports and leaks nothing`() {
        val r = run("cd 'sub dir 中文'; i=0; while [ \$i -lt 4000 ]; do echo line-\$i-xxxxxxxxxxxxxxxxxxxx; i=\$((i+1)); done",
            ExecOptions(60_000, maxOutputChars = 2_000)) as ExecResult.Exited
        assertTrue(r.truncated)
        assertTrue(r.output.startsWith("line-0-"))
        assertTrue(r.output.trimEnd().endsWith("line-3999-xxxxxxxxxxxxxxxxxxxx"))
        assertFalse(r.output.contains("__min") || r.output.contains(".cwd"))
        assertEquals(guest("sub dir 中文"), r.endCwd)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `timeout does not report and leaves no file`() {
        val r = run("cd 'sub dir 中文'; sleep 30", ExecOptions(timeoutMs = 1_500))
        assertTrue(r.toString(), r is ExecResult.TimedOut)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `backgrounded command does not report and its late finish leaves no file`() {
        val r = run("cd 'sub dir 中文'; sleep 2", ExecOptions(timeoutMs = 120_000, foregroundBudgetMs = 300))
        assertTrue(r.toString(), r is ExecResult.Backgrounded)
        val handle = (r as ExecResult.Backgrounded).handle
        handles += handle
        val deadline = System.currentTimeMillis() + 30_000
        while (runBlocking { exec.isAlive(handle) } == true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(false, runBlocking { exec.isAlive(handle) })
        assertEquals("事后才结束的后台命令不能把探针文件留下", emptyList<String>(), leftovers())
        val log = Files.readString(logs.resolve("${handle.id}.log"))
        assertFalse("日志里也没有探针", log.contains("__min") || log.contains(".cwd"))
    }

    @Test fun `an exec built without a path mapper never throws on Windows style paths`() {
        // 许多调用方（测试、插件评测）自己建 LocalExec、不传映射；Windows 上 pwd -W 打 C:/...，GuestPath 构造会抛——必须当作没换目录
        val bare = LocalExec(paths::hostOf, logs, paths.guestOfHost(logs),
            listOf(if (System.getProperty("os.name").startsWith("Windows")) "C:/Program Files/Git/bin/bash.exe" else "bash", "-lc"))
        val r = runBlocking { bare.run(ShellCommand("cd 'sub dir 中文'", cwd, trackCwd = true), ExecOptions(60_000)) } as ExecResult.Exited
        assertEquals(0, r.exitCode)
        r.endCwd?.let { assertTrue(it.value, it.value.startsWith("/")) }
    }

    @Test fun `directory outside every drive project root is still reported so the kernel can see it is outside`() {
        // Git Bash 自己的目录（/usr 在 Git 安装目录下）：pwd -W 给出它的真实 Windows 位置，必然不在项目目录里
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val r = run("cd /usr") as ExecResult.Exited
        val end = r.endCwd!!.value
        assertFalse(end, end.startsWith(cwd.value))
    }
}
