package dev.min.code.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * DocumentsProvider 的路径逃逸检查。
 *
 * 这是整个 provider 里唯一一处"错了就是安全漏洞"的逻辑：documentId 由**别的 App**
 * 构造，注册成 exported 之后就不再是自己跟自己说话了。系统的 DocumentsUI 不会递恶意
 * 路径进来，但能拿到 URI 授权的第三方 App 会。
 */
class WorkspaceDocumentsPathTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var base: File

    private fun setUpBase(): File {
        base = tempFolder.newFolder("rootfs")
        File(base, "workspace").mkdirs()
        File(base, "workspace/a.kt").writeText("fun main() {}")
        return base
    }

    @Test
    fun `根自己解得出来`() {
        val base = setUpBase()
        assertEquals(base.canonicalFile, resolveWithinRoot(base, "")?.canonicalFile)
    }

    @Test
    fun `普通子路径解得出来`() {
        val base = setUpBase()
        assertEquals(
            File(base, "workspace/a.kt").canonicalFile,
            resolveWithinRoot(base, "workspace/a.kt")?.canonicalFile,
        )
    }

    @Test
    fun `不存在的路径给 null`() {
        val base = setUpBase()
        assertNull(resolveWithinRoot(base, "workspace/nope.kt"))
    }

    /** 最直接的那种攻法 */
    @Test
    fun `点点逃出根外时给 null`() {
        val base = setUpBase()
        tempFolder.newFile("outside.txt").writeText("secret")
        assertNull(resolveWithinRoot(base, "../outside.txt"))
        assertNull(resolveWithinRoot(base, "workspace/../../outside.txt"))
    }

    /**
     * 字符串前缀比会漏掉这个：链接本身在根里面，指向的东西在外面。
     * rootfs 里符号链接遍地都是，所以必须按 canonicalPath 判。
     */
    @Test
    fun `符号链接指到根外时给 null`() {
        val base = setUpBase()
        val outside = tempFolder.newFile("outside-linked.txt")
        outside.writeText("secret")
        val link = File(base, "escape")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        // Windows 上建符号链接要特权，建不起来就跳过——这条断言在 CI 的 Linux 上才有意义
        org.junit.Assume.assumeTrue(created)
        assertNull(resolveWithinRoot(base, "escape"))
    }

    /**
     * 同名更长的兄弟目录不该被当成根的内部。`/rootfs-old` 不在 `/rootfs` 里，
     * 和 bind mount 那边 `/skills` 不吃 `/skillsets` 是同一类错。
     */
    @Test
    fun `同名更长的兄弟目录不算根内`() {
        val base = setUpBase()
        val sibling = File(base.parentFile, "${base.name}-old")
        sibling.mkdirs()
        File(sibling, "x.txt").writeText("x")
        assertNull(resolveWithinRoot(base, "../${base.name}-old/x.txt"))
    }
}
