package dev.min.agent.cli

import dev.min.agent.protocol.PermissionMode
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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--restricted`（CC cli-reference :123）的 CLI 面：参数与用法错误、--help、工具表（去掉跑命令的内置工具与 WebFetch，
 * 除非 `--tools` 逐个点名；`default` 不算）、设置层（只读托管与 --settings）、与 --bare / --safe-mode / --tools 的组合。
 * 对照组：同一份工作区不开 --restricted 时的样子。
 */
class RestrictedArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))
    private fun home(): Path = Files.createDirectories(tmp.root.toPath().resolve("home"))

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun app(work: Path, vararg extra: String, home: Path = home()) =
        DesktopApp(parseArgs(argv(work, *extra), work), newScope(), home, claudeHome = home)

    // ---- 参数 ----

    @Test
    fun `--restricted 能解析，没给时为假`() {
        assertTrue(parseArgs(arrayOf("--model", "t", "--restricted"), work()).restricted)
        assertFalse(parseArgs(arrayOf("--model", "t"), work()).restricted)
    }

    @Test
    fun `--restricted 加 --mode bypassPermissions 报用法错误`() {
        try {
            parseArgs(arrayOf("--model", "t", "--restricted", "--mode", "bypassPermissions"), work())
            fail("restricted 拒绝 bypassPermissions，启动时就要报错")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("bypassPermissions"))
        }
    }

    @Test
    fun `--mode bypassPermissions 没有 --restricted 时照常可解析`() {
        assertEquals(PermissionMode.BypassPermissions, parseArgs(arrayOf("--model", "t", "--mode", "bypassPermissions"), work()).mode)
    }

    @Test
    fun `--help 里列出 --restricted`() {
        try {
            parseArgs(arrayOf("--model", "t", "--help"), work())
            fail("--help 走 UsageException")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("--restricted"))
        }
    }

    // ---- 工具表 ----

    @Test
    fun `restricted：Bash、Monitor、WebFetch 不在表里，文件工具照常`() = runBlocking { withTimeout(60_000) {
        val work = work()
        val a = app(work, "--restricted")
        a.hub()
        val names = a.toolNames()
        for (gone in listOf("Bash", "Monitor", "WebFetch")) assertFalse("restricted 不该有 $gone：$names", gone in names)
        for (kept in listOf("Read", "Write", "Edit", "Glob", "Grep", "NotebookEdit")) assertTrue("restricted 该有 $kept：$names", kept in names)
    } }

    @Test
    fun `对照组：不开 restricted，三者都在`() = runBlocking { withTimeout(60_000) {
        val a = app(work())
        a.hub()
        assertTrue(a.toolNames().containsAll(listOf("Bash", "Monitor", "WebFetch")))
    } }

    @Test
    fun `restricted 加 --tools Bash：点名的 Bash 留下，别的仍去掉`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--tools", "Read,Bash")
        a.hub()
        assertEquals(listOf("Read", "Bash"), a.toolNames())
    } }

    @Test
    fun `restricted 加 --tools default：default 预设不放回 Bash、Monitor、WebFetch`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--tools", "default")
        a.hub()
        val names = a.toolNames()
        for (gone in listOf("Bash", "Monitor", "WebFetch")) assertFalse("default 预设不算点名：$names", gone in names)
    } }

    @Test
    fun `restricted 逐个点名 Monitor 与 WebFetch 才放回`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--tools", "WebFetch,Monitor")
        a.hub()
        assertEquals(listOf("Monitor", "WebFetch"), a.toolNames().sorted())
    } }

    @Test
    fun `restricted 加 --bare：只剩 Read、Write、Edit、NotebookEdit`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--bare")
        a.hub()
        assertEquals(listOf("Read", "Write", "Edit", "NotebookEdit"), a.toolNames())
    } }

    @Test
    fun `restricted 与 --safe-mode 可以组合`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--safe-mode")
        a.hub()
        assertFalse("Bash" in a.toolNames())
        assertTrue("Read" in a.toolNames())
    } }

    // ---- 设置层 ----

    private fun seedUserAndProject(work: Path, home: Path) {
        Files.createDirectories(home.resolve(".claude")).resolve("settings.json").toFile()
            .writeText("""{"permissions":{"allow":["Bash(user-rule)"]}}""")
        Files.createDirectories(work.resolve(".claude")).resolve("settings.json").toFile()
            .writeText("""{"permissions":{"allow":["Bash(project-rule)"]}}""")
        work.resolve(".claude/settings.local.json").toFile().writeText("""{"permissions":{"allow":["Bash(local-rule)"]}}""")
    }

    @Test
    fun `restricted：用户、项目、local 设置不加载，--settings 加载`() = runBlocking { withTimeout(60_000) {
        val work = work()
        val home = home()
        seedUserAndProject(work, home)
        val a = app(work, "--restricted", "--settings", """{"permissions":{"deny":["Bash(cli-rule)"]}}""", home = home)
        val texts = a.config().settings.merged.ruleSpecs().map { it.text }.toSet()
        assertEquals(setOf("Bash(cli-rule)"), texts)
    } }

    @Test
    fun `对照组：不开 restricted，用户与项目设置照常加载`() = runBlocking { withTimeout(60_000) {
        val work = work()
        val home = home()
        seedUserAndProject(work, home)
        val texts = app(work, home = home).config().settings.merged.ruleSpecs().map { it.text }.toSet()
        assertTrue("user：$texts", "Bash(user-rule)" in texts)
        assertTrue("project：$texts", "Bash(project-rule)" in texts)
        assertTrue("local：$texts", "Bash(local-rule)" in texts)
    } }

    @Test
    fun `restricted 下设置里写了 defaultMode bypassPermissions，启动报错`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--restricted", "--settings", """{"permissions":{"defaultMode":"bypassPermissions"}}""")
        try {
            a.hub()
            fail("restricted 下设置里的 bypassPermissions 要在启动时报错")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("bypassPermissions"))
        }
    } }

    @Test
    fun `对照组：不开 restricted，设置里的 bypassPermissions 只是一个值，启动不报错`() = runBlocking { withTimeout(60_000) {
        val a = app(work(), "--settings", """{"permissions":{"defaultMode":"bypassPermissions"}}""")
        a.hub()
        assertTrue(a.toolNames().isNotEmpty())
    } }
}
