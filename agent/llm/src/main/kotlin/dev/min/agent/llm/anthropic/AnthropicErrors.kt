package dev.min.agent.llm.anthropic

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.OverflowMatcher
import dev.min.agent.llm.UnavailableModel
import dev.min.agent.llm.bodyPreview
import dev.min.agent.llm.retryAfterMs
import dev.min.agent.spi.ModelFailureKind

/*
 * 错误分类（docs/agent/detail/编解码器规范.md §2 的表）。只分类、不重试：重试是内核的事。
 */

/** Anthropic 协议标准的上下文超长写法；供应商特有的从 `provider.quirks.overflowPatterns` 补 */
internal fun anthropicOverflowMatcher(extraPatterns: List<String>) = OverflowMatcher(
    builtIn = listOf(
        "prompt is too long",
        "exceeds the context window",
        "input length and `max_tokens` exceed context limit",
    ).map { Regex.escape(it) },
    extra = extraPatterns,
)

/** 本协议的预览被截断时补省略号 */
private fun preview(text: String) = bodyPreview(text, ellipsis = true)

/** HTTP 非 2xx：请求还没吐出任何内容，所以 beforeFirstByte 恒为 true */
internal fun classifyHttpFailure(
    status: Int,
    headers: List<Pair<String, String>>,
    body: String,
    overflow: OverflowMatcher,
): ModelException {
    val text = "HTTP $status：${preview(body)}"
    fun failure(kind: ModelFailureKind, retryAfterMs: Long? = null) =
        ModelException(kind, text, httpStatus = status, retryAfterMs = retryAfterMs, beforeFirstByte = true)
    return when {
        status == 401 || status == 403 -> failure(ModelFailureKind.Auth)
        status == 429 -> failure(ModelFailureKind.RateLimited, retryAfterMs(headers))
        status == 529 -> failure(ModelFailureKind.Overloaded)
        status in 500..599 -> failure(ModelFailureKind.Server)
        // 超长优先于 BadRequest：它走压缩流水线，不是「改改请求再发」
        status in 400..499 -> failure(
            when {
                overflow.matches(body) -> ModelFailureKind.ContextOverflow
                UnavailableModel.matches(status, body) -> ModelFailureKind.ModelUnavailable
                else -> ModelFailureKind.BadRequest
            },
        )
        else -> failure(ModelFailureKind.Other)
    }
}

/**
 * 流中的 `event: error`。[beforeFirstByte] 由调用方按「这条错误之前是否已收到事件」给出。
 * 规格只写了 overloaded / api_error / 其余，这里多认了限流与鉴权两类：它们在流里出现时
 * 归成 BadRequest 会让内核放弃本该重试或该提示重新登录的请求（见 NOTES.md）。
 */
internal fun classifyStreamError(
    type: String?,
    message: String?,
    overflow: OverflowMatcher,
    beforeFirstByte: Boolean,
): ModelException {
    val text = "流中错误 ${type ?: "unknown"}：${preview(message.orEmpty())}"
    val kind = when (type) {
        "overloaded_error" -> ModelFailureKind.Overloaded
        "api_error" -> ModelFailureKind.Server
        "rate_limit_error" -> ModelFailureKind.RateLimited
        "authentication_error", "permission_error" -> ModelFailureKind.Auth
        "not_found_error" -> if (UnavailableModel.matches(404, message.orEmpty())) ModelFailureKind.ModelUnavailable else ModelFailureKind.BadRequest
        else -> when {
            overflow.matches(message.orEmpty()) -> ModelFailureKind.ContextOverflow
            UnavailableModel.matches(null, message.orEmpty()) -> ModelFailureKind.ModelUnavailable
            else -> ModelFailureKind.BadRequest
        }
    }
    return ModelException(kind, text, beforeFirstByte = beforeFirstByte)
}
