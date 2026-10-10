package dev.min.agent.context

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage

/**
 * 模型不收图片或 PDF 时，请求里的这些附件换成一行文字（2026-10-06 拍板：集中在组请求这一处做）。
 * 产出附件的地方（MCP 结果、截图、读图片或 PDF 文件、用户附件）都不各自判断模型能力；日志里照存原件，
 * 换成能看的模型后同一段日志组出的请求自然带上。
 * 文字只说「这里有图（文档）、模型看不到」，不猜内容：让模型知道信息缺了一块，需要时换个办法拿。
 * 图片与文档分开判：视觉模型不一定收 PDF（目录里的 supportsPdf 单独标）。
 */
internal object ImageDegrade {

    /** 不收的那一类换成文字；收的那一类原样保留 */
    fun strip(messages: List<ModelMessage>, images: Boolean, documents: Boolean): List<ModelMessage> {
        if (images && documents) return messages
        val drop: (Part) -> Boolean = { part -> (!images && part is ImagePart) || (!documents && part is DocumentPart) }
        return messages.map { message ->
            if (message.parts.none { carries(it, drop) }) message else message.copy(parts = message.parts.map { stripPart(it, drop) })
        }
    }

    private fun carries(part: Part, drop: (Part) -> Boolean): Boolean =
        drop(part) || (part is ToolResultPart && part.content.any(drop))

    private fun stripPart(part: Part, drop: (Part) -> Boolean): Part = when {
        part is ToolResultPart -> part.copy(content = part.content.map { if (drop(it)) placeholder(it) else it })
        drop(part) -> placeholder(part)
        else -> part
    }

    private fun placeholder(part: Part): Part = when (part) {
        is ImagePart -> TextPart("[图片（${part.mediaType}）：当前模型不支持看图，这张图没有发给模型]")
        is DocumentPart -> TextPart("[文档（${part.mediaType}）：当前模型不支持读 PDF，这份文档没有发给模型]")
        else -> part
    }
}
