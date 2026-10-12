package dev.min.agent.cli

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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--init-only` / `--init` / `--maintenance`（cli-reference）触发 Setup hooks（hooks 页「Setup」）：真 CLI 参数解析、真 DesktopApp、真 shell 跑 hook。
 * `--init` 与 `--maintenance` 只在 `-p` 下生效（原文 print mode only）；`--init-only` 在建会话时跑完 Setup 与 SessionStart，由 Main 随即退出。
 */
class SetupFlagsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun base(work: Path) = arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9",
        "--mode", "bypassPermissions", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString())

    @Test fun `flags parse into setup triggers, and init or maintenance without -p fire nothing`() {
        val work = Files.createDirectories(tmp.root.toPath().resolve("w"))
        fun triggers(vararg extra: String) = parseArgs(base(work) + extra, work).setupTriggers
        assertEquals(emptyList<String>(), triggers())
        assertEquals(listOf("init"), triggers("--init-only"))
        assertEquals(listOf("init"), triggers("-p", "hi", "--init"))
        assertEquals(listOf("maintenance"), triggers("-p", "hi", "--maintenance"))
        assertEquals(listOf("init", "maintenance"), triggers("-p", "hi", "--init", "--maintenance"))
        assertEquals("原文：--init / --maintenance 只在 -p 下", emptyList<String>(), triggers("--init"))
        assertEquals(emptyList<String>(), triggers("--maintenance"))
        assertEquals(listOf("init"), triggers("-p", "hi", "--init-only", "--init"))
        assertTrue(parseArgs(base(work) + "--init-only", work).initOnly)
    }

    private fun open(work: Path, vararg extra: String, settings: String): Pair<DesktopApp, dev.min.agent.kernel.SessionRuntime> = runBlocking {
        withTimeout(120_000) {
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            val file = home.resolve("claude-home/.claude/settings.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, settings)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val args = parseArgs(base(work) + extra, work)
            val app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            app to app.openSession()
        }
    }

    private fun hooksJson(marker: Path): String {
        val m = marker.toString().replace('\\', '/')
        return """{"autoMemoryEnabled":false,"hooks":{
            "Setup":[{"matcher":"init","hooks":[{"type":"command","command":"cat >> '$m'; echo >> '$m'"}]},
                     {"matcher":"maintenance","hooks":[{"type":"command","command":"cat >> '$m'; echo >> '$m'; exit 2"}]}],
            "SessionStart":[{"hooks":[{"type":"command","command":"echo SESSIONSTART >> '$m'"}]}]}}"""
    }

    @Test fun `init-only runs Setup with trigger init and then SessionStart, with a real shell`() {
        val work = Files.createDirectories(tmp.root.toPath().resolve("w"))
        val marker = tmp.root.toPath().resolve("marker.log")
        val (_, runtime) = open(work, "--init-only", settings = hooksJson(marker))
        val log = Files.readString(marker)
        assertTrue(log, log.contains("\"hook_event_name\":\"Setup\"") && log.contains("\"trigger\":\"init\""))
        assertFalse("matcher maintenance 不触发", log.contains("\"trigger\":\"maintenance\""))
        assertTrue("Setup 在 SessionStart 之前：$log", log.indexOf("\"trigger\":\"init\"") < log.indexOf("SESSIONSTART"))
        assertTrue(runtime.state.value.turn == null)
    }

    @Test fun `-p with --maintenance fires the maintenance matcher, and exit 2 of a Setup hook changes nothing`() {
        val work = Files.createDirectories(tmp.root.toPath().resolve("w"))
        val marker = tmp.root.toPath().resolve("marker.log")
        val (_, runtime) = open(work, "-p", "hi", "--maintenance", settings = hooksJson(marker))
        val log = Files.readString(marker)
        assertTrue(log, log.contains("\"trigger\":\"maintenance\""))
        assertFalse(log, log.contains("\"trigger\":\"init\""))
        assertTrue("exit 2 不阻断：会话照常建好、SessionStart 照跑：$log", log.contains("SESSIONSTART"))
        assertTrue(runtime.state.value.turn == null)
    }

    @Test fun `a plain start fires no Setup`() {
        val work = Files.createDirectories(tmp.root.toPath().resolve("w"))
        val marker = tmp.root.toPath().resolve("marker.log")
        open(work, "-p", "hi", settings = hooksJson(marker))
        val log = Files.readString(marker)
        assertFalse(log, log.contains("\"hook_event_name\":\"Setup\""))
        assertTrue(log.contains("SESSIONSTART"))
    }
}
