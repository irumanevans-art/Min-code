package dev.min.agent.context

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage

/**
 * 模型不收图片时，请求里的图片换成一行文字（2026-10-06 拍板：集中在组请求这一处做）。
 * 产出图片的地方（MCP 结果、截图、读图片文件、用户附件）都不各自判断模型能力；日志里照存原图，
 * 换成能看图的模型后同一段日志组出的请求自然带图。
 * 文字只说「这里有图、模型看不到」，不猜内容：让模型知道信息缺了一块，需要时换个办法拿。
 */
internal object ImageDegrade {

    fun strip(messages: List<ModelMessage>): List<ModelMessage> =
        messages.map { message -> if (message.parts.none(::carriesImage)) message else message.copy(parts = message.parts.map(::stripPart)) }

    private fun carriesImage(part: Part): Boolean =
        part is ImagePart || (part is ToolResultPart && part.content.any { it is ImagePart })

    private fun stripPart(part: Part): Part = when (part) {
        is ImagePart -> placeholder(part)
        is ToolResultPart -> part.copy(content = part.content.map { if (it is ImagePart) placeholder(it) else it })
        else -> part
    }

    private fun placeholder(image: ImagePart) = TextPart("[图片（${image.mediaType}）：当前模型不支持看图，这张图没有发给模型]")
}
