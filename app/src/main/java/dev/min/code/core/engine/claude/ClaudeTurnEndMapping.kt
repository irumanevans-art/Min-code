package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeTurnEnd
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.TurnEnd

/**
 * manager 的只读 [ClaudeTurnEnd] -> 通用的 [TurnEnd]（设计 §6.4）。放在适配器里：manager 不依赖 `core/engine`。
 */
internal fun ClaudeTurnEnd.toTurnEnd(): TurnEnd = when (kind) {
    ClaudeTurnEnd.Kind.Completed -> TurnEnd.Completed
    ClaudeTurnEnd.Kind.Interrupted -> TurnEnd.Interrupted(InterruptCause.User)
    ClaudeTurnEnd.Kind.Withdrawn -> TurnEnd.Interrupted(InterruptCause.Withdrawn)
    ClaudeTurnEnd.Kind.Failed -> TurnEnd.Failed(claudeFailureKind(detail), detail.orEmpty())
    ClaudeTurnEnd.Kind.Limit -> TurnEnd.Interrupted(InterruptCause.Limit)
    ClaudeTurnEnd.Kind.EngineExited -> TurnEnd.Interrupted(InterruptCause.EngineExited)
    ClaudeTurnEnd.Kind.ProcessDeath -> TurnEnd.Interrupted(InterruptCause.ProcessDeath)
}

/**
 * CLI 的失败原文 -> 失败种类（通知与转录里写「请求失败：网络」之类）。原文是一段人话
 * （`API Error: 529 {"type":"overloaded_error"...}`），没有结构化字段，只能认关键词；认不出就是 null（[FailureKind.Other] 之外的「不知道」），
 * 不瞎猜：错的种类比没有种类更误导。
 */
internal fun claudeFailureKind(detail: String?): FailureKind? {
    val text = detail?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        "prompt is too long" in text || "context_length" in text || "context window" in text -> FailureKind.ContextOverflow
        "429" in text || "rate_limit" in text || "rate limit" in text -> FailureKind.RateLimited
        "529" in text || "overloaded" in text -> FailureKind.Overloaded
        "401" in text || "403" in text || "authentication" in text || "invalid x-api-key" in text ||
            "invalid api key" in text || "permission_error" in text -> FailureKind.Auth
        "model" in text && ("not_found" in text || "not found" in text || "does not exist" in text) -> FailureKind.ModelUnavailable
        "econnrefused" in text || "econnreset" in text || "etimedout" in text || "enotfound" in text ||
            "fetch failed" in text || "network" in text || "timed out" in text || "connection" in text -> FailureKind.Network
        Regex("""\b5\d\d\b""").containsMatchIn(text) || "api_error" in text || "internal server" in text -> FailureKind.Server
        Regex("""\b400\b""").containsMatchIn(text) || "invalid_request" in text -> FailureKind.BadRequest
        else -> null
    }
}
