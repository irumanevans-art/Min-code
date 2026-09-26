package dev.min.code.core.rootfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DocumentsProvider 写入侧守卫的规则钉子。
 *
 * ⚠️ 诚实声明：`WorkspaceDocumentsProvider.requireDisplayName` 与根文档的
 * delete/rename 硬闸是 provider 的私有逻辑，依赖 Android framework（DocumentsProvider
 * 无法在 JVM 单测中实例化）。按任务约定**未改生产代码**，这里把规则复制成本地函数钉住——
 * **生产代码的校验没有被本测试直接执行**。改动生产规则时必须同步更新这里。
 *
 * 为什么这条规则是安全边界：documentId 的读取侧有 [resolveWithinRoot] 的 canonicalPath
 * 围栏，但 create/rename 的 `displayName` 来自**外部 App**——含 `/` 或名为 `..` 时拼出的
 * 路径会越出授权根，写入 App 私有目录（settings.json、数据库等）。
 */
class WorkspaceDocumentsNameRulesTest {

    /** 与 WorkspaceDocumentsProvider.requireDisplayName 同规则（复制，非引用） */
    private fun isValidDisplayName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\u0000')

    /** 与 deleteDocument/renameDocument 同规则：split() 已 trim('/')，relative 为空即根 */
    private fun canDeleteOrRename(relativePath: String): Boolean = relativePath.trim('/').isNotEmpty()

    @Test
    fun `合法名字放行`() {
        listOf(
            "a.kt",
            "名字.txt",
            "a..b.txt", // 名字中间的点不是上级目录
            ".hidden", // 以点开头的隐藏文件合法
            " spaced name ",
        ).forEach { assertTrue(it, isValidDisplayName(it)) }
    }

    @Test
    fun `路径段与控制字符被拒`() {
        listOf(
            "", // 空串
            ".", // 当前目录
            "..", // 上级目录
            "a/b", // 含路径分隔符
            "/abs", // 绝对路径：File(parent, "/abs") 会整个替换掉 parent
            "../escape",
            "x\u0000y", // NUL：C 层字符串终止符
        ).forEach { assertFalse("「$it」应被拒绝", isValidDisplayName(it)) }
    }

    @Test
    fun `根文档拒绝删除与重命名，普通文档不受影响`() {
        assertFalse(canDeleteOrRename("")) // "workspace:" / "rootfs:" 根
        assertFalse(canDeleteOrRename("/")) // split() 已 trim('/')，规整后等价于根
        assertTrue(canDeleteOrRename("a.kt"))
        assertTrue(canDeleteOrRename("sub/dir/file.md"))
    }
}
