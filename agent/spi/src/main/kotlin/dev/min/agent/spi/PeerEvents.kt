package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 本机跨会话消息（Claude Code 的 cross-session messaging，Min 做法：本机会话间邮箱）进日志的形状。
 *
 * 只写在**收件人**的日志里，与同一次提交里的 [InputReceived]（origin = [InputOrigin.Peer]）成对出现：
 * 「来历」与「输入进了收件箱」要么都在要么都不在，与团队邮件（[TeamMessage]）同一个写法。
 * 被拒收、没批准的消息不写任何东西——它们没有进对方的会话。
 *
 * 格式纪律同 SessionEvent.kt：字段只加不改不删。
 */

/**
 * 另一个会话发来的一条消息。[from] 与 [fromName] 由收件方按发件会话的登记填写（Hub 内直送取自内存里的会话，
 * 桌面多进程取自发件进程的登记表），不信消息体里自称的名字。
 */
@Serializable
@SerialName("peer_message")
data class PeerMessage(
    val messageId: String,
    val from: SessionId,
    val fromName: String?,
    val text: String,
    val sentAtMs: Long,
) : SessionEvent
