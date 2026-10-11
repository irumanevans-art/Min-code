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

class PluginSourceSecurityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root get() = tmp.root.toPath()
    private val home by lazy { Files.createDirectories(root.resolve("home")) }
    private val work by lazy { Files.createDirectories(root.resolve("work")) }
    private val paths by lazy { DesktopPaths(work, root.resolve("rootfs")) }
    private fun put(path: Path, value: String) { Files.createDirectories(path.parent); Files.writeString(path, value) }
    private val secret = "PLUGIN_AUTH_DO_NOT_LOG_76192"
    private fun manager(): PluginMarketplace {
        val logs = Files.createDirectories(root.resolve("logs"))
        return PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, logs, paths.guestOfHost(logs)),
            GuestPath(paths.guestOfHost(home).value + "/.claude/plugins"), desktopPluginProcess(paths) { System.getenv() + ("GITHUB_TOKEN" to secret) },
            { paths.hostOf(it).toString() }, { paths.guestOfHost(Path.of(it)) }, paths.guestOfHost(work)), paths.guestOfHost(home), paths.guestOfHost(work), { true },
            canonical = paths.fs::canonical, managedPath = paths.guestOfHost(root.resolve("managed.json")), installationPath = { paths.hostOf(it).toString() })
    }
    @Test fun `拒绝确认和托管禁止时命令没有副作用且 command 环境不带令牌`(): Unit = runBlocking {
        val plugin = root.resolve("generated"); val executed = root.resolve("executed"); val leaked = root.resolve("leaked")
        put(plugin.resolve(".claude-plugin/plugin.json"), """{"name":"generated"}""")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val command = if (windows) "(if defined GITHUB_TOKEN echo leak>\"$leaked\") & echo executed>\"$executed\" & echo $plugin"
            else "[ -z \"\$GITHUB_TOKEN\" ] || touch '$leaked'; touch '$executed'; printf '%s\\n' '$plugin'"
        val market = root.resolve("market")
        fun catalog(cmd: String) = buildJsonObject {
            put("name", "security"); put("owner", buildJsonObject { put("name", "test") })
            put("plugins", JsonArray(listOf(buildJsonObject { put("name", "generated"); put("source", buildJsonObject { put("source", "command"); put("command", cmd) }) })))
        }.toString()
        put(market.resolve(".claude-plugin/marketplace.json"), catalog(command))
        val manager = manager(); manager.add(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(market).value) }, true)
        val output = java.io.PrintStream(java.io.ByteArrayOutputStream())
        assertEquals(1, pluginCommand(manager, listOf("install", "generated@security"), "n\n".reader().buffered(), output))
        assertFalse("确认被拒后不能先执行命令", Files.exists(executed))
        assertEquals(0, pluginCommand(manager, listOf("install", "generated@security", "--yes"), "".reader().buffered(), output))
        assertTrue(Files.exists(executed)); assertFalse("command 不能继承令牌环境", Files.exists(leaked)); Files.delete(executed)
        put(root.resolve("managed.json"), """{"disableCommandPluginSources":true}""")
        assertEquals(1, pluginCommand(manager, listOf("update", "generated@security", "--yes"), "".reader().buffered(), output))
        assertFalse("托管禁止必须在进程启动前生效", Files.exists(executed))
        val layers = dev.min.agent.ext.config.SettingsLoader.load(paths.fs, paths.guestOfHost(work), paths.guestOfHost(home), paths.guestOfHost(root.resolve("managed.json")))
        val bundle = PluginBundle.load(paths.fs, paths.guestOfHost(work), paths.guestOfHost(home), layers.merged, canonical = paths.fs::canonical, installedPath = { paths.guestOfHost(Path.of(it)) })
        assertTrue("托管禁用还须阻止已经安装的 command 插件加载", bundle.batch.plugins.isEmpty())
        Files.delete(root.resolve("managed.json"))
        val accepted = manager.commandsToAccept("generated@security").map { it.sha256 }.toSet()
        put(market.resolve(".claude-plugin/marketplace.json"), catalog("echo $plugin")); manager.refresh("security")
        var refused = false
        try { manager.preview("generated@security", accepted) } catch(e: CancellationException) { throw e } catch(e: IllegalArgumentException) { refused = true }
        assertTrue("旧 SHA 不授权新命令", refused)
    }
    @Test fun `headersHelper 认证可用但凭据不进 Exec 日志与插件记录`(): Unit = runBlocking {
        val received = mutableListOf<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val catalog = """{"name":"private","owner":{"name":"test"},"plugins":[]}""".toByteArray()
        server.createContext("/marketplace.json") { exchange ->
            synchronized(received) { received += exchange.requestHeaders.getFirst("Authorization").orEmpty() }
            exchange.sendResponseHeaders(200, catalog.size.toLong()); exchange.responseBody.use { it.write(catalog) }
        }
        server.start()
        try {
            val helper = if (System.getProperty("os.name").startsWith("Windows")) "echo {\"Authorization\":\"Bearer %GITHUB_TOKEN%\"}" else "printf '{\"Authorization\":\"Bearer %s\"}' \"\$GITHUB_TOKEN\""
            val source = buildJsonObject { put("source", "url"); put("url", "http://127.0.0.1:${server.address.port}/marketplace.json"); put("headersHelper", helper) }
            val manager = manager()
            var refused = false
            try { manager.add(source, false) } catch(e: CancellationException) { throw e } catch(e: IllegalArgumentException) { refused = true }
            assertTrue(refused); assertTrue(received.isEmpty())
            manager.add(source, true); manager.refresh("private")
            assertTrue(received.isNotEmpty() && received.all { it == "Bearer $secret" })
            assertTrue(Files.list(root.resolve("logs")).use { it.findAny().isEmpty })
            Files.walk(home).use { files -> files.filter { Files.isRegularFile(it) }.forEach { assertFalse("插件记录不能保存凭据真值：$it", Files.readString(it).contains(secret)) } }
            assertFalse(redactedPluginSource(buildJsonObject { put("headers", buildJsonObject { put("Authorization", secret) }) }).toString().contains(secret))
        } finally { server.stop(0) }
    }
}
