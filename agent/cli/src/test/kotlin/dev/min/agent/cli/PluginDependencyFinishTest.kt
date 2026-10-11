package dev.min.agent.cli

import dev.min.agent.ext.plugins.*
import dev.min.agent.host.FilePluginSources
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.*

class PluginDependencyFinishTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root get() = tmp.root.toPath()
    private val home by lazy { Files.createDirectories(root.resolve("home")) }
    private val work by lazy { Files.createDirectories(root.resolve("work")) }
    private val paths by lazy { DesktopPaths(work, root.resolve("rootfs")) }
    private fun put(path: Path, value: String) { Files.createDirectories(path.parent); Files.writeString(path, value) }
    private fun manager() = PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, Files.createDirectories(root.resolve("logs")), paths.guestOfHost(root.resolve("logs"))),
        GuestPath(paths.guestOfHost(home).value + "/.claude/plugins"), desktopPluginProcess(paths), { paths.hostOf(it).toString() }, { paths.guestOfHost(Path.of(it)) }, paths.guestOfHost(work)),
        paths.guestOfHost(home), paths.guestOfHost(work), { true }, canonical = paths.fs::canonical,
        managedPath = paths.guestOfHost(root.resolve("managed.json")), installationPath = { paths.hostOf(it).toString() })
    private suspend fun add(manager: PluginMarketplace, path: Path) = manager.add(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(path).value) }, true)
    private suspend fun failure(block: suspend () -> Unit): String {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { return e.message.orEmpty() }
        fail("应拒绝安装"); return ""
    }
    @Test fun `环和缺失依赖给出可操作错误且不发布索引`(): Unit = runBlocking {
        val market = root.resolve("deps")
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"deps","owner":{"name":"test"},"plugins":[{"name":"main","source":"./main"},{"name":"base","source":"./base"}]}""")
        put(market.resolve("main/.claude-plugin/plugin.json"), """{"name":"main","dependencies":["base"]}""")
        put(market.resolve("base/.claude-plugin/plugin.json"), """{"name":"base","dependencies":["main"]}""")
        val manager = manager(); add(manager, market)
        assertTrue(failure { manager.install(manager.preview("main@deps"), confirmed = true) }.contains("cycle"))
        assertFalse("解析环时不能写安装索引", Files.exists(home.resolve(".claude/plugins/installed_plugins.json")))
        put(market.resolve("main/.claude-plugin/plugin.json"), """{"name":"main","dependencies":["missing"]}"""); manager.refresh("deps")
        assertTrue(failure { manager.install(manager.preview("main@deps"), confirmed = true) }.contains("Dependency \"missing@deps\" is not installed"))
        assertTrue(manager.installed().isEmpty())
    }
    @Test fun `跨市场默认拒绝且根 allowlist 与托管名单共同限制依赖`(): Unit = runBlocking {
        val a = root.resolve("a"); val b = root.resolve("b")
        fun rootCatalog(allowed: Boolean) = """{"name":"root","owner":{"name":"test"},${if (allowed) "\"allowCrossMarketplaceDependenciesOn\":[\"shared\"]," else ""}"plugins":[{"name":"main","source":"./main"}]}"""
        put(a.resolve(".claude-plugin/marketplace.json"), rootCatalog(false))
        put(a.resolve("main/.claude-plugin/plugin.json"), """{"name":"main","dependencies":["base@shared"]}""")
        put(b.resolve(".claude-plugin/marketplace.json"), """{"name":"shared","owner":{"name":"test"},"plugins":[{"name":"base","source":"./base"}]}""")
        put(b.resolve("base/.claude-plugin/plugin.json"), """{"name":"base"}""")
        val manager = manager(); add(manager, a); add(manager, b)
        assertTrue(failure { manager.install(manager.preview("main@root"), confirmed = true) }.contains("not in the allowlist"))
        assertTrue(manager.installed().isEmpty())
        put(a.resolve(".claude-plugin/marketplace.json"), rootCatalog(true)); manager.refresh("root")
        put(root.resolve("managed.json"), buildJsonObject { put("strictKnownMarketplaces", JsonArray(listOf(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(a).value) }))) }.toString())
        assertTrue(failure { manager.install(manager.preview("main@root"), confirmed = true) }.contains("strictKnownMarketplaces"))
        assertTrue(manager.installed().isEmpty()); Files.delete(root.resolve("managed.json"))
        manager.install(manager.preview("main@root"), confirmed = true)
        assertEquals(setOf("main@root", "base@shared"), manager.installed().map { it.id }.toSet())
    }
    @Test fun `自动依赖绝不执行 command 并遵守显式禁用`(): Unit = runBlocking {
        val market = root.resolve("deps"); val executed = root.resolve("executed"); val plugin = root.resolve("generated")
        put(plugin.resolve(".claude-plugin/plugin.json"), """{"name":"base"}""")
        val command = if (System.getProperty("os.name").startsWith("Windows")) "echo executed>\"$executed\" & echo $plugin" else "touch '$executed'; printf '%s\\n' '$plugin'"
        put(market.resolve(".claude-plugin/marketplace.json"), buildJsonObject {
            put("name", "deps"); put("owner", buildJsonObject { put("name", "test") })
            put("plugins", JsonArray(listOf(buildJsonObject { put("name", "main"); put("source", "./main") }, buildJsonObject { put("name", "base"); put("source", buildJsonObject { put("source", "command"); put("command", command) }) })))
        }.toString())
        put(market.resolve("main/.claude-plugin/plugin.json"), """{"name":"main","dependencies":["base"]}""")
        val manager = manager(); add(manager, market)
        failure { manager.install(manager.preview("main@deps"), confirmed = true) }; assertFalse(Files.exists(executed)); assertTrue(manager.installed().isEmpty())
        put(root.resolve("managed.json"), """{"enabledPlugins":{"base@deps":false}}""")
        assertTrue(failure { manager.install(manager.preview("main@deps"), confirmed = true) }.contains("disabled by managed")); assertFalse(Files.exists(executed))
    }
    @Test fun `git 依赖选择满足范围的最高插件标签`(): Unit = runBlocking {
        val repo = Files.createDirectories(root.resolve("repository"))
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=Eval", "-c", "user.email=eval@example.invalid") + args).directory(repo.toFile()).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText(); assertEquals(text, 0, process.waitFor())
        }
        git("init", "-q")
        for (version in listOf("1.0.0", "1.5.0", "2.0.0")) {
            put(repo.resolve(".claude-plugin/plugin.json"), """{"name":"base","version":"$version"}""")
            git("add", "."); git("commit", "-qm", version); git("tag", "base--v$version")
        }
        val market = root.resolve("deps")
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"deps","owner":{"name":"test"},"plugins":[{"name":"main","source":"./main"},{"name":"base","source":{"source":"url","url":${JsonPrimitive(repo.toUri().toString())}}}]}""")
        put(market.resolve("main/.claude-plugin/plugin.json"), """{"name":"main","dependencies":[{"name":"base","version":"^1.0.0"}]}""")
        val manager = manager(); add(manager, market)
        var problem: String? = null
        try { manager.install(manager.preview("main@deps"), confirmed = true) } catch(e: CancellationException) { throw e } catch(e: Exception) { problem = e.message }
        assertNull("版本范围应解析到已有 tag", problem)
        val base = manager.installed().single { it.id == "base@deps" }
        assertTrue(base.version.startsWith("1.5.0-"))
        val problemUpdate = failure { manager.install(manager.preview("base@deps"), confirmed = true) }
        assertTrue(problemUpdate, problemUpdate.contains("conflicting version requirements"))
        assertEquals(base.version, manager.installed().single { it.id == "base@deps" }.version)
    }
}
