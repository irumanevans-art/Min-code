package dev.min.agent.llm

import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * 一次模型请求，已经是规范形状：历史来自日志投影（kernel 的 modelMessages），system 与工具来自上下文装配。
 * 编解码器（每种线协议一个 [ModelClient] 实现）负责把它投影成具体供应商的请求。
 */
data class ModelRequest(
    val profile: ModelProfile,
    /** 稳定前缀在前、易变在后；编解码器按线协议决定缓存断点打在哪 */
    val system: List<String>,
    val messages: List<ModelMessage>,
    val tools: List<ToolSpec>,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    /** null = 不控制思考强度（不合并任何补丁） */
    val effort: Effort? = null,
    /** null = 取 [ModelProfile.maxOutputTokens] */
    val maxOutputTokens: Int? = null,
    val cache: CacheHints = CacheHints(),
    val timeoutMs: Long = 600_000,
)

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

sealed interface ToolChoice {
    data object Auto : ToolChoice

    /** 必须调某个工具（Anthropic 的 any、OpenAI 的 required） */
    data object Required : ToolChoice

    data object None : ToolChoice

    data class Named(val name: String) : ToolChoice
}

/**
 * 缓存意图，不是具体参数：Anthropic 系据此打 cache_control 断点，OpenAI 系据此给 prompt_cache_key，
 * 自动前缀缓存的供应商（DeepSeek / GLM / Kimi 等）忽略——它们靠的是前缀纪律，由上下文装配保证。
 */
data class CacheHints(
    /** 同一会话的请求共用一个键，让支持的供应商把它们路由到同一份缓存 */
    val key: String? = null,
    /** 长 TTL（Anthropic 的 1h）：手机上断断续续用，5 分钟的缓存早就过期了 */
    val longTtl: Boolean = false,
)

/**
 * 流式事件。增量只给界面看；[Completed] 带着组装好的完整助手消息，内核只落盘它。
 * 失败不走事件：Flow 以 [ModelException] 结束。
 */
sealed interface ModelStreamEvent {
    data class Started(val providerMessageId: String?) : ModelStreamEvent
    data class TextDelta(val text: String) : ModelStreamEvent
    data class ThinkingDelta(val text: String) : ModelStreamEvent

    /** [index] 是本条消息内工具调用的序号，用来把后续的参数增量对上号 */
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : ModelStreamEvent
    data class ToolInputDelta(val index: Int, val partialJson: String) : ModelStreamEvent

    /**
     * [message] 的 role 必为 Assistant，parts 里依次是 thinking / text / tool_call / opaque 块，
     * 工具调用的 input 已解析成 JSON 对象。
     * [truncated]：输出撞上上限而停止——内核据此不执行本条消息里的工具调用（参数可能不完整）。
     */
    data class Completed(
        val message: ModelMessage,
        val usage: Usage,
        val stop: StopReason,
        val truncated: Boolean,
    ) : ModelStreamEvent
}

/**
 * 模型请求失败。分类是给内核做决定用的：[beforeFirstByte] 且可重试的，内核按 [retryAfterMs] 退避重发；
 * 吐出部分内容后失败的，从上一个安全点重开；[ModelFailureKind.ContextOverflow] 走压缩流水线。
 */
class ModelException(
    val kind: ModelFailureKind,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    val beforeFirstByte: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val retryable: Boolean
        get() = kind == ModelFailureKind.Network ||
            kind == ModelFailureKind.RateLimited ||
            kind == ModelFailureKind.Overloaded ||
            kind == ModelFailureKind.Server
}

/** 一种线协议的客户端。实现只做「规范请求 ↔ 线格式」与错误分类；重试、记账、落盘都是内核的事 */
fun interface ModelClient {
    fun stream(request: ModelRequest): Flow<ModelStreamEvent>
}
