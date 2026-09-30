package dev.min.agent.llm

import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.ModelFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 供应商与模型的描述都是**数据**：随 APK 带一份默认表，用户与中转可以覆盖（docs/agent/04-子系统设计.md §4.2）。
 * 所有字段的默认值取「标准行为」（Anthropic 或 OpenAI 官方的行为），怪癖才需要写出来。
 * 事实依据：D:\kimi\workspace\research\inventory\providers\ 的逐家抄录表。
 */

@Serializable
enum class Wire {
    @SerialName("anthropic_messages") AnthropicMessages,
    @SerialName("openai_chat") OpenAIChat,
    @SerialName("openai_responses") OpenAIResponses,
    @SerialName("gemini") Gemini,
}

@Serializable
enum class AuthScheme {
    /** `x-api-key: <key>`（Anthropic 与多数 /anthropic 兼容端点） */
    @SerialName("x_api_key") XApiKey,

    /** `Authorization: Bearer <key>` */
    @SerialName("bearer") Bearer,
}

@Serializable
data class ProviderDescriptor(
    val id: String,
    val baseUrl: String,
    val wire: Wire,
    val auth: AuthScheme,
    val extraHeaders: Map<String, String> = emptyMap(),
    val quirks: ProviderQuirks = ProviderQuirks(),
)

/** 按线协议分组的怪癖，默认 = 该协议官方的标准行为 */
@Serializable
data class ProviderQuirks(
    val anthropic: AnthropicQuirks = AnthropicQuirks(),
    val openAiChat: OpenAiChatQuirks = OpenAiChatQuirks(),
    /**
     * 上下文超长的报错特征（正则，匹配响应体）。编解码器内置各协议的标准写法，这里只补供应商特有的，
     * 例如某些 /anthropic 兼容端点用自己的错误文案。
     */
    val overflowPatterns: List<String> = emptyList(),
)

@Serializable
data class AnthropicQuirks(
    val anthropicVersion: String = "2023-06-01",
    val betaHeaders: List<String> = emptyList(),
    /** 不支持 cache_control 的兼容端点：请求里整个去掉，免得 400 */
    val cacheControl: Boolean = true,
    /** 端点会不会校验 / 回传 thinking 的 signature；不支持时回传的 thinking 块去掉 signature 字段 */
    val thinkingSignature: Boolean = true,
    /**
     * 端点认不认 redacted_thinking 块；不认的（DeepSeek /anthropic 页明写不支持）回传时整块丢掉，免得 400。
     * 出处：inventory/providers/deepseek.md §10
     */
    val redactedThinking: Boolean = true,
)

@Serializable
data class OpenAiChatQuirks(
    /** `max_tokens` 还是 `max_completion_tokens` */
    val maxTokensField: String = "max_completion_tokens",
    /** 支持 developer 角色；不支持的端点 system 用 system 角色 */
    val developerRole: Boolean = false,
    /** 流式时怎么拿 usage */
    val streamUsage: StreamUsage = StreamUsage.IncludeUsageOption,
    val parallelToolCalls: Boolean = true,
    /** 只有工具调用、没有正文时，assistant 消息的 content 怎么写 */
    val emptyContentWithToolCalls: EmptyContent = EmptyContent.Null,
    /** 响应里推理内容的字段名（DeepSeek / Kimi / GLM 多为 reasoning_content）；null = 该端点不回推理 */
    val reasoningField: String? = null,
    /** 工具结果里带图片时：内联在 tool 消息里，还是拆成紧随其后的一条 user 消息 */
    val toolResultImages: ToolResultImages = ToolResultImages.SeparateUserMessage,
)

@Serializable
enum class StreamUsage {
    /** 请求带 `stream_options: {include_usage: true}`，最后一个 chunk 给 usage */
    @SerialName("include_usage_option") IncludeUsageOption,

    /** 不用开关，最后一个 chunk 总带 usage */
    @SerialName("always") Always,

    /** 流式拿不到 usage（花费只能估算，界面要标出来） */
    @SerialName("none") None,
}

@Serializable
enum class EmptyContent {
    @SerialName("null") Null,
    @SerialName("empty_string") EmptyString,
    @SerialName("omit") Omit,
}

@Serializable
enum class ToolResultImages {
    @SerialName("inline") Inline,
    @SerialName("separate_user_message") SeparateUserMessage,
    @SerialName("unsupported") Unsupported,
}

/**
 * 一个具体模型的能力与调法。思考强度不写成代码分支，而是一张补丁表：
 * [effortPatches] 里「档位 → 要深合并进请求体的 JSON 片段」，例如
 * Anthropic 自适应模型 `{"thinking":{"type":"adaptive"},"output_config":{"effort":"high"}}`、
 * 按预算的旧模型 `{"thinking":{"type":"enabled","budget_tokens":16000}}`、
 * GLM `{"thinking":{"type":"enabled"}}`、Qwen `{"enable_thinking":true}`。
 * 请求的档位不在表里时，编解码器取表里最接近的一档（同距离取较低的一档，省钱）。
 */
@Serializable
data class ModelProfile(
    val ref: ModelRef,
    val wireModelId: String = ref.modelId,
    val family: ModelFamily,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val supportsImages: Boolean = false,
    val editStyle: EditStyle = EditStyle.StrReplace,
    val effortPatches: Map<Effort, JsonObject> = emptyMap(),
    val reasoningEcho: ReasoningEcho = ReasoningEcho.Always,
)

/**
 * 推理内容回传规则（docs/agent/04-子系统设计.md §4.2）。只作用于**同一个模型**产生的推理；
 * 换了模型的推理一律按投影规则转成普通文本或丢弃。
 */
@Serializable
enum class ReasoningEcho {
    /** 一律原样回传（Anthropic：thinking 块必须原样带回，改了就 400） */
    @SerialName("always") Always,

    /** 一律不回传（端点会忽略或报错） */
    @SerialName("never") Never,

    /** 只在工具调用循环内回传（DeepSeek：工具循环里不回传会 400，普通多轮回传了也被忽略） */
    @SerialName("within_tool_loop") WithinToolLoop,

    /** 一律回传，空串也要带上（Kimi） */
    @SerialName("always_including_empty") AlwaysIncludingEmpty,
}
