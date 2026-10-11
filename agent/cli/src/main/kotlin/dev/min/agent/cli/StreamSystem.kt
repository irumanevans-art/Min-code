package dev.min.agent.cli

import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 会话事件 → stream-json 的 `system` 行（SDKMessage 里 `type:"system"` 的那些子类型）。纯函数：一条事件几行、什么字段，
 * 都只看事件本身，便于单测钉死。字段照 cc-sdk-typescript.md 与 headless 页（见 [StreamSystemTest] 头注释）；
 * Min 没有对应事件的子类型不发，原文只列名字没给字段的不编。
 *
 * - `api_retry`：[ModelRequestFailed] 且会重试时；`retry_delay_ms` / `max_retries` 取事件里记的（旧日志没有就省略这两个键）；
 * - `status` / `compact_boundary`：压缩开始 / 完成（`pre_tokens` = 压缩前估算的输入 token）；压缩失败只清掉 status、补一条 informational；
 * - `hook_started` + `hook_response`：[HooksRan] 按事件合并记一条，没有逐个 handler 的名字与进度流，所以一对行、`hook_name` 用事件名；
 *   SessionStart / Setup 一直发，别的事件要 `--include-hook-events`（CLI reference）；后台 hook 完成只发 `hook_response`（不发 started）；
 * - `informational`：[Note]（Min 的说明）与模型回退。SDK 的 level 没有 error，错误级按 `warning` 给。
 */
internal fun systemRows(
    event: SessionEvent,
    sessionId: String,
    includeHookEvents: Boolean,
    newUuid: () -> String = { UUID.randomUUID().toString() },
): List<JsonObject> {
    fun row(subtype: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "system")
        put("subtype", subtype)
        fields()
        put("uuid", newUuid())
        put("session_id", sessionId)
    }
    fun status(value: String?) = row("status") { put("status", value?.let { JsonPrimitive(it) } ?: JsonNull) }
    fun informational(content: String, level: String) = row("informational") { put("content", content); put("level", level) }

    return when (event) {
        is ModelRequestFailed -> if (!event.willRetry) emptyList() else listOf(row("api_retry") {
            put("attempt", event.attempt)
            event.maxRetries?.let { put("max_retries", it) }
            event.retryDelayMs?.let { put("retry_delay_ms", it) }
            put("error_status", event.httpStatus?.let { JsonPrimitive(it) } ?: JsonNull)
            put("error", apiErrorCategory(event.kind))
        })
        is ModelFallback -> listOf(informational(
            "模型 ${event.from.modelId} 暂时不可用（${event.reason.name}），本回合改用 ${event.to.modelId}：${event.message}", "notice",
        ))
        is CompactionStarted -> listOf(status("compacting"))
        is ContextCompacted -> listOf(status(null), row("compact_boundary") {
            put("compact_metadata", buildJsonObject {
                // SDK 只有 manual / auto：撞墙后的压缩（overflow）也是内核自动触发的
                put("trigger", if (event.trigger == CompactionTrigger.Manual) "manual" else "auto")
                put("pre_tokens", event.tokensBefore)
            })
        })
        is CompactionFailed -> listOf(status(null), informational("压缩失败：${event.reason}", "warning"))
        is Note -> listOf(informational(event.text, when (event.level) {
            NoteLevel.Info -> "info"
            NoteLevel.Warning, NoteLevel.Error -> "warning"
        }))
        is HooksRan -> {
            val always = event.event == "SessionStart" || event.event == "Setup"
            if (!always && !includeHookEvents) emptyList() else {
                val hookId = newUuid()
                val problems = event.problems.joinToString("\n")
                val response = row("hook_response") {
                    put("hook_id", hookId); put("hook_name", event.event); put("hook_event", event.event)
                    put("output", problems); put("stdout", ""); put("stderr", problems)
                    put("outcome", if (event.problems.isEmpty()) "success" else "error")
                }
                if (event.background) listOf(response)
                else listOf(row("hook_started") { put("hook_id", hookId); put("hook_name", event.event); put("hook_event", event.event) }, response)
            }
        }
        else -> emptyList()
    }
}

/**
 * headless 页 api_retry 表的 `error` 取值（authentication_failed / rate_limit / overloaded / invalid_request /
 * model_not_found / server_error / unknown）。Min 的失败种类里没有一一对应的（网络错误、上下文超长……）按最接近的给：
 * 网络与其它是 unknown，上下文超长是 invalid_request（请求本身太大）
 */
internal fun apiErrorCategory(kind: ModelFailureKind): String = when (kind) {
    ModelFailureKind.Auth -> "authentication_failed"
    ModelFailureKind.RateLimited -> "rate_limit"
    ModelFailureKind.Overloaded -> "overloaded"
    ModelFailureKind.BadRequest, ModelFailureKind.ContextOverflow -> "invalid_request"
    ModelFailureKind.ModelUnavailable -> "model_not_found"
    ModelFailureKind.Server -> "server_error"
    ModelFailureKind.Network, ModelFailureKind.Other -> "unknown"
}
