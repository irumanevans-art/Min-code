package dev.min.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次模型请求的用量。只信供应商回报的数；估算值不进这里（估算只用于发请求前画上下文条）。
 * 缓存读写单列，是因为国产供应商和 Anthropic 都按命中单独计价，花费和命中率都从这两项算。
 *
 * **口径（各编解码器必须一致）**：[inputTokens] 只算**没有命中缓存、也不是写入缓存**的那部分输入（Anthropic 的 input_tokens 口径）。
 * OpenAI 系的 prompt_tokens 含缓存命中，要减去 cached_tokens 再填；DeepSeek 的 prompt_cache_miss_tokens 直接就是它。
 * 总输入 = inputTokens + cacheReadTokens + cacheWriteTokens。
 */
@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
    )
}

/** 各家 stop / finish reason 归一后的取值；归不进来的记 [Other] 并保留原文 */
@Serializable
sealed interface StopReason {
    @Serializable @SerialName("end_turn") data object EndTurn : StopReason
    @Serializable @SerialName("tool_use") data object ToolUse : StopReason
    @Serializable @SerialName("max_tokens") data object MaxTokens : StopReason
    @Serializable @SerialName("stop_sequence") data object StopSequence : StopReason
    @Serializable @SerialName("refusal") data object Refusal : StopReason
    @Serializable @SerialName("other") data class Other(val raw: String) : StopReason
}

/** 与 Claude Code 同名同义（docs/agent/04-子系统设计.md §4.4），用户在两个引擎间切换不用重新理解 */
@Serializable
enum class PermissionMode {
    @SerialName("default") Default,
    @SerialName("acceptEdits") AcceptEdits,
    @SerialName("plan") Plan,
    @SerialName("auto") Auto,
    @SerialName("dontAsk") DontAsk,
    @SerialName("bypassPermissions") BypassPermissions,
}

/**
 * 统一的思考强度 7 档，由编解码器逐模型映射到各家参数，不支持的档就近取（pi 的做法）。
 * 档位是用户意图，不是某家的参数值——所以这里不出现 budget_tokens 之类的数字。
 */
@Serializable
enum class Effort {
    @SerialName("off") Off,
    @SerialName("minimal") Minimal,
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
    @SerialName("xhigh") XHigh,
    @SerialName("max") Max,
}
