package dev.min.agent.cli

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** 桌面 CLI 真的按设置里的 `model` 启动、续接被排除的模型时落到这次启动的模型、/model 能切到同家目录里的别的型号 */
class StartupModelDesktopTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = scopes.forEach { it.cancel() }

    private fun app(work: Path, claudeHome: Path, vararg extra: String, env: Map<String, String> = emptyMap()): DesktopApp {
        val args = parseArgs(
            arrayOf("--provider", "anthropic", "--cwd", work.toString(), "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER") + extra,
            work,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DesktopApp(args, scope, tmp.root.toPath().resolve("min-home"), claudeHome = claudeHome, env = env::get)
    }

    private fun dirs(): Pair<Path, Path> {
        val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
        val home = Files.createDirectories(tmp.root.toPath().resolve("claude-home"))
        return work to home
    }

    private fun userSettings(home: Path, json: String) {
        Files.createDirectories(home.resolve(".claude"))
        Files.writeString(home.resolve(".claude/settings.json"), json)
    }

    @Test
    fun `without --model the model setting picks the session model`() = runBlocking { withTimeout(30_000) {
        val (work, home) = dirs()
        userSettings(home, """{"model":"haiku"}""")
        val runtime = app(work, home).openSession()
        assertEquals(ModelRef("anthropic", "claude-haiku-5-5"), runtime.state.value.settings.model)
    } }

    @Test
    fun `ANTHROPIC_MODEL beats the setting and --model beats both`() = runBlocking { withTimeout(30_000) {
        val (work, home) = dirs()
        userSettings(home, """{"model":"haiku"}""")
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), app(work, home, env = mapOf("ANTHROPIC_MODEL" to "sonnet")).openSession().state.value.settings.model)
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), app(work, home, "--model", "opus", env = mapOf("ANTHROPIC_MODEL" to "sonnet")).openSession().state.value.settings.model)
    } }

    @Test
    fun `the model command can switch to another model of the same provider's catalog`() = runBlocking { withTimeout(30_000) {
        val (work, home) = dirs()
        val app = app(work, home, "--model", "sonnet")
        val runtime = app.openSession()
        org.junit.Assert.assertNull(runtime.changeSettings(model = ModelRef("anthropic", "claude-haiku-5-5")))
        // 解析器认得同家目录里的型号：不会在发请求时报「没有配置会话需要的模型」
        app.hub()
        assertEquals(ModelRef("anthropic", "claude-haiku-5-5"), app.sessionModels!!.resolve(ModelRef("anthropic", "claude-haiku-5-5")).profile.ref)
        assertEquals("opusplan 展开出的档位别名也认", ModelRef("anthropic", "claude-opus-5-5"), app.sessionModels!!.resolve(ModelRef("anthropic", "opus")).profile.ref)
    } }

    @Test
    fun `a resumed session whose model the allowlist now excludes falls back to this launch's model`() = runBlocking { withTimeout(30_000) {
        val (work, home) = dirs()
        val first = app(work, home, "--model", "claude-opus-5-5")
        val id = first.openSession().sessionId
        first.hub().close(id)
        userSettings(home, """{"availableModels":["sonnet"]}""")
        val resumed = app(work, home, "--model", "sonnet", "--resume", id.value).openSession()
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), resumed.state.value.settings.model)
        assertEquals(PermissionMode.Default, resumed.state.value.settings.mode)
    } }
}
