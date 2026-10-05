package dev.min.agent.kernel

import dev.min.agent.mcp.McpCallResult
import dev.min.agent.mcp.McpContent
import dev.min.agent.mcp.McpHttpException
import dev.min.agent.mcp.McpRpcException
import dev.min.agent.mcp.McpTimeoutException
import dev.min.agent.mcp.McpTransportClosed
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.ToolResult
import java.util.Base64

/*
 * MCP 工具结果 → 内核的工具结果（MCP设计 §7）。结果是外部服务器写的：包在 <mcp-result server="…"> 里，
 * 正文里出现的同名标签转义（同子 agent 报告的做法），模型分得清哪些话是服务器说的。
 */
internal object McpResults {
    private const val TAG = "mcp-result"

    /** 内联给模型的图片上限（base64 之前）；更大的只存附件、给路径 */
    const val MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024

    suspend fun toToolResult(server: String, tool: String, callId: String, result: McpCallResult, artifacts: ArtifactSink): ToolResult {
        val parts = ArrayList<Part>()
        val text = StringBuilder()
        var attachment = 0
        suspend fun store(kind: String, ext: String, bytes: ByteArray): String =
            artifacts.put("mcp/$callId-${++attachment}-$kind.$ext", bytes)

        for (content in result.content) {
            when (content) {
                is McpContent.Text -> text.appendBlock(content.text)
                is McpContent.Image -> {
                    val bytes = decode(content.data)
                    if (bytes == null) {
                        text.appendBlock("[图片（${content.mimeType}）的数据不是合法的 base64，已丢弃]")
                    } else {
                        // 原图同时落附件：模型以后要引用、用户要另存都靠这个路径（CC 2.1.283 起同样落盘）
                        val path = store("image", extensionOf(content.mimeType), bytes)
                        if (bytes.size <= MAX_INLINE_IMAGE_BYTES) {
                            parts += ImagePart(content.mimeType, ImageSource.Base64(content.data))
                            text.appendBlock("[图片，原图在 $path]")
                        } else {
                            text.appendBlock("[图片太大（${bytes.size} 字节），没有直接给你看，原图在 $path]")
                        }
                    }
                }
                is McpContent.Audio -> {
                    val bytes = decode(content.data)
                    text.appendBlock(
                        if (bytes == null) "[音频（${content.mimeType}）的数据不是合法的 base64，已丢弃]"
                        else "[音频（${content.mimeType}），已存到 ${store("audio", extensionOf(content.mimeType), bytes)}]",
                    )
                }
                is McpContent.ResourceLink -> text.appendBlock("资源：${content.title ?: content.name}（${content.uri}）" +
                    (content.description?.let { "：$it" } ?: ""))
                is McpContent.EmbeddedResource -> {
                    val body = content.text
                    val blob = content.blob?.let(::decode)
                    when {
                        body != null -> text.appendBlock("资源 ${content.uri}：\n$body")
                        blob != null -> text.appendBlock("[资源 ${content.uri}（${content.mimeType ?: "二进制"}），已存到 ${store("resource", "bin", blob)}]")
                        else -> text.appendBlock("[资源 ${content.uri}，没有内容]")
                    }
                }
                is McpContent.Unknown -> text.appendBlock("[服务器返回了一种不认识的内容，已略过]")
            }
        }
        // 结构化结果：content 里没有等价的文字时才序列化给模型（规范：有 structuredContent 的工具 SHOULD 同时给文本）
        val structured = result.structuredContent
        if (structured != null && text.isBlank()) text.appendBlock(structured.toString())
        if (text.isEmpty() && parts.isEmpty()) text.append("（工具没有返回内容）")

        val wrapped = "<$TAG server=\"${attr(server)}\" tool=\"${attr(tool)}\">\n${escape(text.toString().trimEnd())}\n</$TAG>"
        return ToolResult(listOf(TextPart(wrapped)) + parts, isError = result.isError)
    }

    /** 调用失败（不是工具自己说的 isError）给模型的话：说清是哪种失败、能不能重试 */
    fun failure(server: String, e: Exception): ToolResult {
        val text = when (e) {
            is McpRpcException -> "MCP 服务器 $server 拒绝了这次调用（错误码 ${e.code}）：${e.message}"
            is McpTimeoutException -> when (e.kind) {
                McpTimeoutException.Kind.Idle -> "MCP 服务器 $server 太久没有回应也没有报进度，这次调用已取消。结果未知：有副作用的操作先确认状态，不要盲目重试。"
                McpTimeoutException.Kind.Hard -> "这次调用超过了 MCP 服务器 $server 配置的时限，已取消。结果未知：有副作用的操作先确认状态，不要盲目重试。"
            }
            is McpHttpException -> if (e.needsAuth) "MCP 服务器 $server 需要登录（HTTP ${e.status}）。请用户在 MCP 服务器面板里处理。"
                else "MCP 服务器 $server 返回了 HTTP ${e.status}：${e.message}"
            is McpTransportClosed -> "和 MCP 服务器 $server 的连接断了（${e.reason}）。这次调用的结果未知：不要盲目重试有副作用的操作。"
            else -> "调用 MCP 服务器 $server 出错：${e.message ?: e::class.simpleName}"
        }
        return ToolResult(listOf(TextPart(text)), isError = true)
    }

    private fun decode(data: String): ByteArray? = dev.min.agent.spi.catchingNonCancellation { Base64.getDecoder().decode(data) }.getOrNull()

    private fun extensionOf(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "bin"
    }

    private fun StringBuilder.appendBlock(block: String) {
        if (isNotEmpty()) append("\n\n")
        append(block)
    }

    private fun attr(text: String) = text.replace("\"", "'").replace("<", "‹").replace(">", "›")

    /** 只转义本系统的标签，其余原样：服务器返回的代码、HTML 不该被改写 */
    fun escape(text: String): String = text.replace("<$TAG", "&lt;$TAG").replace("</$TAG", "&lt;/$TAG")
}
