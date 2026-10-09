package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AutoModeConfig(
    val environment: List<String> = emptyList(),
    val hardDeny: List<String> = emptyList(),
    val softDeny: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
    /** 空数组表示主动替换默认列表，必须与未配置区分；旧日志没有此字段时保留默认。 */
    val explicitSections: Set<String> = emptySet(),
    val classifyAllShell: Boolean = false,
)

/** 目录外模型必须由用户描述能力，不能借会话模型的能力猜测。 */
@Serializable
data class AutoModeReviewer(
    val model: ModelRef,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
@SerialName("auto_review_settings_loaded")
data class AutoReviewSettingsLoaded(
    val config: AutoModeConfig = AutoModeConfig(),
    val reviewer: AutoModeReviewer? = null,
    val unavailable: Boolean = false,
    val notes: List<String> = emptyList(),
    val disableAutoMode: Boolean = false,
    val disableBypassPermissionsMode: Boolean = false,
) : SessionEvent

@Serializable
enum class AutoReviewDecision {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
    @SerialName("ask") Ask,
}

@Serializable
@SerialName("auto_reviewed")
data class AutoReviewed(
    val turnId: TurnId,
    val callId: CallId,
    val model: ModelRef?,
    val decision: AutoReviewDecision,
    val reason: String,
    val usage: Usage = Usage(),
) : SessionEvent

/** 按本次请求的模型价格冻结花费，重放和换模型后不能重新定价。 */
@Serializable
@SerialName("model_charged")
data class ModelCharged(
    val model: ModelRef,
    val usage: Usage,
    val amount: Double? = null,
    val currency: String? = null,
) : SessionEvent
