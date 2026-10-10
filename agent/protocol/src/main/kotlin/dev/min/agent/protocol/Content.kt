package dev.min.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 哪家的哪个模型。thinking 的签名只对产生它的模型有效，所以 thinking 块要记住出身 */
@Serializable
data class ModelRef(val providerId: String, val modelId: String)

/**
 * 规范内容块：会话历史里只存这一种形状，发给具体供应商时再由编解码器投影
 * （docs/agent/04-子系统设计.md §4.2「发送时投影」）。
 *
 * 加变体前先问：四套线协议（Anthropic / OpenAI Chat / OpenAI Responses / Gemini）里至少一家有对应物吗？
 * 只有一家才有的东西放进 [OpaquePart]，不要为它开新变体。
 */
@Serializable
sealed interface Part

@Serializable
@SerialName("text")
data class TextPart(val text: String) : Part

@Serializable
@SerialName("image")
data class ImagePart(val mediaType: String, val source: ImageSource) : Part

@Serializable
sealed interface ImageSource {
    /** 小图直接内联。大图应先落成会话附件，用 [Artifact] 引用，免得日志膨胀 */
    @Serializable
    @SerialName("base64")
    data class Base64(val data: String) : ImageSource

    /** 会话 artifacts 目录下的相对路径（docs/agent/03-核心契约.md §8 的目录布局） */
    @Serializable
    @SerialName("artifact")
    data class Artifact(val path: String) : ImageSource
}

/**
 * 文档（目前只有 PDF）。[source] 借用 [ImageSource] 的两种形态（内联 base64 / 会话附件路径）：
 * 同样是「二进制 + 媒体类型」，另起一套只会让内联与降级的代码各写一遍。
 * 不做成 [ImagePart] 的子类：图片与文档的供应商字段名、模型能力位（supportsImages / supportsPdf）都不同。
 */
@Serializable
@SerialName("document")
data class DocumentPart(val mediaType: String, val source: ImageSource) : Part

/**
 * 模型的思考过程。[signature] 是 Anthropic 一类供应商用来校验「这段思考没被改过」的签名，
 * 只能原样回传给**同一个模型**；换了模型，编解码器会把它转成普通文本或剔除。
 */
@Serializable
@SerialName("thinking")
data class ThinkingPart(val text: String, val origin: ModelRef, val signature: String? = null) : Part

/** 供应商加密后给回来的思考内容（看不到原文，只能原样回传） */
@Serializable
@SerialName("redacted_thinking")
data class RedactedThinkingPart(val data: String, val origin: ModelRef) : Part

@Serializable
@SerialName("tool_call")
data class ToolCallPart(val callId: CallId, val name: String, val input: JsonObject) : Part

/** [content] 只放 [TextPart]、[ImagePart] 与 [DocumentPart]；大输出只留预览，全文在 artifacts 里 */
@Serializable
@SerialName("tool_result")
data class ToolResultPart(
    val callId: CallId,
    val content: List<Part>,
    val isError: Boolean = false,
) : Part

/**
 * 某一家专有、别家没有对应物的块（例如 OpenAI Responses 的 reasoning item、豆包的 encrypted_content）。
 * 只有 [origin] 那一家的编解码器认识它，发给别家时丢弃。
 */
@Serializable
@SerialName("opaque")
data class OpaquePart(val origin: ModelRef, val json: JsonElement) : Part
