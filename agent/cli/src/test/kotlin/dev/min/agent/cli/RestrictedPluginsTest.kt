package dev.min.agent.cli

import dev.min.agent.ext.config.ClaudeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--restricted` 的插件口径（Min 的解释）：只加载托管层、`--settings` 启用的，以及 `--plugin-dir` 点名的；
 * 已安装索引（`installed_plugins.json`）与用户、项目设置里启用的一律不加载。对照组：同一份工作区不开 restricted。
 */
class RestrictedPluginsTest {
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

    /** 带 manifest、命令与 skill 的插件，插件名 check */
    private fun seedPlugin(dir: Path) {
        Files.createDirectories(dir.resolve(".claude-plugin"))
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
            """{"name":"check","commands":{"hi":{"content":"Hi ${'$'}ARGUMENTS"}}}""")
        Files.createDirectories(dir.resolve("skills/run"))
        Files.writeString(dir.resolve("skills/run/SKILL.md"), "---\ndescription: plugin skill\n---\nRun body")
    }

    /** 已安装索引里登记它（user 范围），并在用户设置里启用 */
    private fun seedInstalledAndUserEnabled(home: Path, dir: Path) {
        val plugins = Files.createDirectories(home.resolve(".claude/plugins"))
        Files.writeString(plugins.resolve("installed_plugins.json"),
            """{"plugins":{"check@m":[{"scope":"user","installPath":"${dir.toString().replace('\\', '/')}"}]}}""")
        Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
            .toFile().writeText("""{"enabledPlugins":{"check@m":true}}""")
    }

    private fun skillNames(config: ClaudeConfig) = config.skills.map { it.name }

    @Test fun `对照组：不开 restricted，已安装并启用的插件加载`() = runBlocking { withTimeout(60_000) {
        val work = work(); val home = home()
        seedPlugin(home.resolve("installed-plug"))
        seedInstalledAndUserEnabled(home, home.resolve("installed-plug"))
        val config = DesktopApp(parseArgs(argv(work), work), newScope(), home, claudeHome = home).config()
        assertTrue("对照组该加载已安装插件：${skillNames(config)}", skillNames(config).contains("check:run"))
    } }

    @Test fun `restricted：已安装索引与用户设置启用的插件不加载`() = runBlocking { withTimeout(60_000) {
        val work = work(); val home = home()
        seedPlugin(home.resolve("installed-plug"))
        seedInstalledAndUserEnabled(home, home.resolve("installed-plug"))
        val config = DesktopApp(parseArgs(argv(work, "--restricted"), work), newScope(), home, claudeHome = home).config()
        assertFalse("restricted 不该加载已安装插件：${skillNames(config)}", skillNames(config).contains("check:run"))
    } }

    @Test fun `restricted：--plugin-dir 点名的插件照常加载`() = runBlocking { withTimeout(60_000) {
        val work = work(); val home = home()
        seedPlugin(work.resolve("plug"))
        val config = DesktopApp(parseArgs(argv(work, "--restricted", "--plugin-dir", work.resolve("plug").toString()), work),
            newScope(), home, claudeHome = home).config()
        assertTrue("restricted 下 --plugin-dir 的插件该在：${skillNames(config)}", skillNames(config).contains("check:run"))
    } }

    @Test fun `restricted：--settings 启用、但只登记在安装索引里的插件不加载（索引不是来源）`() = runBlocking { withTimeout(60_000) {
        val work = work(); val home = home()
        // 插件只在安装索引里有（缓存里没有）；启用来自 --settings。索引不是来源，所以不加载
        val plugins = Files.createDirectories(home.resolve(".claude/plugins"))
        seedPlugin(home.resolve("indexed-only"))
        val installPath = home.resolve("indexed-only").toString().replace("\\", "/")
        Files.writeString(plugins.resolve("installed_plugins.json"),
            """{"plugins":{"check@m":[{"scope":"user","installPath":"$installPath"}]}}""")
        val json = """{"enabledPlugins":{"check@m":true}}"""
        val config = DesktopApp(parseArgs(argv(work, "--restricted", "--settings", json), work), newScope(), home, claudeHome = home).config()
        assertFalse("restricted 下索引里的插件不该加载：${skillNames(config)}", skillNames(config).contains("check:run"))
        // 对照：不开 restricted，同样的索引与启用会加载它
        val control = DesktopApp(parseArgs(argv(work, "--settings", json), work), newScope(), home, claudeHome = home).config()
        assertTrue("对照组该加载：${skillNames(control)}", skillNames(control).contains("check:run"))
    } }

    @Test fun `restricted：--settings 启用的插件加载（从缓存找到）`() = runBlocking { withTimeout(60_000) {
        val work = work(); val home = home()
        // 缓存布局 ~/.claude/plugins/cache/<市场>/<插件>/<版本>/，不经安装索引
        seedPlugin(home.resolve(".claude/plugins/cache/m/check/1.0.0"))
        val json = """{"enabledPlugins":{"check@m":true}}"""
        val config = DesktopApp(parseArgs(argv(work, "--restricted", "--settings", json), work), newScope(), home, claudeHome = home).config()
        assertTrue("restricted 下 --settings 启用的插件该在：${skillNames(config)}", skillNames(config).contains("check:run"))
    } }
}
