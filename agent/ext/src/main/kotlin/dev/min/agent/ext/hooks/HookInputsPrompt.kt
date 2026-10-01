package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 提示与通知类事件的 stdin 专属字段（R1a.2）：UserPromptSubmit、UserPromptExpansion、MessageDisplay、
 * Notification。
 */

/** UserPromptSubmit 输入：提交 prompt 后、Claude 处理前触发。command/http/mcp_tool 默认超时 30s（超时丢弃输出；SDK 回调超时则阻断 prompt 不失败开放） */
@Serializable
data class UserPromptSubmitInput(
    /** 用户 prompt（粘贴占位符已展开，带 `<pasted_content id=…>` 包裹） */
    val prompt: String? = null,
)

/** UserPromptExpansion 输入：命令展开成 prompt 后、送达前触发 */
@Serializable
data class UserPromptExpansionInput(
    /** slash_command / mcp_prompt */
    @SerialName("expansion_type") val expansionType: String? = null,
    /** 展开的命令名（matcher 对它） */
    @SerialName("command_name") val commandName: String? = null,
    /** 命令参数 */
    @SerialName("command_args") val commandArgs: String? = null,
    /** 命令来源 */
    @SerialName("command_source") val commandSource: String? = null,
    /** 展开得到的 prompt */
    val prompt: String? = null,
)

/** MessageDisplay 输入：助手文本显示时触发。默认超时 10s；非交互（SDK/-p）下每条消息单次触发、带全文 */
@Serializable
data class MessageDisplayInput(
    /** 所属 turn 的标识 */
    @SerialName("turn_id") val turnId: String? = null,
    /** 消息标识 */
    @SerialName("message_id") val messageId: String? = null,
    /** 本消息内的行序号 */
    val index: Long? = null,
    /** 是否本消息最后一行 */
    val final: Boolean? = null,
    /** 新增的整行文本 */
    val delta: String? = null,
)

/** Notification 输入：发送通知时触发。无阻断；systemMessage/continue 被丢弃，terminalSequence 有效 */
@Serializable
data class NotificationInput(
    /** 通知内容 */
    val message: String? = null,
    /** 通知标题（可缺席） */
    val title: String? = null,
    /**
     * 通知类型（matcher 对它）：permission_prompt（约 6 秒后；SDK canUseTool 会话约 6 秒且不因打字推迟，
     * CLAUDE_CODE_DISABLE_PERMISSION_PROMPT_NOTIFY_HOOKS=1 关闭）、idle_prompt（约 60 秒）、auth_success、
     * elicitation_dialog、elicitation_url_dialog、elicitation_complete、elicitation_response、
     * agent_needs_input（v2.1.198+）、agent_completed（v2.1.198+）、
     * quota_auto_resume_fired / quota_auto_resume_stale / quota_auto_resume_disabled（v2.1.234+）
     */
    @SerialName("notification_type") val notificationType: String? = null,
)
