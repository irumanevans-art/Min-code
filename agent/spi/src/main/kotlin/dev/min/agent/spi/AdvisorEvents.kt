package dev.min.agent.spi

import dev.min.agent.protocol.ModelRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 顾问选择随日志冻结；null 明确表示关闭，重放与子会话不重新读取设置文件。 */
@Serializable
@SerialName("advisor_configured")
data class AdvisorConfigured(val model: ModelRef? = null) : SessionEvent
