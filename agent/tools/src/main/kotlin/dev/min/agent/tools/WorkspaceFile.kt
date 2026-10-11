package dev.min.agent.tools

import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ToolContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * 把工作区文件交给用户（SendUserFile）或交给预览位（Artifact）前的共同检查。
 * 两个工具都只能碰会话的工作区：这条规矩放在一处，两边不会各写一份、慢慢走样。
 */

/**
 * 这个工作区文件能不能交出去：不在工作区、不存在、是目录、超过 [maxBytes] 分别给一句原因；能交为 null。
 * 只查不读，调用方先把所有路径查完再读盘，整批不合格就整批不发。
 */
internal suspend fun workspaceFileProblem(path: GuestPath, ctx: ToolContext, maxBytes: Int, verb: String): String? {
    // 按实际指向判：先折叠 `..`，再让宿主解析链接（ctx.canonicalPath）。只比字符串前缀的话，`/work/../etc/x`
    // 与工作区里指向外面（凭证、/sdcard）的链接都能混过去，而读盘时文件系统照样会跟过去
    val cwd = actualPath(ctx.cwd, ctx).trimEnd('/')
    val actual = actualPath(path, ctx)
    if (actual != cwd && !actual.startsWith("$cwd/")) return "$path 不在工作区里（只能${verb}工作区的文件）。"
    val stat = ctx.fs.stat(path) ?: return "$path 不存在。"
    return when {
        stat.kind == FileKind.Directory -> "$path 是目录，不能直接${verb}（先打包成一个文件）。"
        stat.size > maxBytes -> "$path 超过 ${maxBytes / 1024 / 1024} MB，${verb}不了。"
        else -> null
    }
}

private fun actualPath(path: GuestPath, ctx: ToolContext): String =
    ctx.canonicalPath(GuestPath(dev.min.agent.policy.normalizePolicyPath(path.value))).value

/**
 * 交出去之前的权限声明：把要交的文件当作「读」报给权限判定。不声明的话 Read 的 deny 规则、
 * Min 的凭证保护与 `--restricted` 的工作目录线都看不到这次调用碰了哪些文件
 */
internal fun workspaceFileReads(paths: List<GuestPath>, ctx: ToolContext): List<dev.min.agent.spi.PathAccess> =
    paths.map { dev.min.agent.spi.PathAccess(it, dev.min.agent.spi.AccessKind.Read, ctx.fs.scopeOf(it)) }

/**
 * 严格按 UTF-8 解码；字节里有非法序列就返回 null，而不是默默替换成 U+FFFD。
 * 预览位显示的是模型写的源文件，替换字符会让页面静默地错，不如直接说这个文件不是 UTF-8。
 */
internal fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}
