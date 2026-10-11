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

class PluginFinishTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root get() = tmp.root.toPath()
    private val home by lazy { Files.createDirectories(root.resolve("home")) }
    private val work by lazy { Files.createDirectories(root.resolve("work")) }
    private val paths by lazy { DesktopPaths(work, root.resolve("rootfs")) }
    private var registry: String? = null
    private fun put(path: Path, value: String) { Files.createDirectories(path.parent); Files.writeString(path, value) }
    private fun manager(): PluginMarketplace {
        val logs = Files.createDirectories(root.resolve("logs"))
        return PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, logs, paths.guestOfHost(logs)),
            GuestPath(paths.guestOfHost(home).value + "/.claude/plugins"), desktopPluginProcess(paths) {
                System.getenv() + mapOf("NPM_CONFIG_CACHE" to root.resolve("npm-cache").toString()) + registry?.let { mapOf("NPM_CONFIG_REGISTRY" to it) }.orEmpty()
            }, { paths.hostOf(it).toString() }, { paths.guestOfHost(Path.of(it)) }, paths.guestOfHost(work)), paths.guestOfHost(home), paths.guestOfHost(work), { true },
            canonical = paths.fs::canonical, managedPath = paths.guestOfHost(root.resolve("managed.json")), installationPath = { paths.hostOf(it).toString() })
    }
    @Test fun `npm 包通过本机 registry 拉取且安装脚本不执行`(): Unit = runBlocking {
        val content = linkedMapOf(
            ".claude-plugin/plugin.json" to """{"name":"npm-demo","version":"1.2.3"}""",
            "commands/hello.md" to "npm-command",
            "package.json" to """{"name":"npm-demo","version":"1.2.3","scripts":{"postinstall":"echo UNSAFE_SCRIPT"}}""")
        val archive = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(archive).use { gzip ->
            for ((path, value) in content) {
                val bytes = value.toByteArray(); val header = ByteArray(512)
                fun field(at: Int, size: Int, text: String) { text.toByteArray().copyInto(header, at, 0, minOf(size, text.length)) }
                field(0, 100, "package/$path"); field(100, 8, "0000644"); field(124, 12, bytes.size.toString(8).padStart(11, '0')); field(148, 8, "        "); header[156] = '0'.code.toByte()
                field(257, 6, "ustar"); field(148, 8, header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
                gzip.write(header); gzip.write(bytes); gzip.write(ByteArray((512 - bytes.size % 512) % 512))
            }
            gzip.write(ByteArray(1024))
        }
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        registry = "http://127.0.0.1:${server.address.port}"
        val tar = archive.toByteArray()
        val packument = """{"name":"npm-demo","dist-tags":{"latest":"1.2.3"},"versions":{"1.2.3":{"name":"npm-demo","version":"1.2.3","dist":{"tarball":"$registry/npm-demo/-/npm-demo-1.2.3.tgz"}}}}""".toByteArray()
        val requested = mutableListOf<String>()
        server.createContext("/") { exchange ->
            synchronized(requested) { requested += exchange.requestURI.path }
            val data = if (exchange.requestURI.path.endsWith(".tgz")) tar else packument
            exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val market = root.resolve("npm-market")
            put(market.resolve(".claude-plugin/marketplace.json"), """{"name":"npm-market","owner":{"name":"test"},"plugins":[{"name":"npm-demo","source":{"source":"npm","package":"npm-demo","version":"1.2.3","registry":"$registry"}}]}""")
            val manager = manager(); manager.add(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(market).value) }, true)
            val output = java.io.ByteArrayOutputStream()
            assertEquals("本机 npm registry 必须可安装：$output", 0, pluginCommand(manager, listOf("install", "npm-demo@npm-market", "--yes"), "".reader().buffered(), java.io.PrintStream(output)))
            assertTrue(requested.any { it.endsWith(".tgz") })
            assertFalse(output.toString().contains("UNSAFE_SCRIPT"))
            assertEquals("npm-command", Files.readString(Path.of(manager.installed().single().installPath).resolve("commands/hello.md")))
            assertTrue("认证和 npm stdout 不应进入 Exec 日志", Files.list(root.resolve("logs")).use { it.findAny().isEmpty })
        } finally { server.stop(0) }
    }
    @Test fun `command 执行前确认并遵守托管禁止`(): Unit = runBlocking {
        val plugin = root.resolve("generated")
        put(plugin.resolve(".claude-plugin/plugin.json"), """{"name":"generated"}""")
        put(plugin.resolve("commands/hello.md"), "hello")
        val market = root.resolve("commands")
        val command = if (System.getProperty("os.name").startsWith("Windows")) "echo $plugin" else "printf '%s\\n' '$plugin'"
        put(market.resolve(".claude-plugin/marketplace.json"), buildJsonObject {
            put("name", "commands"); put("owner", buildJsonObject { put("name", "test") })
            put("plugins", JsonArray(listOf(buildJsonObject { put("name", "generated"); put("source", buildJsonObject { put("source", "command"); put("command", command) }) })))
        }.toString())
        val manager = manager(); manager.add(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(market).value) }, true)
        val output = java.io.ByteArrayOutputStream()
        assertEquals(1, pluginCommand(manager, listOf("install", "generated@commands"), "n\n".reader().buffered(), java.io.PrintStream(output)))
        assertTrue(manager.installed().isEmpty())
        assertTrue("确认必须显示完整命令", output.toString().contains(command))
        output.reset()
        assertEquals("已授权 command 源应成功安装：$output", 0, pluginCommand(manager, listOf("install", "generated@commands", "--yes"), "".reader().buffered(), java.io.PrintStream(output)))
        put(root.resolve("managed.json"), """{"disableCommandPluginSources":true}""")
        assertEquals(1, pluginCommand(manager, listOf("update", "generated@commands", "--yes"), "".reader().buffered(), java.io.PrintStream(output)))
    }
}
