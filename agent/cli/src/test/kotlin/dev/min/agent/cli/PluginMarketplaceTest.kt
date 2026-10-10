package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.ext.config.SettingsLoader
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
import java.net.InetSocketAddress
import java.util.zip.*
import java.io.ByteArrayOutputStream

class PluginMarketplaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.root.toPath() }
    private val home by lazy { Files.createDirectories(root.resolve("home")) }
    private val work by lazy { Files.createDirectories(root.resolve("work")) }
    private val paths by lazy { DesktopPaths(work, root.resolve("scratch")) }
    private fun guest(path: Path) = paths.guestOfHost(path)
    private var trust = false
    private fun manager(): PluginMarketplace {
        val logs = Files.createDirectories(root.resolve("logs"))
        return PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, logs, guest(logs)), GuestPath(guest(home).value + "/.claude/plugins")), guest(home), guest(work), { trust }, canonical = paths.fs::canonical,
            managedPath = guest(root.resolve("managed.json")), installationPath = { paths.hostOf(it).toString() })
    }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun put(path: Path, value: String) { Files.createDirectories(path.parent); Files.writeString(path, value) }
    private fun fixture(): Path {
        val market = Files.createDirectories(root.resolve("market"))
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"local-market","owner":{"name":"Test"},"plugins":[{"name":"demo","source":"./plugins/demo"}]}""")
        plugin(market.resolve("plugins/demo"))
        return market
    }
    private fun plugin(plugin: Path) {
        put(plugin.resolve(".claude-plugin/plugin.json"), """{"name":"demo","version":"1.0.0","experimental":{"monitors":[{"name":"watch","description":"watch","command":"echo safe"}]}}""")
        put(plugin.resolve("commands/hello.md"), "---\ndescription: Say hello\n---\nMARKET_COMMAND_BODY")
        put(plugin.resolve("agents/helper.md"), "---\nname: helper\ndescription: Help inspect\n---\nInspect files.")
        put(plugin.resolve("hooks/hooks.json"), """{"hooks":{"PreToolUse":[{"matcher":"Read","hooks":[{"type":"command","command":"echo safe"}]}]}}""")
        put(plugin.resolve(".mcp.json"), """{"test":{"command":"echo","args":["safe"]}}""")
        put(plugin.resolve(".lsp.json"), """{"test":{"command":"test-server","extensionToLanguage":{".kt":"kotlin"}}}""")
    }
    private fun source(market: Path) = buildJsonObject { put("source", "directory"); put("path", guest(market).value) }
    private suspend fun rejected(part: String, action: suspend () -> Unit) {
        var caught: Exception? = null
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { caught = e }
        assertNotNull("必须拒绝：$part", caught)
        assertTrue("拒绝必须来自对应边界：${caught?.message}", caught!!.message.orEmpty().contains(part))
    }
    private suspend fun bundle() = PluginBundle.load(paths.fs, guest(work), guest(home), SettingsLoader.load(paths.fs, guest(work), guest(home), guest(root.resolve("managed.json"))).merged,
        canonical = paths.fs::canonical, trust = { trust }, installedPath = { paths.guestOfHost(Path.of(it)) })
    private fun git(repo: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
    @Test fun localGitFullLifecycleLoadsExistingBundleAndCliPrintsComponentsBeforeConfirm(): Unit = runBlocking {
        val repo = fixture()
        git(repo, "init"); git(repo, "config", "core.autocrlf", "false"); git(repo, "add", "."); git(repo, "-c", "user.name=test", "-c", "user.email=test@local", "commit", "-m", "fixture")
        val manager = manager()
        assertEquals(listOf(OFFICIAL_MARKETPLACE), manager.markets().map { it.name })
        assertTrue(manager.markets().single().autoUpdate)
        manager.add(buildJsonObject { put("source", "git"); put("url", repo.toUri().toString()) }, confirmed = true)
        assertEquals("demo", manager.browse("local-market").plugins.single().getValue("name").jsonPrimitive.content)
        val output = ByteArrayOutputStream()
        assertEquals(1, pluginCommand(manager, listOf("install", "demo@local-market"), "n\n".reader().buffered(), java.io.PrintStream(output)))
        assertTrue(manager.installed().isEmpty()); assertTrue(output.toString().contains("PreToolUse / command"))
        assertTrue(output.toString().contains("demo:hello")); assertTrue(output.toString().contains("plugin:demo:test")); assertTrue(output.toString().contains("Monitors: watch")); assertTrue(output.toString().contains("LSP: test"))
        assertEquals(0, pluginCommand(manager, listOf("install", "demo@local-market", "--yes"), "".reader().buffered(), java.io.PrintStream(output)))
        assertEquals(listOf("demo:hello"), bundle().commands.map { it.name })
        assertTrue(manager.installed().single().installPath.contains("cache"))
        manager.setEnabled("demo@local-market", false)
        assertTrue(bundle().commands.isEmpty())
        manager.uninstall("demo@local-market")
        assertTrue(manager.installed().isEmpty()); manager.removeMarket("local-market")
        assertEquals(listOf(OFFICIAL_MARKETPLACE), manager.markets().map { it.name })
    }
    private fun zip(plugin: Path): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> Files.walk(plugin).use { stream -> stream.filter { Files.isRegularFile(it) && plugin.relativize(it).none { part -> part.toString() == ".git" } }.forEach { file ->
            zip.putNextEntry(ZipEntry(plugin.relativize(file).toString().replace('\\', '/'))); zip.write(Files.readAllBytes(file)); zip.closeEntry()
        } } }
        return out.toByteArray()
    }
    @Test fun urlMarketplaceAndArchivePluginUseOnlyLocalHttp(): Unit = runBlocking {
        val files = fixture(); val bytes = zip(files.resolve("plugins/demo"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val manifest = """{"name":"http-market","owner":{"name":"Test"},"plugins":[{"name":"demo","source":{"source":"archive","url":"$base/plugin.zip"}}]}""".toByteArray()
        server.createContext("/marketplace.json") { ex -> ex.sendResponseHeaders(200, manifest.size.toLong()); ex.responseBody.use { it.write(manifest) } }
        server.createContext("/plugin.zip") { ex -> ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        server.start()
        try {
            val manager = manager(); manager.add(buildJsonObject { put("source", "url"); put("url", "$base/marketplace.json") }, true)
            val preview = manager.preview("demo@http-market"); manager.install(preview, confirmed = true)
            assertEquals("demo:hello", bundle().commands.single().name)
            manager.uninstall("demo@http-market"); assertTrue(manager.installed().isEmpty())
        } finally { server.stop(0) }
    }
    @Test fun pluginUrlMeansGitAndLocalArchiveWorks(): Unit = runBlocking {
        val market = fixture(); val repo = market.resolve("plugins/demo")
        git(repo, "init"); git(repo, "config", "core.autocrlf", "false"); git(repo, "add", "."); git(repo, "-c", "user.name=test", "-c", "user.email=test@local", "commit", "-m", "plugin")
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"git-plugins","owner":{"name":"Test"},"plugins":[{"name":"demo","source":{"source":"url","url":"${repo.toUri()}"}}]}""")
        val manager = manager(); manager.add(source(market), true); manager.install(manager.preview("demo@git-plugins"), confirmed = true)
        assertEquals("demo:hello", bundle().commands.single().name)
        val archive = root.resolve("plugin.zip"); Files.write(archive, zip(repo))
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"archives","owner":{"name":"Test"},"plugins":[{"name":"demo","source":{"source":"archive","url":"${guest(archive).value}"}}]}""")
        manager.add(source(market), true); manager.install(manager.preview("demo@archives"), confirmed = true)
        assertEquals(2, manager.installed().size)
    }
    @Test fun unconfirmedThirdPartyCannotAddOrInstall(): Unit = runBlocking {
        val market = fixture(); val manager = manager()
        rejected("第三方插件能跑命令") { manager.add(source(market)) }
        assertEquals(listOf(OFFICIAL_MARKETPLACE), manager.markets().map { it.name })
        val saved = buildJsonObject { put("local-market", buildJsonObject { put("source", source(market)); put("installLocation", guest(market).value); put("confirmed", false) }) }
        put(home.resolve(".claude/plugins/known_marketplaces.json"), saved.toString())
        rejected("第三方源尚未确认") { manager.preview("demo@local-market") }
        assertTrue(manager.installed().isEmpty())
    }
    @Test fun managedAllowlistCheckedBeforeFetchAndBeforeInstall(): Unit = runBlocking {
        val market = fixture(); val manager = manager()
        put(root.resolve("managed.json"), """{"strictKnownMarketplaces":[]}""")
        rejected("strictKnownMarketplaces") { manager.add(source(market), true) }
        assertEquals(0, Files.list(home.resolve(".claude/plugins")).use { it.filter { path -> path.fileName.toString() == ".staging" }.count().toInt() })
        put(root.resolve("managed.json"), buildJsonObject { put("strictKnownMarketplaces", JsonArray(listOf(source(market)))) }.toString())
        manager.add(source(market), true); val preview = manager.preview("demo@local-market")
        put(root.resolve("managed.json"), """{"strictKnownMarketplaces":[]}""")
        rejected("strictKnownMarketplaces") { manager.install(preview, confirmed = true) }
        assertTrue(manager.installed().isEmpty()); manager.discard(preview)
    }
    @Test fun untrustedProjectSettingsCannotAddFetchOrInstall(): Unit = runBlocking {
        val market = fixture(); val manager = manager()
        put(work.resolve(".claude/settings.json"), buildJsonObject { put("extraKnownMarketplaces", buildJsonObject { put("local-market", buildJsonObject { put("source", source(market)) }) }); put("enabledPlugins", buildJsonObject { put("demo@local-market", true) }) }.toString())
        assertTrue(manager.reconcileSettings().isEmpty()); assertTrue(manager.installed().isEmpty())
        assertEquals(listOf(OFFICIAL_MARKETPLACE), manager.markets().map { it.name })
        manager.add(source(market), true); val preview = manager.preview("demo@local-market")
        rejected("尚未信任") { manager.install(preview, "project", true) }
        rejected("尚未信任") { manager.install(preview, "local", true) }
        trust = true; manager.install(preview, "project", true)
        assertEquals("demo:hello", bundle().commands.single().name)
        trust = false; assertTrue(bundle().commands.isEmpty())
    }
    @Test fun namesDuplicatesAndOfficialImpersonationAreRejected(): Unit = runBlocking {
        val market = fixture(); val manager = manager()
        for ((manifest, reason) in listOf(
            """{"name":"../bad","owner":{"name":"Test"},"plugins":[]}""" to "名字非法",
            """{"name":"m","owner":{"name":"Test"},"plugins":[{"name":"demo","source":"./plugins/demo"},{"name":"demo","source":"./plugins/demo"}]}""" to "同名插件冲突",
            """{"name":"claude-plugins-official","owner":{"name":"Test"},"plugins":[]}""" to "不能被第三方冒用")) {
            put(market.resolve(".claude-plugin/marketplace.json"), manifest)
            rejected(reason) { manager.add(source(market), true) }
        }
        assertTrue(manager.installed().isEmpty())
    }
    @Test fun confirmationFreezesPreviewAndCancellationPropagates(): Unit = runBlocking {
        val manager = manager(); manager.add(source(fixture()), true)
        val preview = manager.preview("demo@local-market")
        rejected("核对安装组件") { manager.install(preview) }
        val raw = preview.components.root
        paths.fs.writeAtomic(GuestPath(raw + "/commands/hello.md"), "CHANGED".toByteArray())
        rejected("预览文件已变化") { manager.install(preview, confirmed = true) }
        manager.discard(preview); assertTrue(manager.installed().isEmpty())
    }
    @Test fun untrustedProjectCannotOverridePersonalPluginEnablement(): Unit = runBlocking {
        val manager = manager(); manager.add(source(fixture()), true); manager.install(manager.preview("demo@local-market"), confirmed = true)
        put(work.resolve(".claude/settings.json"), """{"enabledPlugins":{"demo@local-market":false}}""")
        assertEquals(listOf("demo:hello"), bundle().commands.map { it.name })
        trust = true; assertTrue(bundle().commands.isEmpty())
    }
    @Test fun autoUpdateRetainsDisabledStateAndRequiresReviewForNewComponents(): Unit = runBlocking {
        val market = fixture(); val manager = manager(); manager.add(source(market), true)
        manager.install(manager.preview("demo@local-market"), confirmed = true); manager.setEnabled("demo@local-market", false); manager.setAutoUpdate("local-market", true)
        put(market.resolve("plugins/demo/commands/hello.md"), "---\ndescription: Say hello\n---\nUPDATED_BODY")
        assertTrue(manager.autoUpdate().single().contains("已更新")); assertFalse(manager.installed().single().enabled)
        put(market.resolve("plugins/demo/commands/new-command.md"), "New command")
        assertTrue(manager.autoUpdate().single().contains("请预览后更新")); assertFalse(manager.installed().single().enabled)
    }
    @Test fun entryOnlyPluginAndSourceParserRemainCompatibleWithIndex(): Unit = runBlocking {
        val market = fixture()
        Files.delete(market.resolve("plugins/demo/.claude-plugin/plugin.json"))
        put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"entry-market","owner":{"name":"Test"},"plugins":[{"name":"demo","source":"./plugins/demo","commands":{"inline":{"content":"INLINE_MARKET_BODY"}}}]}""")
        val manager = manager(); manager.add(source(market), true); manager.install(manager.preview("demo@entry-market"), confirmed = true)
        assertTrue(bundle().commands.any { it.name == "demo:inline" && it.body == "INLINE_MARKET_BODY" })
        assertEquals("git", PluginMarketplace.sourceInput("https://gitlab.com/team/market#main").getValue("source").jsonPrimitive.content)
        assertEquals("main", PluginMarketplace.sourceInput("https://gitlab.com/team/market#main").getValue("ref").jsonPrimitive.content)
        assertEquals(OFFICIAL_MARKETPLACE_SOURCE, PluginMarketplace.sourceInput("https://github.com/anthropics/claude-plugins-official.git"))
        assertFalse(pluginAutoUpdatesEnabled(mapOf("DISABLE_AUTOUPDATER" to "1")))
        assertTrue(pluginAutoUpdatesEnabled(mapOf("DISABLE_AUTOUPDATER" to "1", "FORCE_AUTOUPDATE_PLUGINS" to "1")))
    }
    @Test fun settingsAutoUpdateOverridesDiskWithoutTrustingProject(): Unit = runBlocking {
        val market = fixture(); val manager = manager(); manager.add(source(market), true)
        put(work.resolve(".claude/settings.json"), buildJsonObject { put("extraKnownMarketplaces", buildJsonObject { put("local-market", buildJsonObject { put("source", source(market)); put("autoUpdate", true) }) }) }.toString())
        assertFalse(manager.markets().single { it.name == "local-market" }.autoUpdate)
        trust = true; assertTrue(manager.markets().single { it.name == "local-market" }.autoUpdate)
        put(root.resolve("managed.json"), buildJsonObject { put("extraKnownMarketplaces", buildJsonObject { put("local-market", buildJsonObject { put("source", source(market)); put("autoUpdate", false) }) }) }.toString())
        assertFalse(manager.markets().single { it.name == "local-market" }.autoUpdate)
    }
    @Test fun cancelledHostFetchPropagatesAndCleansStaging(): Unit = runBlocking {
        var cleaned = false
        val host = object : PluginSourceHost {
            override suspend fun fetch(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits) { throw CancellationException("fixture-cancel") }
            override suspend fun copy(source: GuestPath, destination: GuestPath, limits: PluginFetchLimits) = error("不得复制")
            override suspend fun remove(path: GuestPath) { cleaned = true }
            override suspend fun <T> locked(block: suspend () -> T): T = block()
        }
        val manager = PluginMarketplace(paths.fs, host, guest(home), guest(work), { false }, managedPath = guest(root.resolve("managed.json")))
        try { manager.add(source(fixture()), true); fail("必须传播取消") }
        catch (e: CancellationException) { assertEquals("fixture-cancel", e.message) }
        assertTrue(cleaned); assertTrue(manager.installed().isEmpty())
    }
}
