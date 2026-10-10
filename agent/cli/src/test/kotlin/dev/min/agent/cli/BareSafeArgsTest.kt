package dev.min.agent.cli

import dev.min.agent.kernel.Customizations
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * `--bare` / `--safe-mode` 的 CLI 面：参数解析与冲突、环境变量等效、--help 说明、组合根的剥除
 * （skills / 命令 / 子 agent 定义不发现）与 bare 的工具表收窄。内核侧的加载开关在 BareSafeModeTest。
 */
class BareSafeArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))
    private fun home(): Path = Files.createDirectories(tmp.root.toPath().resolve("home"))

    /** 与 HeadlessCompatTest 同款的自定义供应商参数：不用真模型（请求不会发出），地址给个占位 */
    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    private fun seedWorkspace(work: Path) {
        Files.createDirectories(work.resolve(".claude/skills/x")).resolve("SKILL.md").toFile()
            .writeText("---\ndescription: X skill\n---\nX body")
        Files.createDirectories(work.resolve(".claude/commands")).resolve("y.md").toFile()
            .writeText("---\ndescription: Y command\n---\nY body")
        Files.createDirectories(work.resolve(".claude/agents")).resolve("z.md").toFile()
            .writeText("---\nname: z\ndescription: Z agent\n---\nZ instructions")
        Files.createDirectories(work.resolve(".claude")).resolve("settings.json").toFile().writeText("{}")
    }

    // ---- 参数解析 ----

    @Test
    fun `--bare 与 --safe-mode 各自能解析`() {
        val bare = parseArgs(arrayOf("--model", "test", "--bare"), work())
        assertTrue(bare.bare)
        assertFalse(bare.safeMode)
        val safe = parseArgs(arrayOf("--model", "test", "--safe-mode"), work())
        assertFalse(safe.bare)
        assertTrue(safe.safeMode)
    }

    @Test
    fun `--bare 与 --safe-mode 同时给报用法错误`() {
        try {
            parseArgs(arrayOf("--model", "test", "--bare", "--safe-mode"), work())
            fail("两个档位同时给要报用法错误")
        } catch (_: UsageException) { }
    }

    @Test
    fun `--help 里列出两个开关与环境变量`() {
        try {
            parseArgs(arrayOf("--model", "test", "--help"), work())
            fail("--help 走 UsageException")
        } catch (e: UsageException) {
            val usage = e.message ?: ""
            assertTrue("--help 该提到 --bare：$usage", usage.contains("--bare"))
            assertTrue("--help 该提到 --safe-mode：$usage", usage.contains("--safe-mode"))
            assertTrue("--help 该提到 CLAUDE_CODE_SIMPLE：$usage", usage.contains("CLAUDE_CODE_SIMPLE"))
            assertTrue("--help 该提到 CLAUDE_CODE_SAFE_MODE：$usage", usage.contains("CLAUDE_CODE_SAFE_MODE"))
        }
    }

    // ---- 环境变量 ----

    @Test
    fun `CLAUDE_CODE_SIMPLE 等效 --bare，CLAUDE_CODE_SAFE_MODE 等效 --safe-mode`() {
        assertEquals(Customizations.Bare, resolveCustomizations(false, false) { k -> if (k == "CLAUDE_CODE_SIMPLE") "1" else null })
        assertEquals(Customizations.Safe, resolveCustomizations(false, false) { k -> if (k == "CLAUDE_CODE_SAFE_MODE") "true" else null })
        assertEquals(Customizations.Full, resolveCustomizations(false, false) { null })
        // 参数与它自己的环境变量同给不冲突（CC：--bare 就是「设置 CLAUDE_CODE_SIMPLE」）
        assertEquals(Customizations.Bare, resolveCustomizations(true, false) { k -> if (k == "CLAUDE_CODE_SIMPLE") "1" else null })
        // 别的值不算开
        assertEquals(Customizations.Full, resolveCustomizations(false, false) { k -> if (k == "CLAUDE_CODE_SIMPLE") "0" else null })
    }

    @Test
    fun `两个档位经环境变量同时给也报用法错误`() {
        try {
            resolveCustomizations(true, false) { k -> if (k == "CLAUDE_CODE_SAFE_MODE") "1" else null }
            fail("--bare 加 CLAUDE_CODE_SAFE_MODE 要报用法错误")
        } catch (_: UsageException) { }
    }

    // ---- 工具表 ----

    @Test
    fun `--bare 工具表只留 CC 点名的 Bash 与文件读写改（NotebookEdit 算改文件）`() {
        assertEquals(listOf("Read", "Write", "Edit", "NotebookEdit", "Bash"), desktopTools(bare = true).map { it.name })
        // 对照：普通档工具表比 bare 宽
        assertTrue(desktopTools().map { it.name }.size > 5)
    }

    // ---- 组合根 ----

    @Test
    fun `普通启动 skills 命令与子 agent 都发现`() = runBlocking { withTimeout(60_000) {
        val work = work()
        seedWorkspace(work)
        val app = DesktopApp(parseArgs(argv(work), work), newScope(), home(), claudeHome = home())
        val config = app.config()
        assertTrue(config.skills.any { it.name == "x" })
        assertTrue(config.commands.any { it.name == "y" })
        assertTrue(config.agents.any { it.name == "z" })
    } }

    @Test
    fun `--bare 不发现 skills 命令与子 agent，settings 照常读，工具表收窄`() = runBlocking { withTimeout(60_000) {
        val work = work()
        seedWorkspace(work)
        val app = DesktopApp(parseArgs(argv(work, "--bare"), work), newScope(), home(), claudeHome = home())
        val config = app.config()
        assertFalse(config.skills.any { it.name == "x" })
        assertFalse(config.commands.any { it.name == "y" })
        assertFalse(config.agents.any { it.name == "z" })
        // settings 本身不受影响（权限规则照常的载体）
        assertNotNull(config.settings)
        // Hub 的工具表跟着收窄（stream-json init 行与内核拿到的是同一份）
        app.hub()
        assertEquals(listOf("Read", "Write", "Edit", "NotebookEdit", "Bash"), app.toolNames())
    } }

    @Test
    fun `--safe-mode 同样不发现 definitions`() = runBlocking { withTimeout(60_000) {
        val work = work()
        seedWorkspace(work)
        val app = DesktopApp(parseArgs(argv(work, "--safe-mode"), work), newScope(), home(), claudeHome = home())
        val config = app.config()
        assertFalse(config.skills.any { it.name == "x" })
        assertFalse(config.commands.any { it.name == "y" })
        assertFalse(config.agents.any { it.name == "z" })
        // safe 的工具表不收窄（内置工具照常）
        app.hub()
        assertTrue(app.toolNames().size > 5)
    } }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
}
