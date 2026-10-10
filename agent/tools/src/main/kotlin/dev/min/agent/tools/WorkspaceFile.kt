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
    val cwd = ctx.cwd.value.trimEnd('/')
    if (path.value != cwd && !path.value.startsWith("$cwd/")) return "$path 不在工作区里（只能${verb}工作区的文件）。"
    val stat = ctx.fs.stat(path) ?: return "$path 不存在。"
    return when {
        stat.kind == FileKind.Directory -> "$path 是目录，不能直接${verb}（先打包成一个文件）。"
        stat.size > maxBytes -> "$path 超过 ${maxBytes / 1024 / 1024} MB，${verb}不了。"
        else -> null
    }
}

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
