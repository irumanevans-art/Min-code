package dev.min.agent.spi

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 模型回退链进日志的形状（设置 `fallbackModel` / CLI `--fallback-model`）。单独一个文件放，理由同 MemoryEvents.kt。
 */

/**
 * 主模型在这个回合里用不下去，内核换了链上的下一个。落在失败事件（[ModelRequestFailed]）之后、
 * 用新模型重发的 [ModelRequestStarted] 之前。
 *
 * 范围照 CC：**只管这一个回合**（回合结束状态就回到主模型，下一个回合先试主模型）；
 * 同一回合里后续的请求都用 [to]，不会每次请求都先撞一遍已经倒了的主模型。
 * 链走到底还是失败，回合照常失败，不再写这个事件。
 */
@Serializable
@SerialName("model_fallback")
data class ModelFallback(
    val turnId: TurnId,
    /** 刚失败的那个请求；换模型后用同一个 id 重发（尝试次数从 1 重新数） */
    val requestId: RequestId,
    /** 失败的模型：回合里第一次换是会话的主模型，之后是上一个回退模型 */
    val from: ModelRef,
    val to: ModelRef,
    val reason: ModelFailureKind,
    /** 供应商的报错原文，给人看 */
    val message: String,
) : SessionEvent
