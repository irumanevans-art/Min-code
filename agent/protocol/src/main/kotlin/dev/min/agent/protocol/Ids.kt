package dev.min.agent.protocol

import kotlinx.serialization.Serializable

/*
 * 各种 id 都包一层 value class：编译期分得清「这是 turn 的 id 还是工具调用的 id」，
 * 序列化后仍是一个裸字符串，日志与协议里不多占一个字节。
 * id 由内核的 IdGenerator 端口生成（测试里可控），这里只管类型。
 */

@Serializable
@JvmInline
value class SessionId(val value: String)

@Serializable
@JvmInline
value class TurnId(val value: String)

@Serializable
@JvmInline
value class MessageId(val value: String)

/** 一次工具调用。模型给的 id 在不同供应商间格式不一，发送时由编解码器投影，日志里存内核自己的这一个 */
@Serializable
@JvmInline
value class CallId(val value: String)

@Serializable
@JvmInline
value class ApprovalId(val value: String)

/** 一次模型请求（含重试的每一次尝试共用同一个 id，靠 attempt 区分） */
@Serializable
@JvmInline
value class RequestId(val value: String)

/** UI 为一条输入生成的幂等键：同一个键重发只收一次（断网重连、通知栏重复点击） */
@Serializable
@JvmInline
value class ClientMsgId(val value: String)

/** 一次向用户的提问（AskUserQuestion 的一组问题共用一个）。和审批一样是持久请求：进程重启后照它把提问卡片重新挂到界面上 */
@Serializable
@JvmInline
value class QuestionId(val value: String)
