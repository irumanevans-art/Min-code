package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 提示与显示类事件的 hookSpecificOutput 形状（R1a.1 + R1a.2）：
 * UserPromptSubmit、UserPromptExpansion、MessageDisplay。
 */

/** UserPromptSubmit 的 hookSpecificOutput：additionalContext 注入 prompt 旁；顶层 decision:"block"+reason（抹除 prompt）在 [HookOutputCommon]，exit 2 同 block */
@Serializable
data class UserPromptSubmitSpecificOutput(
    /** 应等于 "UserPromptSubmit" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入 prompt 旁的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
    /** 会话标题 */
    @SerialName("sessionTitle") val sessionTitle: String? = null,
    /** 是否不把原 prompt 送达 Claude */
    @SerialName("suppressOriginalPrompt") val suppressOriginalPrompt: Boolean? = null,
)

/** UserPromptExpansion 的 hookSpecificOutput：additionalContext 注入 prompt 旁；顶层 decision:"block"+reason 在 [HookOutputCommon]，exit 2 同 block */
@Serializable
data class UserPromptExpansionSpecificOutput(
    /** 应等于 "UserPromptExpansion" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入 prompt 旁的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)

/** MessageDisplay 的 hookSpecificOutput：displayContent 替换屏幕显示（纯显示替换；转录与 Claude 看到的原文不变） */
@Serializable
data class MessageDisplaySpecificOutput(
    /** 应等于 "MessageDisplay" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 替换后显示的内容 */
    @SerialName("displayContent") val displayContent: String? = null,
)
