package dev.min.code.ui.terminal

import me.rerere.workspace.ProotShellEntry
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 终端页签喂给 [me.rerere.workspace.ProotShellRunner] 的那份上下文。
 *
 * 命令行本身在 workspace 模块里断言（ProotCommandTest），这里只管终端自己的两件事：
 * `-w` 指向的目录必须真的存在，以及 Node 的 PATH 必须显式注入（交互 shell 不是登录 shell，
 * 读不到 /etc/profile.d/node.sh，不给的话终端里 `claude` / `node` 全是 command not found）。
 */
class TerminalShellContextTest {
    private lateinit var workspace: File

    private fun dirs(): Triple<File, File, File> {
        workspace = Files.createTempDirectory("terminal-ctx").toFile().apply { deleteOnExit() }
        return Triple(
            File(workspace, "files").apply { mkdirs() },
            File(workspace, "linux").apply { mkdirs() },
            File(workspace, "tmp").apply { mkdirs() },
        )
    }

    private fun build(cwd: String?) = dirs().let { (files, linux, tmp) ->
        files to buildTerminalShellContext(
            root = "default",
            filesDir = files,
            linuxDir = linux,
            tempDir = tmp,
            cwd = cwd,
        )
    }

    @Test
    fun `terminal runs an interactive shell that dies with its tab`() {
        val (_, context) = build(null)
        assertEquals(ProotShellEntry.InteractiveShell, context.entry)
        // 关掉页签就该把里面跑的东西一起带走；长驻本地服务正相反（killOnExit=false）
        assertTrue(context.killOnExit)
        assertEquals("", context.command)
    }

    @Test
    fun `node is on PATH inside the terminal`() {
        val (_, context) = build(null)
        val path = context.env["PATH"]
        assertTrue("终端必须显式拿到 Node 的 PATH: $path", path?.contains("/opt/node/bin") == true)
    }

    @Test
    fun `the terminal finally gets the credentials the session page has always had`() {
        // 在这之前这里一个凭据都没有：同一个 App 里，会话页能用的 token，
        // 换到终端页敲 claude 就是「未配置」。Node 的 PATH 当初补上了、凭据没有，
        // 于是那个 claude 看着装好了、跑起来连不上
        val (files, _) = build(null)
        val context = buildTerminalShellContext(
            root = "default",
            filesDir = files,
            linuxDir = File(workspace, "linux"),
            tempDir = File(workspace, "tmp"),
            credentials = mapOf(
                "ANTHROPIC_BASE_URL" to "https://relay.example",
                "ANTHROPIC_AUTH_TOKEN" to "sk-live",
            ),
        )
        assertEquals("sk-live", context.env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://relay.example", context.env["ANTHROPIC_BASE_URL"])
        // 凭据不能把 PATH 挤掉：两者共存才谈得上「终端里的 claude 能跑」
        assertTrue(context.env["PATH"]?.contains("/opt/node/bin") == true)
    }

    @Test
    fun `no credentials is a normal state, not a broken one`() {
        // 开关关着、还没配过、Keystore 解不开 —— 三种情况都落在这里。
        // 终端照常开得起来，只是里面没有 key
        val (_, context) = build(null)
        assertTrue(context.env.keys.none { it.startsWith("ANTHROPIC_") })
    }

    @Test
    fun `existing session cwd is kept as a workspace relative path`() {
        val (files, _) = build(null)
        File(files, "api").mkdirs()
        val context = buildTerminalShellContext(
            root = "default",
            filesDir = files,
            linuxDir = File(workspace, "linux"),
            tempDir = File(workspace, "tmp"),
            cwd = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/api",
        )
        assertEquals("api", context.cwd)
    }

    @Test
    fun `unusable cwd falls back to the workspace root`() {
        val (files, _) = build(null)
        fun cwdFor(input: String?) = buildTerminalShellContext(
            root = "default",
            filesDir = files,
            linuxDir = File(workspace, "linux"),
            tempDir = File(workspace, "tmp"),
            cwd = input,
        ).cwd

        // 不存在的目录：proot 的 -w 指到它身上时 shell 会莫名其妙地起在别处
        assertEquals("", cwdFor("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/gone"))
        // /workspace 之外的目录不是终端该去的地方
        assertEquals("", cwdFor("/etc"))
        // 前缀像但不是（/workspace-old）也要挡掉
        assertEquals("", cwdFor("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}-old/api"))
        assertEquals("", cwdFor(WorkspaceManager.ROOTFS_WORKSPACE_DIR))
        assertEquals("", cwdFor(null))
    }
}
