package dev.min.agent.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 工作区信任（CC permissions 页「Project allow rules and workspace trust」）的桌面口径：
 * 交互模式首次进未信任的目录弹确认，选「信任」后记账本，下次不再问；`-p` 不弹、不写，只读已记的信任。
 */
class WorkspaceTrustDialogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun home(): Path = Files.createDirectories(tmp.root.toPath().resolve("home"))

    private fun work(name: String = "project"): Path = Files.createDirectories(tmp.root.toPath().resolve(name))

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    private fun app(work: Path, home: Path, vararg extra: String) =
        DesktopApp(parseArgs(argv(work, *extra), work), newScope(), home, claudeHome = home.resolve("claude-home"))

    private fun reader(text: String) = BufferedReader(StringReader(text))

    @Test fun `interactive first visit asks, accepting records the folder, and the next launch does not ask`() {
        val work = work()
        val home = home()
        val out = ByteArrayOutputStream()
        val first = app(work, home)
        assertFalse(first.isWorkspaceTrusted())
        assertTrue(confirmWorkspaceTrust(first, reader("1\n"), PrintStream(out, true, "UTF-8")))
        assertTrue(out.toString("UTF-8"), out.toString("UTF-8").contains("信任"))
        assertTrue(first.isWorkspaceTrusted())

        // 新进程（新的组合根）：同一个用户目录里的账本还在，不再问，也不读输入
        val silent = ByteArrayOutputStream()
        val second = app(work, home)
        assertTrue(second.isWorkspaceTrusted())
        assertTrue(confirmWorkspaceTrust(second, reader(""), PrintStream(silent, true, "UTF-8")))
        assertEquals("", silent.toString("UTF-8"))
    }

    @Test fun `declining does not record the folder and the next launch asks again`() {
        val work = work()
        val home = home()
        val out = ByteArrayOutputStream()
        val app = app(work, home)
        assertFalse(confirmWorkspaceTrust(app, reader("2\n"), PrintStream(out, true, "UTF-8")))
        assertFalse(app(work, home).isWorkspaceTrusted())
        // 直接回车 = 没有同意（安全的默认）
        assertFalse(confirmWorkspaceTrust(app(work, home), reader("\n"), PrintStream(ByteArrayOutputStream(), true, "UTF-8")))
        assertFalse(Files.exists(home.resolve("workspace-trust.txt")))
    }

    @Test fun `print mode never asks and never records, but honours a trust recorded earlier`() {
        val work = work()
        val home = home()
        val out = ByteArrayOutputStream()
        // -p 没有弹框：未信任就是未信任，输入流里的回答也不读
        val headless = app(work, home, "-p", "hello")
        assertFalse(headless.isWorkspaceTrusted())
        assertFalse(Files.exists(home.resolve("workspace-trust.txt")))

        // 交互里信任过的目录，-p 照样生效（只读账本）
        assertTrue(confirmWorkspaceTrust(app(work, home), reader("y\n"), PrintStream(out, true, "UTF-8")))
        assertTrue(app(work, home, "-p", "hello").isWorkspaceTrusted())
    }

    @Test fun `trusting a parent folder covers the projects below it`() {
        val parent = work("ws")
        val child = Files.createDirectories(parent.resolve("sub"))
        val home = home()
        assertTrue(confirmWorkspaceTrust(app(parent, home), reader("1\n"), PrintStream(ByteArrayOutputStream(), true, "UTF-8")))
        assertTrue(app(child, home).isWorkspaceTrusted())
        // 反过来不成立：信任子目录不信任父目录
        val other = home().resolve("elsewhere-root").also { Files.createDirectories(it) }
        val kid = Files.createDirectories(other.resolve("kid"))
        assertTrue(confirmWorkspaceTrust(app(kid, home), reader("1\n"), PrintStream(ByteArrayOutputStream(), true, "UTF-8")))
        assertFalse(app(other, home).isWorkspaceTrusted())
    }

    @Test fun `trust flag still works without a record`() {
        val work = work()
        assertTrue(app(work, home(), "--trust").isWorkspaceTrusted())
    }
}
