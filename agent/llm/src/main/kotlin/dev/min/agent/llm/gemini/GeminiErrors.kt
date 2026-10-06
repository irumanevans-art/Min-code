package dev.min.agent.llm.gemini

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.UnavailableModel
import dev.min.agent.spi.ModelFailureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal class GeminiOverflowMatcher(extra: List<String>) {
    // 只认明确的输入超长，不把 quota、输出预算错误或笼统 RESOURCE_EXHAUSTED 当成上下文问题。
    private val patterns = (listOf(
        "input token count.{0,100}exceeds.{0,100}maximum", "exceeds.{0,80}maximum.{0,40}token",
        "maximum.{0,40}context length", "context window exceeded", "prompt is too long",
    ) + extra).map { Regex(it, RegexOption.IGNORE_CASE) }
    fun matches(body: String) = patterns.any { it.containsMatchIn(body) }
}

internal fun geminiHttpError(status: Int, headers: List<Pair<String, String>>, body: String, overflow: GeminiOverflowMatcher): ModelException =
    ModelException(kind(status, null, body, overflow), "HTTP $status：${body.take(500)}", httpStatus = status,
        retryAfterMs = if (status == 429) retryAfter(headers) else null, beforeFirstByte = true)

internal fun geminiStreamError(error: JsonObject, body: String, overflow: GeminiOverflowMatcher, before: Boolean): ModelException {
    val code = (error["code"] as? JsonPrimitive)?.intOrNull
    return ModelException(kind(code, error.str("status"), body, overflow), "Gemini 流中错误：${body.take(500)}",
        httpStatus = code, beforeFirstByte = before)
}

private fun kind(code: Int?, status: String?, body: String, overflow: GeminiOverflowMatcher) = when {
    code == 401 || code == 403 -> ModelFailureKind.Auth
    code == 429 -> ModelFailureKind.RateLimited
    code == 408 -> ModelFailureKind.Network
    code == 503 || code == 529 -> ModelFailureKind.Overloaded
    code != null && code in 500..599 -> ModelFailureKind.Server
    code == null && status in setOf("UNAUTHENTICATED", "PERMISSION_DENIED") -> ModelFailureKind.Auth
    code == null && status == "RESOURCE_EXHAUSTED" -> ModelFailureKind.RateLimited
    code == null && status == "UNAVAILABLE" -> ModelFailureKind.Overloaded
    code == null && status == "INTERNAL" -> ModelFailureKind.Server
    code == null && status == "DEADLINE_EXCEEDED" -> ModelFailureKind.Network
    (code != null && code in 400..499 || status == "INVALID_ARGUMENT") && overflow.matches(body) -> ModelFailureKind.ContextOverflow
    // NOT_FOUND 也可能是别的资源（上传的文件、缓存内容）不存在，只有正文提到模型才算模型不可用
    (code == 404 || status == "NOT_FOUND") && UnavailableModel.matches(404, body) -> ModelFailureKind.ModelUnavailable
    code != null && code in 400..499 || status in setOf("INVALID_ARGUMENT", "NOT_FOUND", "FAILED_PRECONDITION") -> ModelFailureKind.BadRequest
    else -> ModelFailureKind.Other
}

private fun retryAfter(headers: List<Pair<String, String>>): Long? {
    val seconds = headers.lastOrNull { it.first.equals("retry-after", true) }?.second?.trim()?.toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < 0) return null
    return (seconds * 1000).toLong()
}
