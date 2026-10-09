package dev.min.agent.spi

import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 只冻结能交付的正文；拒绝的 handback 原文留在子转录，不能进入父报告。空正文撤销 hook 续跑前的交卷。 */
@Serializable @SerialName("subagent_report_ready")
data class SubagentReportReady(val turnId: TurnId, val body: String? = null, val review: String? = null, val handback: Boolean = false) : SessionEvent
