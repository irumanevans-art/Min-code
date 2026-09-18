package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * proot 命令行的拼装。
 *
 * 这套断言存在的理由：命令行以前在三处各拼一份，改一处忘两处是常态，而结果只在真机上
 * 才看得出来（`claude: command not found`、关掉对话服务跟着死）。收口之后，三条路径
 * 之间**哪些必须一致、哪些是故意不同**由这里钉住。
 */
class ProotCommandTest {
    private fun tempDir(name: String): File =
        Files.createTempDirectory(name).toFile().apply { deleteOnExit() }

    private fun runner(): ProotShellRunner = ProotShellRunner(nativeLibraryDir = tempDir("native"))

    private fun context(
        cwd: String = "",
        command: String = "echo hi",
        env: Map<String, String> = emptyMap(),
        killOnExit: Boolean = true,
        entry: ProotShellEntry = ProotShellEntry.LoginCommand,
    ): WorkspaceShellContext {
        val workspace = tempDir("workspace")
        return WorkspaceShellContext(
            root = "default",
            command = command,
            cwd = cwd,
            filesDir = File(workspace, "files").apply { mkdirs() },
            linuxDir = File(workspace, "linux").apply { mkdirs() },
            tempDir = File(workspace, "tmp").apply { mkdirs() },
            workingDir = File(workspace, "files"),
            timeoutMillis = 0L,
            env = env,
            killOnExit = killOnExit,
            entry = entry,
        )
    }

    /** 三条真实路径的还原：CLI 会话 / 长驻本地服务 / 终端页签 */
    private fun sessionCommand() = runner().buildCommand(
        context(env = mapOf("PATH" to "/opt/node/bin:/usr/bin", "ANTHROPIC_AUTH_TOKEN" to "t"))
    )

    private fun serviceCommand() = runner().buildCommand(
        context(
            command = "npm run dev",
            env = mapOf("PATH" to "/opt/node/bin:/usr/bin"),
            killOnExit = false,
        )
    )

    private fun terminalCommand() = runner().buildCommand(
        context(
            command = "",
            env = mapOf("PATH" to "/opt/node/bin:/usr/bin"),
            entry = ProotShellEntry.InteractiveShell,
        )
    )

    // --- 应当一致的部分 ---

    @Test
    fun `every path gets the same proot flags and mounts`() {
        listOf(sessionCommand(), serviceCommand(), terminalCommand()).forEach { command ->
            assertTrue("--root-id 缺失", "--root-id" in command)
            assertTrue("--link2symlink 缺失", "--link2symlink" in command)
            assertTrue("-r 缺失", "-r" in command)
            assertTrue("/usr/bin/env -i 缺失", command.zipWithNext().contains("/usr/bin/env" to "-i"))
            // /workspace 的挂载点是三条路径共同的地面，漂了的话同一个文件在不同页面是两个路径
            assertTrue(
                "/workspace 绑定缺失",
                command.any { it.endsWith(":${WorkspaceManager.ROOTFS_WORKSPACE_DIR}") },
            )
            // ProotCompat 的补丁（/dev/shm 替身 + 假 /proc）三条路径都要有，
            // 否则终端里的 Python multiprocessing 能跑而会话里不能，反之亦然
            assertTrue("/dev/shm 替身缺失", command.any { it.endsWith(":/dev/shm") })
        }
    }

    @Test
    fun `every path declares the same guest identity`() {
        listOf(sessionCommand(), serviceCommand(), terminalCommand()).forEach { command ->
            assertTrue("HOME=/root" in command)
            assertTrue("USER=root" in command)
            assertTrue("SHELL=/bin/bash" in command)
            assertTrue("LANG=C.UTF-8" in command)
            assertTrue("LC_ALL=C.UTF-8" in command)
            assertTrue("TERM=xterm-256color" in command)
        }
    }

    @Test
    fun `caller env overrides the base env`() {
        val command = sessionCommand()
        // `env -i` 里同名变量后者覆盖前者：调用方注入的 PATH 必须排在基础 PATH 之后
        val basePath = command.indexOfFirst { it.startsWith("PATH=") }
        val callerPath = command.indexOfLast { it.startsWith("PATH=") }
        assertTrue(basePath >= 0)
        assertTrue("调用方 PATH 必须排在基础 PATH 之后", callerPath > basePath)
        assertEquals("PATH=/opt/node/bin:/usr/bin", command[callerPath])
        assertTrue("ANTHROPIC_AUTH_TOKEN=t" in command)
    }

    @Test
    fun `cwd is resolved against the workspace mount point`() {
        val command = runner().buildCommand(context(cwd = "api/server"))
        val workDir = command[command.indexOf("-w") + 1]
        assertEquals("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/api/server", workDir)

        val root = runner().buildCommand(context(cwd = ""))
        assertEquals(
            WorkspaceManager.ROOTFS_WORKSPACE_DIR,
            root[root.indexOf("-w") + 1],
        )
    }

    // --- 故意不同的部分 ---

    @Test
    fun `only the hosted service keeps running after its handle dies`() {
        // 长驻本地服务是独立 proot：`--kill-on-exit` 一带上，registry 一收句柄整棵树跟着死，
        // 「关掉对话，后台服务还活着」就没了（LRN-20260915-PROCESS-TABLE-PREVIEW）
        assertTrue("会话必须带 --kill-on-exit", "--kill-on-exit" in sessionCommand())
        assertTrue("终端页签必须带 --kill-on-exit", "--kill-on-exit" in terminalCommand())
        assertFalse("长驻服务绝不能带 --kill-on-exit", "--kill-on-exit" in serviceCommand())
    }

    @Test
    fun `the non-interactive convention stops at the terminal tab`() {
        listOf(sessionCommand(), serviceCommand()).forEach { command ->
            assertTrue("CI=true" in command)
            assertTrue("NO_COLOR=1" in command)
            assertTrue("PAGER=cat" in command)
        }
        // 终端页签有真人在看：关颜色、把分页器换成 cat 是错的
        val terminal = terminalCommand()
        assertFalse("CI=true" in terminal)
        assertFalse("NO_COLOR=1" in terminal)
        assertFalse("PAGER=cat" in terminal)
    }

    @Test
    fun `entry shape follows the entry kind`() {
        // 非交互：命令走位置参数，不经任何转义
        val session = runner().buildCommand(context(command = "echo 'a b'"))
        // 尾巴固定是 bash -l -c <script> rikkahub <cwd> <command> <PATH> 共 8 项
        assertEquals(listOf("/bin/bash", "-l", "-c"), session.subList(session.size - 8, session.size - 5))
        assertTrue("命令必须原样作为位置参数传入", "echo 'a b'" in session)

        // 交互：pty 接管，没有 -c
        val terminal = terminalCommand()
        assertEquals("/bin/bash", terminal.last())
        assertFalse("交互 shell 不该有 -c", "-c" in terminal)
    }

    @Test
    fun `host environment carries the proot loader`() {
        val runner = runner()
        val context = context()
        val env = runner.hostEnvironment(context)
        assertEquals(runner.prootLoader.absolutePath, env["PROOT_LOADER"])
        assertEquals(context.tempDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals(context.tempDir.absolutePath, env["TMPDIR"])
        // argv[0] 也从 runner 来，调用方不再自己拼 so 名字
        assertEquals(runner.prootExecutable.absolutePath, runner.buildCommand(context).first())
    }
}
