package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * MCP 问答（elicitation）类事件的 hookSpecificOutput 形状（R1a.1 + R1a.2）：Elicitation、ElicitationResult。
 * 两者都可完全跳过对话框程序化应答；systemMessage/continue 被丢弃。
 */

/** Elicitation 的 hookSpecificOutput：程序化应答 MCP 问答 */
@Serializable
data class ElicitationSpecificOutput(
    /** 应等于 "Elicitation" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** accept / decline / cancel */
    val action: String? = null,
    /** accept 时的表单值 */
    val content: JsonElement? = null,
)

/** ElicitationResult 的 hookSpecificOutput：覆写用户答复（发回 MCP 服务器前）；exit 2 = 改为 decline */
@Serializable
data class ElicitationResultSpecificOutput(
    /** 应等于 "ElicitationResult" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** accept / decline / cancel */
    val action: String? = null,
    /** 覆写后的表单值 */
    val content: JsonElement? = null,
)
