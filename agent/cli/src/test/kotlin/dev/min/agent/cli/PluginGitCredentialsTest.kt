package dev.min.agent.cli

import dev.min.agent.ext.plugins.PluginMarketplace
import dev.min.agent.host.FilePluginSources
import dev.min.agent.spi.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.*

class PluginGitCredentialsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `私有 git 使用系统凭据助手且认证值不进入日志与索引`(): Unit = runBlocking {
        val root = tmp.root.toPath(); val repository = Files.createDirectories(root.resolve("repository"))
        fun git(cwd: Path, vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args).directory(cwd.toFile()).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText(); assertEquals(text, 0, p.waitFor())
        }
        git(repository, "init", "-q")
        Files.createDirectories(repository.resolve(".claude-plugin")); Files.writeString(repository.resolve(".claude-plugin/plugin.json"), """{"name":"git-demo"}""")
        git(repository, "add", "."); git(repository, "commit", "-qm", "fixture")
        val bare = root.resolve("bare.git"); git(root, "clone", "--bare", repository.toString(), bare.toString()); git(bare, "update-server-info")
        val secret = "SYSTEM_GIT_AUTH_DO_NOT_LOG_92047"
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("user:$secret".toByteArray())
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repo.git/") { e ->
            val auth = e.requestHeaders.getFirst("Authorization").orEmpty(); received += auth
            if (auth != expected) {
                e.responseHeaders.add("WWW-Authenticate", "Basic realm=\"local-test\""); e.sendResponseHeaders(401, -1)
            } else {
                val file = bare.resolve(e.requestURI.path.removePrefix("/repo.git/")).normalize()
                if (!file.startsWith(bare) || !Files.isRegularFile(file)) e.sendResponseHeaders(404, -1)
                else {
                    val bytes = Files.readAllBytes(file); e.responseHeaders.add("Content-Type", if (file.fileName.toString() == "refs") "text/plain" else "application/octet-stream")
                    e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
                }
            }
            e.close()
        }
        server.start()
        try {
            val config = root.resolve("system.gitconfig")
            Files.writeString(config, "[credential]\n\thelper = \"!f() { echo username=user; echo password=$secret; }; f\"\n")
            val home = Files.createDirectories(root.resolve("home")); val work = Files.createDirectories(root.resolve("work")); val paths = DesktopPaths(work, root.resolve("rootfs"))
            val logs = Files.createDirectories(root.resolve("logs")); val market = Files.createDirectories(root.resolve("market/.claude-plugin"))
            Files.writeString(market.resolve("marketplace.json"), """{"name":"git-auth","owner":{"name":"test"},"plugins":[{"name":"git-demo","source":{"source":"url","url":"http://127.0.0.1:${server.address.port}/repo.git"}}]}""")
            val manager = PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, logs, paths.guestOfHost(logs)), GuestPath(paths.guestOfHost(home).value + "/.claude/plugins"),
                desktopPluginProcess(paths) { System.getenv() + mapOf("GIT_CONFIG_SYSTEM" to config.toString(), "GIT_CONFIG_GLOBAL" to root.resolve("absent-global").toString(), "http_proxy" to "", "https_proxy" to "", "all_proxy" to "", "HTTP_PROXY" to "", "HTTPS_PROXY" to "", "ALL_PROXY" to "", "NO_PROXY" to "127.0.0.1,localhost", "no_proxy" to "127.0.0.1,localhost") },
                { paths.hostOf(it).toString() }, { paths.guestOfHost(Path.of(it)) }, paths.guestOfHost(work)), paths.guestOfHost(home), paths.guestOfHost(work), { true }, canonical = paths.fs::canonical,
                managedPath = paths.guestOfHost(root.resolve("absent-managed")), installationPath = { paths.hostOf(it).toString() })
            manager.add(buildJsonObject { put("source", "directory"); put("path", paths.guestOfHost(market.parent).value) }, true)
            val output = java.io.ByteArrayOutputStream()
            val code = pluginCommand(manager, listOf("install", "git-demo@git-auth", "--yes"), "".reader().buffered(), java.io.PrintStream(output))
            assertEquals(output.toString(), 0, code)
            assertTrue("真实 git 应取得系统 helper 的认证", received.contains(expected))
            assertFalse(output.toString().contains(secret) || output.toString().contains(expected)); assertTrue(Files.list(logs).use { it.findAny().isEmpty })
            Files.walk(home).use { files -> files.filter { Files.isRegularFile(it) }.forEach { assertFalse("索引及缓存不能存认证值", Files.readString(it).contains(secret)) } }
        } finally { server.stop(0) }
    }
}
