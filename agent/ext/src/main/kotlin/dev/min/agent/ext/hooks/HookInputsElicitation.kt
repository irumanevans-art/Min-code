package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * MCP 问答（elicitation）类事件的 stdin 专属字段（R1a.2）：Elicitation、ElicitationResult。
 */

/** Elicitation 输入：MCP 请求用户输入时触发。exit 2 阻断但 hookSpecificOutput 被忽略；systemMessage/continue 被丢弃 */
@Serializable
data class ElicitationInput(
    /** MCP 服务器名（matcher 对它） */
    @SerialName("mcp_server_name") val mcpServerName: String? = null,
    /** 请求用户输入的说明 */
    val message: String? = null,
    /** 问答模式（可缺席；文档未写取值） */
    val mode: String? = null,
    /** url 型问答的地址（可缺席） */
    val url: String? = null,
    /** 本次问答的标识（可缺席） */
    @SerialName("elicitation_id") val elicitationId: String? = null,
    /** 表单 schema（可缺席；文档未写内部形状，用 JsonElement 承载） */
    @SerialName("requested_schema") val requestedSchema: JsonElement? = null,
)

/** ElicitationResult 输入：用户答复 MCP 请求后触发。exit 2 = 改为 decline；systemMessage/continue 被丢弃 */
@Serializable
data class ElicitationResultInput(
    /** MCP 服务器名（matcher 对它） */
    @SerialName("mcp_server_name") val mcpServerName: String? = null,
    /** 用户的答复动作 */
    val action: String? = null,
    /** 问答模式（可缺席） */
    val mode: String? = null,
    /** 本次问答的标识（可缺席） */
    @SerialName("elicitation_id") val elicitationId: String? = null,
    /** 用户填写的表单值（可缺席；文档未写内部形状，用 JsonElement 承载） */
    val content: JsonElement? = null,
)
