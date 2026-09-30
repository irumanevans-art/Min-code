package dev.min.agent.host

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** 布局：rootfs 在 /data/rootfs，/workspace 挂到 /data/files；搜索根都在 /workspace 下 */
class JvmSearchTest {
    private val jfs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build())
    private val rootfs: Path = Files.createDirectories(jfs.getPath("/data/rootfs"))
    private val files: Path = Files.createDirectories(jfs.getPath("/data/files"))
    private val outside: Path = Files.createDirectories(jfs.getPath("/data/outside"))
    private val search = JvmSearch(RootfsFileSystem(rootfs, binds = listOf(BindMount(GuestPath("/workspace"), files))))
    private val root = GuestPath("/workspace/proj")
    private val proj: Path = Files.createDirectories(files.resolve("proj"))

    private fun write(rel: String, text: String, base: Path = proj) {
        val p = base.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
    }

    private fun grep(pattern: String, block: (GrepQueryBuilder) -> Unit = {}): List<GrepHit> = runBlocking {
        val b = GrepQueryBuilder(pattern)
        block(b)
        search.grep(b.build()).toList()
    }

    private class GrepQueryBuilder(val pattern: String) {
        var root = GuestPath("/workspace/proj")
        var include: String? = null
        var ignoreCase = false
        var literal = false
        var maxHits = 500
        var context = 0
        var multiline = false
        fun build() = GrepQuery(pattern, root, include, ignoreCase, literal, maxHits, context, multiline)
    }

    private fun glob(pattern: String, max: Int = 1_000): List<String> = runBlocking {
        search.glob(GlobQuery(pattern, root, max)).toList().map { it.value }
    }

    private fun GrepHit.brief() = "${path.value.removePrefix("/workspace/proj")}:$line:${if (isContext) "c" else "h"}:$text"

    @Test
    fun `grep 结果是 guest 路径，按路径与行号排序`() {
        write("b.txt", "x\nfoo 2\n")
        write("a/z.txt", "foo 1\nbar\nfoo 3\n")
        write("a/y.txt", "foo 0\n")
        assertEquals(
            listOf("/a/y.txt:1:h:foo 0", "/a/z.txt:1:h:foo 1", "/a/z.txt:3:h:foo 3", "/b.txt:2:h:foo 2"),
            grep("foo").map { it.brief() },
        )
        assertEquals("/workspace/proj/b.txt", grep("foo").last().path.value)
    }

    @Test
    fun `搜索根是单个文件时只搜它并返回它自己的路径`() {
        write("a.txt", "foo\n")
        write("b.txt", "foo\n")
        val hits = grep("foo") { it.root = GuestPath("/workspace/proj/a.txt") }
        assertEquals(listOf("/workspace/proj/a.txt"), hits.map { it.path.value })
    }

    @Test
    fun `跳过 git 与 node_modules 目录`() {
        write(".git/config", "foo\n")
        write("node_modules/x/index.js", "foo\n")
        write("src/node_modules_not/a.txt", "foo\n")
        write("src/a.txt", "foo\n")
        assertEquals(listOf("/src/a.txt", "/src/node_modules_not/a.txt"), grep("foo").map { it.path.value.removePrefix("/workspace/proj") })
        assertEquals(listOf("/workspace/proj/src/a.txt", "/workspace/proj/src/node_modules_not/a.txt"), glob("**/*.txt"))
    }

    @Test
    fun `符号链接不被跟随，文件链接与目录链接都跳过`() {
        write("secret.txt", "foo\n", outside)
        write("real.txt", "foo\n")
        Files.createSymbolicLink(proj.resolve("dir-link"), outside)
        Files.createSymbolicLink(proj.resolve("file-link.txt"), outside.resolve("secret.txt"))
        assertEquals(listOf("/workspace/proj/real.txt"), grep("foo").map { it.path.value })
        assertEquals(listOf("/workspace/proj/real.txt"), glob("**/*"))
    }

    @Test
    fun `二进制文件被跳过，只看前 8KB`() {
        Files.write(proj.resolve("bin.dat"), byteArrayOf('f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0, 1))
        // NUL 在 8KB 之后：按约定当文本
        Files.write(proj.resolve("late.txt"), ByteArray(9000) { 'a'.code.toByte() } + "\nfoo\n".toByteArray() + byteArrayOf(0))
        write("text.txt", "foo\n")
        assertEquals(listOf("/late.txt", "/text.txt"), grep("foo").map { it.path.value.removePrefix("/workspace/proj") })
    }

    @Test
    fun `include 相对搜索根匹配，无斜杠的模式按文件名任意深度`() {
        write("a.kt", "foo\n")
        write("src/b.kt", "foo\n")
        write("src/c.java", "foo\n")
        write("src/deep/d.kt", "foo\n")
        fun paths(include: String) = grep("foo") { it.include = include }.map { it.path.value.removePrefix("/workspace/proj/") }
        assertEquals(listOf("a.kt", "src/b.kt", "src/deep/d.kt"), paths("*.kt"))
        assertEquals(listOf("a.kt", "src/b.kt", "src/deep/d.kt"), paths("**/*.kt"))
        assertEquals(listOf("src/b.kt", "src/c.java"), paths("src/*.{kt,java}"))
    }

    @Test
    fun `literal 把正则元字符当普通字符`() {
        write("a.txt", "a.b\naxb\n")
        assertEquals(listOf(1, 2), grep("a.b").map { it.line })
        assertEquals(listOf(1), grep("a.b") { it.literal = true }.map { it.line })
    }

    @Test
    fun `ignoreCase`() {
        write("a.txt", "Hello\nhello\nHELLO\n")
        assertEquals(1, grep("hello").size)
        assertEquals(3, grep("hello") { it.ignoreCase = true }.size)
    }

    @Test
    fun `上下文行前后各 N 行，同一行不重复，不计入 maxHits`() {
        write("a.txt", (1..10).joinToString("\n") { if (it == 3 || it == 5 || it == 9) "hit$it" else "l$it" } + "\n")
        assertEquals(
            listOf(
                "/a.txt:2:c:l2", "/a.txt:3:h:hit3", "/a.txt:4:c:l4", "/a.txt:5:h:hit5", "/a.txt:6:c:l6",
                "/a.txt:8:c:l8", "/a.txt:9:h:hit9", "/a.txt:10:c:l10",
            ),
            grep("hit") { it.context = 1 }.map { it.brief() },
        )
        // 文件首行命中：前面没有上下文，不越界
        write("b.txt", "hit\nx\n")
        assertEquals(listOf("/b.txt:1:h:hit", "/b.txt:2:c:x"), grep("hit") { it.context = 3; it.include = "b.txt" }.map { it.brief() })
        // maxHits = 1 只算命中，第一个命中的上下文仍给
        val limited = grep("hit") { it.context = 1; it.maxHits = 1; it.include = "a.txt" }
        assertEquals(1, limited.count { !it.isContext })
        assertEquals(listOf(2, 3, 4), limited.map { it.line })
    }

    @Test
    fun `multiline 在整个文件上匹配，行号取匹配起点，文本含换行`() {
        write("a.txt", "one\nfun a(\n    x: Int,\n)\ntail\n")
        val hits = grep("""fun a\(.*?\)""") { it.multiline = true }
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].line)
        assertEquals("fun a(\n    x: Int,\n)", hits[0].text)
        // 不开 multiline 时不跨行
        assertTrue(grep("""fun a\(.*?\)""").isEmpty())
    }

    @Test
    fun `multiline 的上下文行不与命中占用的行重复`() {
        write("a.txt", "1\n2\nstart\nmid\nend\n6\n7\n")
        val hits = grep("start.*end") { it.multiline = true; it.context = 2 }
        assertEquals(listOf("/a.txt:1:c:1", "/a.txt:2:c:2", "/a.txt:3:h:start\nmid\nend", "/a.txt:6:c:6", "/a.txt:7:c:7"), hits.map { it.brief() })
    }

    @Test
    fun `maxHits 截断，跨文件也生效`() {
        write("a.txt", "foo\nfoo\nfoo\n")
        write("b.txt", "foo\nfoo\n")
        assertEquals(listOf("/a.txt:1", "/a.txt:2"), grep("foo") { it.maxHits = 2 }.map { "${it.path.value.removePrefix("/workspace/proj")}:${it.line}" })
        assertEquals(4, grep("foo") { it.maxHits = 4 }.size)
        assertEquals(5, grep("foo") { it.maxHits = 100 }.size)
    }

    @Test
    fun `CRLF 行尾不进文本`() {
        write("a.txt", "foo\r\nbar\r\n")
        assertEquals("foo", grep("foo").single().text)
    }

    @Test
    fun `搜索根是符号链接时先按 guest 规则解析`() {
        write("a.txt", "foo\n")
        Files.createSymbolicLink(files.resolve("alias"), proj)
        val hits = grep("foo") { it.root = GuestPath("/workspace/alias") }
        assertEquals(listOf("/workspace/proj/a.txt"), hits.map { it.path.value })
    }

    @Test
    fun `非法正则抛异常`() {
        assertThrows(java.util.regex.PatternSyntaxException::class.java) { grep("(") }
    }

    @Test
    fun `glob 只列文件，相对搜索根匹配，按路径排序并截断`() {
        write("b.kt", "")
        write("a.kt", "")
        write("src/x.kt", "")
        write("src/x.java", "")
        Files.createDirectories(proj.resolve("emptydir.kt"))
        assertEquals(
            listOf("/workspace/proj/a.kt", "/workspace/proj/b.kt", "/workspace/proj/src/x.kt"),
            glob("**/*.kt"),
        )
        assertEquals(listOf("/workspace/proj/a.kt", "/workspace/proj/b.kt"), glob("*.kt"))
        assertEquals(listOf("/workspace/proj/src/x.java", "/workspace/proj/src/x.kt"), glob("src/*"))
        assertEquals(listOf("/workspace/proj/a.kt", "/workspace/proj/b.kt"), glob("**/*.kt", max = 2))
    }

    @Test
    fun `flow 可取消，取第一条后不再遍历出错`() {
        write("a.txt", "foo\n")
        write("b.txt", "foo\n")
        val first = runBlocking { search.grep(GrepQuery("foo", root)).first() }
        assertEquals("/workspace/proj/a.txt", first.path.value)
    }
}
