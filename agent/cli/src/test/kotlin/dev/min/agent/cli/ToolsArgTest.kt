package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--tools`（CC :136）：限定内置工具表。逗号或空格分隔；`default` 预设不限定；`""` 不给任何内置工具；
 * 未知工具名在启动时报用法错误。只影响内置工具，MCP 工具照常（原文）。
 */
class ToolsArgTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    private fun parse(vararg extra: String) = parseArgs(arrayOf("--model", "test") + extra, work())

    @Test
    fun `逗号与空格分隔都能解析成点名集合`() {
        assertEquals(setOf("Read", "Bash"), parse("--tools", "Read,Bash").tools)
        assertEquals(setOf("Read", "Bash"), parse("--tools", "Read Bash").tools)
        assertEquals(setOf("Read", "Bash"), parse("--tools", " Read , Bash ").tools)
    }

    @Test
    fun `空串是「不给任何内置工具」，不是没给`() {
        assertEquals(emptySet<String>(), parse("--tools", "").tools)
    }

    @Test
    fun `没给或 default 都是预设，不限定`() {
        assertNull(parse().tools)
        assertNull(parse("--tools", "default").tools)
    }

    @Test
    fun `default 不能和具体名字混用`() {
        try {
            parse("--tools", "default,Read")
            fail("default 与名字混用要报用法错误")
        } catch (_: UsageException) { }
    }

    @Test
    fun `未知工具名报用法错误，并指出是哪个`() {
        try {
            parse("--tools", "Read,NoSuchTool")
            fail("未知工具名要报用法错误")
        } catch (e: UsageException) {
            assertTrue("报错该点出未知名：${e.message}", e.message!!.contains("NoSuchTool"))
        }
    }

    @Test
    fun `--tools Read,Bash 之后工具表只剩这两个（表序不变）`() = kotlinx.coroutines.runBlocking {
        val work = work()
        val app = DesktopApp(parseArgs(argv(work, "--tools", "Bash,Read"), work), newScope(), tmp.root.toPath().resolve("home").also { Files.createDirectories(it) }, claudeHome = tmp.root.toPath().resolve("home"))
        app.hub()
        assertEquals(listOf("Read", "Bash"), app.toolNames())
    }

    @Test
    fun `--tools 空串之后没有任何内置工具`() = kotlinx.coroutines.runBlocking {
        val work = work()
        val app = DesktopApp(parseArgs(argv(work, "--tools", ""), work), newScope(), tmp.root.toPath().resolve("home").also { Files.createDirectories(it) }, claudeHome = tmp.root.toPath().resolve("home"))
        app.hub()
        assertEquals(emptyList<String>(), app.toolNames())
    }

    @Test
    fun `--tools default 与不给一样，工具表是全表`() = kotlinx.coroutines.runBlocking {
        val work = work()
        val app = DesktopApp(parseArgs(argv(work, "--tools", "default"), work), newScope(), tmp.root.toPath().resolve("home").also { Files.createDirectories(it) }, claudeHome = tmp.root.toPath().resolve("home"))
        app.hub()
        assertTrue(app.toolNames().containsAll(listOf("Read", "Bash", "WebFetch")))
    }

    @Test
    fun `--help 里列出 --tools`() {
        try {
            parseArgs(arrayOf("--model", "test", "--help"), work())
            fail("--help 走 UsageException")
        } catch (e: UsageException) {
            assertTrue("--help 该提到 --tools：${e.message}", e.message!!.contains("--tools"))
        }
    }

    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()
    private fun newScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).also { scopes += it }
}
