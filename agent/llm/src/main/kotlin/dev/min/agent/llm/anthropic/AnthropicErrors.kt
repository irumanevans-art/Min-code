package dev.min.agent.llm.anthropic

import dev.min.agent.llm.ModelException
import dev.min.agent.spi.ModelFailureKind

/*
 * 错误分类（docs/agent/detail/编解码器规范.md §2 的表）。只分类、不重试：重试是内核的事。
 */

/**
 * 上下文超长特征。协议标准写法内置，供应商特有的从 `provider.quirks.overflowPatterns` 补。
 * 构造时就编译正则：模型数据里写错了正则应该在建客户端时大声报错，而不是等到真出错那一刻
 * 在错误路径上再炸、把原始错误盖住。
 */
internal class OverflowMatcher(extraPatterns: List<String>) {
    private val patterns: List<Regex> = (BUILT_IN + extraPatterns).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matches(text: String): Boolean = patterns.any { it.containsMatchIn(text) }

    private companion object {
        val BUILT_IN = listOf(
            Regex.escape("prompt is too long"),
            Regex.escape("exceeds the context window"),
            Regex.escape("input length and `max_tokens` exceed context limit"),
        )
    }
}

/** 异常 message 里带的响应体开头长度上限：够人看，又不会把整页 HTML 塞进日志 */
private const val BODY_PREVIEW_CHARS = 500

internal fun bodyPreview(body: String): String =
    if (body.length <= BODY_PREVIEW_CHARS) body else body.take(BODY_PREVIEW_CHARS) + "…"

/** HTTP 非 2xx：请求还没吐出任何内容，所以 beforeFirstByte 恒为 true */
internal fun classifyHttpFailure(
    status: Int,
    headers: List<Pair<String, String>>,
    body: String,
    overflow: OverflowMatcher,
): ModelException {
    val text = "HTTP $status：${bodyPreview(body)}"
    fun failure(kind: ModelFailureKind, retryAfterMs: Long? = null) =
        ModelException(kind, text, httpStatus = status, retryAfterMs = retryAfterMs, beforeFirstByte = true)
    return when {
        status == 401 || status == 403 -> failure(ModelFailureKind.Auth)
        status == 429 -> failure(ModelFailureKind.RateLimited, retryAfterMs(headers))
        status == 529 -> failure(ModelFailureKind.Overloaded)
        status in 500..599 -> failure(ModelFailureKind.Server)
        // 超长优先于 BadRequest：它走压缩流水线，不是「改改请求再发」
        status in 400..499 -> failure(if (overflow.matches(body)) ModelFailureKind.ContextOverflow else ModelFailureKind.BadRequest)
        else -> failure(ModelFailureKind.Other)
    }
}

/** `retry-after` 的秒数（允许小数）→ 毫秒；HTTP-date 形式或非法值一律当没有 */
private fun retryAfterMs(headers: List<Pair<String, String>>): Long? {
    val raw = headers.lastOrNull { it.first.equals("retry-after", ignoreCase = true) }?.second ?: return null
    val seconds = raw.trim().toDoubleOrNull() ?: return null
    if (seconds < 0 || seconds.isNaN() || seconds.isInfinite()) return null
    return (seconds * 1000).toLong()
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
    val text = "流中错误 ${type ?: "unknown"}：${bodyPreview(message.orEmpty())}"
    val kind = when (type) {
        "overloaded_error" -> ModelFailureKind.Overloaded
        "api_error" -> ModelFailureKind.Server
        "rate_limit_error" -> ModelFailureKind.RateLimited
        "authentication_error", "permission_error" -> ModelFailureKind.Auth
        else -> if (overflow.matches(message.orEmpty())) ModelFailureKind.ContextOverflow else ModelFailureKind.BadRequest
    }
    return ModelException(kind, text, beforeFirstByte = beforeFirstByte)
}
