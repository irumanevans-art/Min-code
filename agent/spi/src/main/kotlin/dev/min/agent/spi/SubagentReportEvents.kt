package dev.min.agent.spi

import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 冻结核验结果与带警告或核验说明的正文，避免重放时丢失交付口径；空正文撤销 hook 续跑前的交卷。 */
@Serializable @SerialName("subagent_report_ready")
data class SubagentReportReady(val turnId: TurnId, val body: String? = null, val review: String? = null, val handback: Boolean = false) : SessionEvent
