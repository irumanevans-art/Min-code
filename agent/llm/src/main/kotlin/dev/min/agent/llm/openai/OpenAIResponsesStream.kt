package dev.min.agent.llm.openai

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.SseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 增量只给 UI，terminal response 的 output 才是落盘依据；EOF 不能冒充完成。 */
internal class OpenAIResponsesStreamParser(private val profile: ModelProfile, private val overflow: OverflowMatcher) {
    var seen = 0
        private set
    var finished = false
        private set
    private var started = false
    private val callIndices = mutableMapOf<Int, Int>()

    fun feed(event: SseEvent): List<ModelStreamEvent> {
        val before = seen == 0
        seen++
        val data = try { AgentJson.parseToJsonElement(event.data) as? JsonObject } catch (e: IllegalArgumentException) {
            throw protocolError("Responses SSE 不是合法 JSON", e)
        } ?: throw protocolError("Responses SSE 不是 JSON 对象")
        val type = data.str("type") ?: event.event ?: throw protocolError("Responses SSE 缺少 type")
        val out = mutableListOf<ModelStreamEvent>()
        if (!started) {
            started = true
            out += ModelStreamEvent.Started((data["response"] as? JsonObject)?.str("id"))
        }
        when (type) {
            "response.output_text.delta", "response.refusal.delta" -> out += ModelStreamEvent.TextDelta(data.str("delta").orEmpty())
            "response.reasoning_summary_text.delta" -> out += ModelStreamEvent.ThinkingDelta(data.str("delta").orEmpty())
            "response.output_item.added" -> {
                val item = data["item"] as? JsonObject
                if (item?.str("type") == "function_call") {
                    val index = data.num("output_index") ?: throw protocolError("工具调用缺少 output_index")
                    val id = item.str("call_id")?.takeIf { it.isNotEmpty() } ?: throw protocolError("工具调用缺少 call_id")
                    val name = item.str("name")?.takeIf { it.isNotEmpty() } ?: throw protocolError("工具调用缺少 name")
                    val ordinal = callIndices.getOrPut(index) { callIndices.size }
                    out += ModelStreamEvent.ToolCallStarted(ordinal, id, name)
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = data.num("output_index")
                val ordinal = callIndices[index] ?: throw protocolError("工具参数增量没有对应调用")
                out += ModelStreamEvent.ToolInputDelta(ordinal, data.str("delta").orEmpty())
            }
            "error" -> throw classifyStreamError(data.str("code") ?: data.str("type"), data.toString(), overflow, before)
            "response.failed" -> {
                val response = data["response"] as? JsonObject
                val error = response?.get("error") as? JsonObject
                throw classifyStreamError(error?.str("code") ?: error?.str("type"), error.toString(), overflow, before)
            }
            "response.completed", "response.incomplete" -> {
                val response = data["response"] as? JsonObject ?: throw protocolError("终止事件缺少 response")
                out += complete(response, type == "response.incomplete")
                finished = true
            }
        }
        return out
    }

    private fun complete(response: JsonObject, incomplete: Boolean): ModelStreamEvent.Completed {
        val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
        val truncated = incomplete && reason == "max_output_tokens"
        if (incomplete && !truncated && reason != "content_filter") throw protocolError("Responses 未完成：$reason")
        val output = response["output"] as? JsonArray ?: throw protocolError("终止事件缺少 output")
        val parts = mutableListOf<Part>()
        var refusal = false
        val ids = mutableSetOf<String>()
        output.forEach { element ->
            val item = element as? JsonObject ?: throw protocolError("output item 不是对象")
            when (item.str("type")) {
                "reasoning" -> parts += OpaquePart(profile.ref, item)
                "message" -> {
                    if (item.str("role") != "assistant") throw protocolError("output message 不是 assistant")
                    (item["content"] as? JsonArray).orEmpty().forEach { content ->
                        val block = content as? JsonObject ?: throw protocolError("message content 不是对象")
                        when (block.str("type")) {
                            "output_text" -> parts += TextPart(block.str("text").orEmpty())
                            "refusal" -> { refusal = true; parts += TextPart(block.str("refusal").orEmpty()) }
                            else -> throw protocolError("不支持的 message content：${block.str("type")}")
                        }
                    }
                    parts += OpaquePart(profile.ref, item)
                }
                "function_call" -> {
                    val id = item.str("call_id")?.takeIf { it.isNotEmpty() } ?: throw protocolError("工具调用缺少 call_id")
                    if (!ids.add(id)) throw protocolError("工具 call_id 重复")
                    val name = item.str("name")?.takeIf { it.isNotEmpty() } ?: throw protocolError("工具调用缺少 name")
                    val arguments = item.str("arguments").orEmpty()
                    val parsed = try { AgentJson.parseToJsonElement(arguments.ifEmpty { "{}" }) as? JsonObject }
                        catch (_: IllegalArgumentException) { null }
                    parts += ToolCallPart(CallId(id), name, parsed ?: JsonObject(mapOf("__invalid_json" to JsonPrimitive(arguments))))
                    parts += OpaquePart(profile.ref, item)
                }
                else -> throw protocolError("不支持的 Responses output item：${item.str("type")}")
            }
        }
        val rawUsage = response["usage"] as? JsonObject
        val details = rawUsage?.get("input_tokens_details") as? JsonObject
        val total = rawUsage?.tokens("input_tokens") ?: 0L
        val read = details?.tokens("cached_tokens") ?: 0L
        val write = details?.tokens("cache_write_tokens") ?: 0L
        val usage = Usage((total - read - write).coerceAtLeast(0), rawUsage?.tokens("output_tokens") ?: 0, read, write)
        val stop = when {
            truncated -> StopReason.MaxTokens
            refusal || reason == "content_filter" -> StopReason.Refusal
            parts.any { it is ToolCallPart } -> StopReason.ToolUse
            else -> StopReason.EndTurn
        }
        return ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts), usage, stop, truncated)
    }

    private fun protocolError(message: String, cause: Throwable? = null) =
        ModelException(ModelFailureKind.Other, message, beforeFirstByte = seen == 0, cause = cause)
}

private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.tokens(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0)
