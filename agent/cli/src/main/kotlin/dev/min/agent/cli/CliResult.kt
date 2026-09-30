package dev.min.agent.cli

import dev.min.agent.protocol.Usage
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 旧回合的累计 token 不能证明本轮成功；评测与缓存诊断必须读本次命令之后的持久事件。 */
internal data class CliResult(val session: String, val events: List<EventRecord>, val cumulative: Usage) {
    private val ends = events.mapNotNull { it.event as? TurnEnded }
    val completed: Boolean get() = ends.isNotEmpty() && ends.all { it.outcome == TurnOutcome.Completed }
    val failure: String? get() = ends.mapNotNull { (it.outcome as? TurnOutcome.Failed)?.message }.lastOrNull()
    val turnUsage: Usage get() = ends.fold(Usage()) { total, end -> total + end.usage }

    fun json(): JsonObject = buildJsonObject {
        put("session", session)
        put("completed", completed)
        // 保留既有累计字段的口径；新加单次命令的用量，resume 后无需手算差值。
        put("input_tokens", cumulative.inputTokens)
        put("output_tokens", cumulative.outputTokens)
        put("cache_read_tokens", cumulative.cacheReadTokens)
        put("cache_write_tokens", cumulative.cacheWriteTokens)
        put("turn_usage", usageJson(turnUsage))
        put("prefix_fingerprints", JsonArray(events.mapNotNull { (it.event as? ModelRequestStarted)?.prefixFingerprint }.map(::JsonPrimitive)))
        failure?.let { put("error", it) }
    }
}

private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
    put("input_tokens", usage.inputTokens)
    put("output_tokens", usage.outputTokens)
    put("cache_read_tokens", usage.cacheReadTokens)
    put("cache_write_tokens", usage.cacheWriteTokens)
}
