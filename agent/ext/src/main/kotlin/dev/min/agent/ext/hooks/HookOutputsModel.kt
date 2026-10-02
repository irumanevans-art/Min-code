package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 模型切换类事件的 hookSpecificOutput 形状（R1a.1 + R1a.2）：PreModelSwitch、PostModelSwitch。
 */

/** PreModelSwitch 的 hookSpecificOutput：切换前的许可决策。优先级 deny>ask>allow；仅交互 /model 能显示 ask，其余面（-p、/config、set_model）视 ask 为拒绝；超时 = 阻断（默认 30s，与 PreToolUse 相反）；systemMessage 无论决策都显示 */
@Serializable
data class PreModelSwitchSpecificOutput(
    /** 应等于 "PreModelSwitch" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** allow / deny / ask（不接受 defer） */
    @SerialName("permissionDecision") val permissionDecision: String? = null,
    /** deny = 给用户的原因或 set_model 的错误；ask = 确认框 */
    @SerialName("permissionDecisionReason") val permissionDecisionReason: String? = null,
)

/** PostModelSwitch 的 hookSpecificOutput：additionalContext 随切换后下一请求带上；5 秒内未完成则顺延附加到再下一请求；多次切换取最后 */
@Serializable
data class PostModelSwitchSpecificOutput(
    /** 应等于 "PostModelSwitch" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)
