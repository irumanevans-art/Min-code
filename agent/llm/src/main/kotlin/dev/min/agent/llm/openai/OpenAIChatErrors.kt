package dev.min.agent.llm.openai

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.UnavailableModel
import dev.min.agent.spi.ModelFailureKind

internal class OverflowMatcher(extraPatterns: List<String>) {
    // 正则在构造时验证，不能等到错误路径再盖住原始错误。
    private val patterns = (BUILT_IN + extraPatterns).map { Regex(it, RegexOption.IGNORE_CASE) }
    fun matches(body: String): Boolean = patterns.any { it.containsMatchIn(body) }

    private companion object {
        val BUILT_IN = listOf(
            "context_length_exceeded", "maximum context length", "context window exceeded",
            "prompt is too long", "prompt too long", "input token exceed the limit",
            "range of input length should be",
        ).map { Regex.escape(it) }
    }
}

internal fun bodyPreview(body: String): String = body.take(500)

internal fun classifyHttpFailure(
    status: Int, headers: List<Pair<String, String>>, body: String, overflow: OverflowMatcher,
): ModelException {
    val kind = when {
        status == 401 || status == 403 -> ModelFailureKind.Auth
        status == 408 -> ModelFailureKind.Network
        status == 429 -> ModelFailureKind.RateLimited
        status == 529 -> ModelFailureKind.Overloaded
        status in 500..599 -> ModelFailureKind.Server
        status == 413 || status in 400..499 && overflow.matches(body) -> ModelFailureKind.ContextOverflow
        status in 400..499 && UnavailableModel.matches(status, body) -> ModelFailureKind.ModelUnavailable
        status in 400..499 -> ModelFailureKind.BadRequest
        else -> ModelFailureKind.Other
    }
    return ModelException(
        kind, "HTTP $status：${bodyPreview(body)}", httpStatus = status,
        retryAfterMs = if (status == 429) retryAfterMs(headers) else null,
        beforeFirstByte = true,
    )
}

private fun retryAfterMs(headers: List<Pair<String, String>>): Long? {
    val seconds = headers.lastOrNull { it.first.equals("retry-after", ignoreCase = true) }
        ?.second?.trim()?.toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < 0) return null
    return (seconds * 1000).toLong()
}

internal fun classifyStreamError(type: String?, body: String, overflow: OverflowMatcher, before: Boolean): ModelException {
    val kind = when (type) {
        "authentication_error", "permission_error", "invalid_api_key" -> ModelFailureKind.Auth
        "rate_limit_error" -> ModelFailureKind.RateLimited
        "overloaded_error" -> ModelFailureKind.Overloaded
        "api_error", "server_error" -> ModelFailureKind.Server
        "model_not_found" -> ModelFailureKind.ModelUnavailable
        else -> when {
            overflow.matches(body) -> ModelFailureKind.ContextOverflow
            UnavailableModel.matches(null, body) -> ModelFailureKind.ModelUnavailable
            else -> ModelFailureKind.BadRequest
        }
    }
    return ModelException(kind, "流中错误 ${type ?: "unknown"}：${bodyPreview(body)}", beforeFirstByte = before)
}
