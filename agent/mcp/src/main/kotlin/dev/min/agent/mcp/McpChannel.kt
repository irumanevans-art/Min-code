package dev.min.agent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * MCP channel（CC channels-reference）在协议层的形状。客户端只做两件事：
 * 一是按服务器握手时声明的能力放行或丢弃它推来的通知，二是把通知解析成不会带出脏数据的事件；
 * 「谁订阅了它、推来的话怎么进对话」是内核的事（kernel 的 McpChannels）。
 */

/** 服务器推来的 channel 事件 */
sealed interface McpChannelEvent {
    /**
     * `notifications/claude/channel`：[content] 是正文；[meta] 的键只含字母、数字、下划线（别的键 CC 静默丢弃），
     * 值只收字符串。[meta] 里没有 `source`——来源由内核按配置的服务器名填，服务器不能自称
     */
    data class Message(val content: String, val meta: Map<String, String>) : McpChannelEvent

    /** `notifications/claude/channel/permission`：服务器对一次审批转发的答复（[requestId] 必须是内核发出过的那个） */
    data class Verdict(val requestId: String, val allow: Boolean) : McpChannelEvent
}

internal const val CHANNEL_CAPABILITY = "claude/channel"
internal const val CHANNEL_PERMISSION_CAPABILITY = "claude/channel/permission"
internal const val CHANNEL_METHOD = "notifications/claude/channel"
internal const val CHANNEL_VERDICT_METHOD = "notifications/claude/channel/permission"
internal const val CHANNEL_PERMISSION_REQUEST_METHOD = "notifications/claude/channel/permission_request"

private val META_KEY = Regex("[A-Za-z0-9_]+")

/** 服务器声明了 `experimental[name]` 且值是对象。`false`、`null`、字符串都不算声明（CC：v2.1.234 起 `false` 视为没声明） */
internal fun JsonObject.declaresExperimental(name: String): Boolean =
    ((this["experimental"] as? JsonObject)?.get(name) as? JsonObject) != null

internal fun parseChannelMessage(params: JsonObject?): McpChannelEvent.Message? {
    val content = (params?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val meta = LinkedHashMap<String, String>()
    (params["meta"] as? JsonObject)?.forEach { (key, value) ->
        if (META_KEY.matches(key) && value is JsonPrimitive && value.isString) meta[key] = value.content
    }
    return McpChannelEvent.Message(content, meta)
}

internal fun parseChannelVerdict(params: JsonObject?): McpChannelEvent.Verdict? {
    val id = (params?.get("request_id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val behavior = (params["behavior"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (behavior) {
        "allow" -> McpChannelEvent.Verdict(id, true)
        "deny" -> McpChannelEvent.Verdict(id, false)
        else -> null
    }
}

