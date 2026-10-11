package dev.min.agent.cli

import dev.min.agent.ext.config.ConfigSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
 * `--settings <文件或 JSON>`（CC :128）：参数解析（内联 JSON / 文件、相对 cwd、2 MiB 上限、坏 JSON 报用法错误），
 * 以及它进了组合根：deny 规则与 hooks 随设置合并进配置。
 */
class SettingsArgTest {
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

    @Test
    fun `内联 JSON 能解析`() {
        val s = parseArgs(arrayOf("--model", "t", "--settings", """{"model":"x"}"""), work()).settings
        assertEquals("""{"model":"x"}""", s!!.text)
        assertEquals("--settings", s.label)
    }

    @Test
    fun `文件路径相对 --cwd 解析`() {
        val work = work()
        Files.writeString(work.resolve("s.json"), """{"model":"from-file"}""")
        val s = parseArgs(arrayOf("--model", "t", "--cwd", work.toString(), "--settings", "s.json"), tmp.root.toPath()).settings
        assertEquals("""{"model":"from-file"}""", s!!.text)
        assertTrue(s.label.endsWith("s.json"))
    }

    @Test
    fun `没给 --settings 为 null`() {
        assertNull(parseArgs(arrayOf("--model", "t"), work()).settings)
    }

    @Test
    fun `坏的内联 JSON 报用法错误`() {
        try {
            parseArgs(arrayOf("--model", "t", "--settings", "{not json"), work())
            fail("坏 JSON 要报用法错误")
        } catch (_: UsageException) { }
    }

    @Test
    fun `文件里不是 JSON 对象报用法错误`() {
        val work = work()
        Files.writeString(work.resolve("arr.json"), "[1,2]")
        try {
            parseArgs(arrayOf("--model", "t", "--cwd", work.toString(), "--settings", "arr.json"), tmp.root.toPath())
            fail("非对象要报用法错误")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("JSON 对象"))
        }
    }

    @Test
    fun `找不到的文件报用法错误`() {
        try {
            parseArgs(arrayOf("--model", "t", "--cwd", work().toString(), "--settings", "nope.json"), tmp.root.toPath())
            fail("找不到文件要报用法错误")
        } catch (_: UsageException) { }
    }

    @Test
    fun `超过 2 MiB 的文件报用法错误（CC 原文）`() {
        val work = work()
        Files.write(work.resolve("big.json"), ByteArray(2 * 1024 * 1024 + 1) { 32 })
        try {
            parseArgs(arrayOf("--model", "t", "--cwd", work.toString(), "--settings", "big.json"), tmp.root.toPath())
            fail("超过 2 MiB 要报用法错误")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("2 MiB"))
        }
    }

    @Test
    fun `--help 里列出 --settings`() {
        try {
            parseArgs(arrayOf("--model", "t", "--help"), work())
            fail("--help 走 UsageException")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("--settings"))
        }
    }

    @Test
    fun `--settings 的 deny 规则与 hooks 进了组合根的配置（cli 层）`() = runBlocking { withTimeout(60_000) {
        val work = work()
        val json = """{"permissions":{"deny":["Bash(rm *)"]},"hooks":{"Stop":[{"hooks":[{"type":"command","command":"true"}]}]}}"""
        val app = DesktopApp(parseArgs(argv(work, "--settings", json), work), newScope(), home(), claudeHome = home())
        val config = app.config()
        val deny = config.settings.merged.ruleSpecs().single { it.text == "Bash(rm *)" }
        assertEquals(ConfigSource.Cli.label, deny.source)
        assertTrue(config.settings.merged.hooks.any { it.source == ConfigSource.Cli })
    } }
}
