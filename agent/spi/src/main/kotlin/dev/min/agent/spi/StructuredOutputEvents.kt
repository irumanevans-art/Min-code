package dev.min.agent.spi

import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * --json-schema 的结构化输出（工具式）进日志的形状。工具调用与结果本来就在日志里
 * （助手消息的 ToolCallPart + ToolOutcome）；这条事件标明「其中哪次调用是被采纳的最终答案」，
 * 界面与 --json 的结果从日志就能重建，不用再去翻对话。
 */

/**
 * 模型对 StructuredOutput 的一次**通过校验**的调用被采纳为最终答案。`value` 是校验通过的入参原文。
 * 失败的尝试不写这条事件：它们留在对话里（错误的工具结果），到重试上限回合以 Failed 收尾
 */
@Serializable
@SerialName("structured_output")
data class StructuredOutputDelivered(val turnId: TurnId, val value: JsonObject) : SessionEvent
