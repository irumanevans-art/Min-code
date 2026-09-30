package dev.min.agent.host

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * 路径映射是「文件工具在宿主侧直接读写 rootfs」的地基，也是安全边界：每个用例对应一种真实布局或一种越界手法。
 * 布局照 Min 的真实目录：rootfs 在 `<ws>/linux`，`/workspace` 挂到 `<ws>/files`，`/sdcard` 挂到手机共享存储。
 */
class RootfsFileSystemTest {
    private val fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build())
    private val ws = fs.getPath("/data/app/ws")
    private val rootfs: Path = Files.createDirectories(ws.resolve("linux"))
    private val files: Path = Files.createDirectories(ws.resolve("files"))
    private val storage: Path = Files.createDirectories(fs.getPath("/storage/emulated/0"))
    private val secret: Path = Files.createDirectories(fs.getPath("/data/app/private")).also {
        Files.write(it.resolve("token"), "sk-real".toByteArray())
    }

    private val mapper = RootfsFileSystem(
        rootfs,
        binds = listOf(
            BindMount(GuestPath("/workspace"), files),
            BindMount(GuestPath("/sdcard"), storage),
        ),
    )

    private fun g(p: String) = GuestPath(p)
    private fun mkdirs(p: Path): Path = Files.createDirectories(p)
    private fun write(p: Path, text: String): Path = p.also { mkdirs(it.parent); Files.write(it, text.toByteArray()) }

    @Test
    fun `plain paths map into the rootfs and binds map to their host directories`() {
        assertEquals(rootfs.resolve("etc/hosts"), mapper.hostPath(g("/etc/hosts")))
        assertEquals(files.resolve("app/Main.kt"), mapper.hostPath(g("/workspace/app/Main.kt")))
        assertEquals(files, mapper.hostPath(g("/workspace")))
        assertEquals(storage.resolve("DCIM/a.jpg"), mapper.hostPath(g("/sdcard/DCIM/a.jpg")))
        // 前缀要按分量匹配：/workspace2 不属于 /workspace 这个挂载
        assertEquals(rootfs.resolve("workspace2/x"), mapper.hostPath(g("/workspace2/x")))
    }

    @Test
    fun `absolute symlinks inside the rootfs resolve against the guest root, not the host`() {
        mkdirs(rootfs.resolve("usr/lib"))
        Files.createSymbolicLink(rootfs.resolve("lib"), fs.getPath("/usr/lib"))
        assertEquals(rootfs.resolve("usr/lib/libc.so"), mapper.hostPath(g("/lib/libc.so")))
        assertEquals(g("/usr/lib/libc.so"), mapper.canonical(g("/lib/libc.so")))
    }

    @Test
    fun `relative symlinks resolve next to the link`() {
        mkdirs(rootfs.resolve("usr/bin"))
        Files.createSymbolicLink(rootfs.resolve("usr/bin/python3"), fs.getPath("python3.12"))
        assertEquals(g("/usr/bin/python3.12"), mapper.canonical(g("/usr/bin/python3")))
    }

    @Test
    fun `dot-dot never climbs above the guest root`() {
        assertEquals(rootfs.resolve("etc/passwd"), mapper.hostPath(g("/../../../etc/passwd")))
        assertEquals(rootfs.resolve("etc/passwd"), mapper.hostPath(g("/workspace/../../../../etc/passwd")))
    }

    @Test
    fun `a symlink that tries to climb out stays inside the rootfs`() {
        mkdirs(rootfs.resolve("home"))
        Files.createSymbolicLink(rootfs.resolve("home/up"), fs.getPath("../../../../../../data/app/private/token"))
        assertEquals(rootfs.resolve("data/app/private/token"), mapper.hostPath(g("/home/up")))
    }

    @Test
    fun `a symlink to a host path outside the rootfs is read as a guest path`() = runBlocking {
        // guest 里写一个指向宿主私有目录的绝对链接：必须被当成 guest 的 /data/app/private/token，读不到真的凭证
        Files.createSymbolicLink(rootfs.resolve("steal"), secret.resolve("token"))
        assertEquals(rootfs.resolve("data/app/private/token"), mapper.hostPath(g("/steal")))
        assertNull(mapper.stat(g("/steal")))
    }

    @Test
    fun `link2symlink targets written as host paths are translated back into the rootfs`() = runBlocking {
        // proot --link2symlink 模拟硬链接：链接目标写的是宿主上 rootfs 内的绝对路径
        val real = write(rootfs.resolve("usr/lib/.l2s.libfoo.so0001"), "ELF")
        Files.createSymbolicLink(rootfs.resolve("usr/lib/libfoo.so"), real)
        assertEquals(g("/usr/lib/.l2s.libfoo.so0001"), mapper.canonical(g("/usr/lib/libfoo.so")))
        assertArrayEquals("ELF".toByteArray(), mapper.read(g("/usr/lib/libfoo.so")))
    }

    @Test
    fun `a host path inside a bind is translated to the bind's guest path`() {
        write(storage.resolve("DCIM/a.jpg"), "jpg")
        Files.createSymbolicLink(rootfs.resolve("photo"), storage.resolve("DCIM/a.jpg"))
        assertEquals(g("/sdcard/DCIM/a.jpg"), mapper.canonical(g("/photo")))
    }

    @Test
    fun `symlink loops are reported instead of spinning`() {
        Files.createSymbolicLink(rootfs.resolve("a"), fs.getPath("/b"))
        Files.createSymbolicLink(rootfs.resolve("b"), fs.getPath("/a"))
        assertThrows(GuestPathException::class.java) { mapper.canonical(g("/a/x")) }
    }

    @Test
    fun `a bind mount point that is itself a host symlink is followed by the host, not reinterpreted`() = runBlocking {
        // Android 的 /sdcard 在宿主上常常本身就是链接（指向 /storage/emulated/0）
        val linkedBind = fs.getPath("/sdcard-link")
        Files.createSymbolicLink(linkedBind, storage)
        write(storage.resolve("Download/n.txt"), "note")
        val m = RootfsFileSystem(rootfs, listOf(BindMount(GuestPath("/sdcard"), linkedBind)))
        assertEquals(linkedBind.resolve("Download/n.txt"), m.hostPath(g("/sdcard/Download/n.txt")))
        assertArrayEquals("note".toByteArray(), m.read(g("/sdcard/Download/n.txt")))
    }

    @Test
    fun `scope is judged on the resolved path, so a workspace link into sdcard counts as device`() {
        mkdirs(files)
        Files.createSymbolicLink(files.resolve("photos"), fs.getPath("/sdcard/DCIM"))
        assertEquals(PathScope.Workspace, mapper.scopeOf(g("/workspace/src/a.kt")))
        assertEquals(PathScope.Rootfs, mapper.scopeOf(g("/etc/hosts")))
        assertEquals(PathScope.Device, mapper.scopeOf(g("/sdcard/DCIM/a.jpg")))
        assertEquals(PathScope.Device, mapper.scopeOf(g("/workspace/photos/a.jpg")))
    }

    @Test
    fun `atomic writes create parents, replace content and keep permission bits`() = runBlocking {
        val script = write(files.resolve("run.sh"), "echo old")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"))

        mapper.writeAtomic(g("/workspace/run.sh"), "echo new".toByteArray())
        assertEquals("echo new", String(Files.readAllBytes(script)))
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(script))

        mapper.writeAtomic(g("/workspace/deep/new/file.txt"), "hi".toByteArray())
        assertEquals("hi", String(Files.readAllBytes(files.resolve("deep/new/file.txt"))))
        Files.list(files).use { entries -> assertFalse("不留临时文件", entries.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    @Test
    fun `writing through a symlink writes the target inside the rootfs`() = runBlocking {
        mkdirs(rootfs.resolve("etc"))
        Files.createSymbolicLink(rootfs.resolve("etc/app.conf"), fs.getPath("/opt/app/app.conf"))
        mapper.writeAtomic(g("/etc/app.conf"), "k=v".toByteArray())
        assertEquals("k=v", String(Files.readAllBytes(rootfs.resolve("opt/app/app.conf"))))
    }

    @Test
    fun `read-only binds refuse writes`() = runBlocking {
        val m = RootfsFileSystem(rootfs, listOf(BindMount(GuestPath("/sdcard"), storage, readOnly = true)))
        val e = assertThrows(GuestPathException::class.java) {
            runBlocking { m.writeAtomic(g("/sdcard/x.txt"), "x".toByteArray()) }
        }
        assertTrue(e.message!!.contains("只读"))
        assertFalse(Files.exists(storage.resolve("x.txt")))
    }

    @Test
    fun `stat, ranged read and listing`() = runBlocking {
        write(files.resolve("a.txt"), "0123456789")
        mkdirs(files.resolve("dir"))
        Files.createSymbolicLink(files.resolve("link"), fs.getPath("a.txt"))

        val stat = mapper.stat(g("/workspace/a.txt"))!!
        assertEquals(FileKind.File, stat.kind)
        assertEquals(10L, stat.size)
        assertEquals(FileKind.File, mapper.stat(g("/workspace/link"))!!.kind) // stat 跟随链接
        assertNull(mapper.stat(g("/workspace/missing")))
        assertArrayEquals("3456".toByteArray(), mapper.read(g("/workspace/a.txt"), offset = 3, limit = 4))
        assertArrayEquals(ByteArray(0), mapper.read(g("/workspace/a.txt"), offset = 20, limit = 4))

        val listing = mapper.list(g("/workspace")).associate { it.name to it.kind }
        assertEquals(mapOf("a.txt" to FileKind.File, "dir" to FileKind.Directory, "link" to FileKind.Symlink), listing)
    }
}
