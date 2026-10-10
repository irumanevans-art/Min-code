package dev.min.agent.host

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.*
import java.util.zip.*
import java.io.ByteArrayOutputStream

class FilePluginSourcesTest {
    private val noExec = object : Exec {
        override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("本测试不得执行进程")
        override suspend fun kill(handle: ExecHandle) = Unit
    }
    private suspend fun rejected(reason: String, block: suspend () -> Unit) {
        var error: Exception? = null
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        assertNotNull("应拒绝：$reason", error); assertTrue("对应边界：${error?.message}", error!!.message.orEmpty().contains(reason))
    }
    private fun zip(name: String, body: String, mode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(body.toByteArray()); it.closeEntry() }
        val bytes = out.toByteArray()
        if (mode != 0) {
            val central = (0..bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 0x01.toByte() && bytes[it + 3] == 0x02.toByte() }
            bytes[central + 5] = 3
            bytes[central + 40] = (mode and 255).toByte(); bytes[central + 41] = (mode ushr 8).toByte()
        }
        return bytes
    }
    private fun setup(block: suspend (FilePluginSources, Path) -> Unit) = runBlocking {
        Jimfs.newFileSystem(Configuration.unix()).use { files ->
            val root = Files.createDirectories(files.getPath("/rootfs")); Files.createDirectories(root.resolve("root/.claude/plugins"))
            block(FilePluginSources(RootfsFileSystem(root), noExec, GuestPath("/root/.claude/plugins")), root)
        }
    }
    @Test fun archiveTraversalIsRejectedBeforeWritingOutside() = setup { host, root ->
        Files.write(root.resolve("bad.zip"), zip("../escaped.txt", "secret"))
        rejected("路径穿越") { host.fetch(PluginFetchSource("archive", "/bad.zip"), GuestPath("/root/.claude/plugins/package"), PluginFetchLimits()) }
        assertFalse(Files.exists(root.resolve("root/.claude/plugins/escaped.txt")))
    }
    @Test fun archiveUnixSymlinkIsRejectedBeforeExtraction() = setup { host, root ->
        Files.write(root.resolve("bad.zip"), zip("escape-link", "/outside", 0xa1ff))
        rejected("符号链接") { host.fetch(PluginFetchSource("archive", "/bad.zip"), GuestPath("/root/.claude/plugins/package"), PluginFetchLimits()) }
        assertFalse(Files.exists(root.resolve("root/.claude/plugins/package/escape-link")))
    }
    @Test fun expandedSizeLimitBlocksCompressedBomb() = setup { host, root ->
        Files.write(root.resolve("large.zip"), zip("large", "x".repeat(32768)))
        rejected("展开超") { host.fetch(PluginFetchSource("archive", "/large.zip"), GuestPath("/root/.claude/plugins/package"), PluginFetchLimits(expandedBytes = 128)) }
    }
    @Test fun treeCopyRejectsSymlinksAndCleanupCannotEscapeOwnedRoot() = setup { host, root ->
        val plugin = Files.createDirectories(root.resolve("plugin")); Files.writeString(root.resolve("secret"), "secret")
        Files.createSymbolicLink(plugin.resolve("link"), root.resolve("secret"))
        rejected("符号链接") { host.copy(GuestPath("/plugin"), GuestPath("/root/.claude/plugins/package"), PluginFetchLimits()) }
        rejected("超出安装目录") { host.remove(GuestPath("/secret")) }
        assertEquals("secret", Files.readString(root.resolve("secret")))
    }
    @Test fun hostRootAliasIsAcceptedButPluginTreeEscapeIsRejected(): Unit = runBlocking {
        Jimfs.newFileSystem(Configuration.unix()).use { files ->
            val physical = Files.createDirectories(files.getPath("/physical"))
            Files.createSymbolicLink(files.getPath("/alias"), physical)
            Files.createDirectories(physical.resolve("root/.claude/plugins"))
            val plugin = Files.createDirectories(physical.resolve("plugin"))
            Files.writeString(plugin.resolve("hello"), "safe")
            val host = FilePluginSources(RootfsFileSystem(files.getPath("/alias")), noExec, GuestPath("/root/.claude/plugins"))
            var aliasError: Exception? = null
            try { host.locked { host.copy(GuestPath("/plugin"), GuestPath("/root/.claude/plugins/package"), PluginFetchLimits()) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { aliasError = e }
            assertNull("宿主根别名不应被当成插件链接逃逸", aliasError)
            assertEquals("safe", Files.readString(physical.resolve("root/.claude/plugins/package/hello")))
            Files.createDirectories(physical.resolve("outside"))
            Files.createSymbolicLink(physical.resolve("root/.claude/plugins/escape"), physical.resolve("outside"))
            rejected("副作用超出安装目录") { host.remove(GuestPath("/root/.claude/plugins/escape")) }
            assertTrue(Files.isDirectory(physical.resolve("outside")))
        }
    }
    @Test fun validArchiveHonorsDigestAndCancellationIsNotSwallowed() = setup { host, root ->
        Files.write(root.resolve("good.zip"), zip("commands/test.md", "safe"))
        rejected("SHA256") { host.fetch(PluginFetchSource("archive", "/good.zip", sha256 = "0".repeat(64)), GuestPath("/root/.claude/plugins/bad"), PluginFetchLimits()) }
        host.fetch(PluginFetchSource("archive", "/good.zip"), GuestPath("/root/.claude/plugins/good"), PluginFetchLimits())
        assertEquals("safe", Files.readString(root.resolve("root/.claude/plugins/good/commands/test.md")))
        coroutineScope {
            val cancel = launch(start = CoroutineStart.LAZY) { host.copy(GuestPath("/plugin"), GuestPath("/root/.claude/plugins/cancelled"), PluginFetchLimits()) }
            cancel.cancel(); cancel.join(); assertFalse(Files.exists(root.resolve("root/.claude/plugins/cancelled")))
        }
    }
}
