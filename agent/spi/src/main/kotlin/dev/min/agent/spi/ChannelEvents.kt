package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * MCP channel（Claude Code 的 channels：MCP 服务器把外部事件推进正在跑的会话）进日志的形状。
 *
 * 与 [PeerMessage] 同一个写法：只写在**收件会话**的日志里，与同一次提交里的 [InputReceived]
 * （origin = [InputOrigin.Channel]）成对出现——「来历」与「输入进了收件箱」要么都在要么都不在。
 * 没订阅 channel 的会话、没声明能力的服务器推来的东西不写任何事件（它们没有进对话）。
 *
 * 格式纪律同 SessionEvent.kt：字段只加不改不删。
 */

/**
 * channel 服务器推来的一条消息。[server] 是配置里的服务器名（模型看到的 `<channel source="…">` 同它，服务器不能自称）；
 * [meta] 是服务器给的路由信息（键只含字母数字下划线，已滤掉 `source`）；[content] 是原文（可能已按上限截断）。
 */
@Serializable
@SerialName("channel_message")
data class ChannelMessage(
    val server: String,
    val meta: Map<String, String>,
    val content: String,
    val receivedAtMs: Long,
) : SessionEvent
