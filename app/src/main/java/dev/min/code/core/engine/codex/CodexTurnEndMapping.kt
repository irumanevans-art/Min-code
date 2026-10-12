package dev.min.code.core.engine.codex

import dev.min.code.core.codex.CodexTurnEnd
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.TurnEnd

/** manager 的只读 [CodexTurnEnd] -> 通用的 [TurnEnd]（设计 §6.5）。manager 不依赖 `core/engine`，映射放在适配器里 */
internal fun CodexTurnEnd.toTurnEnd(): TurnEnd = when (kind) {
    CodexTurnEnd.Kind.Completed -> TurnEnd.Completed
    CodexTurnEnd.Kind.Interrupted -> TurnEnd.Interrupted(InterruptCause.User)
    CodexTurnEnd.Kind.Failed -> TurnEnd.Failed(codexFailureKind(detail), detail.orEmpty())
    CodexTurnEnd.Kind.EngineExited -> TurnEnd.Interrupted(InterruptCause.EngineExited)
    CodexTurnEnd.Kind.ProcessDeath -> TurnEnd.Interrupted(InterruptCause.ProcessDeath)
}

/**
 * `turn.error.message` -> 失败种类。实测的措辞（codex-cli 0.162.1）：401 是 `unexpected status 401 Unauthorized`，
 * 500 耗尽是 `We're currently experiencing high demand`（`codexErrorInfo` 是 internalServerError）。
 * 只认得出的才给种类，认不出返回 null：错的种类比没有种类更误导。
 */
internal fun codexFailureKind(detail: String?): FailureKind? {
    val text = detail?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        "context" in text && ("window" in text || "length" in text || "limit" in text) -> FailureKind.ContextOverflow
        "401" in text || "403" in text || "unauthorized" in text || "authentication" in text || "invalid api key" in text -> FailureKind.Auth
        "429" in text || "rate limit" in text || "rate_limit" in text || "usage limit" in text -> FailureKind.RateLimited
        "high demand" in text || "overloaded" in text || "529" in text -> FailureKind.Overloaded
        "econnrefused" in text || "econnreset" in text || "connection" in text || "network" in text || "timed out" in text -> FailureKind.Network
        Regex("""\b5\d\d\b""").containsMatchIn(text) || "internal server" in text -> FailureKind.Server
        else -> null
    }
}
