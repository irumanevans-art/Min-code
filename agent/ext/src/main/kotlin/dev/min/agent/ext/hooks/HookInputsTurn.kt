package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * 回合与子 agent 类事件的 stdin 专属字段（R1a.2）：Stop、StopFailure、SubagentStart、SubagentStop、
 * TaskCreated、TaskCompleted、TeammateIdle，以及 Stop 携带的后台任务与会话定时任务条目。
 */

/** Stop 输入里的一条后台任务 */
@Serializable
data class BackgroundTask(
    /** 任务标识 */
    val id: String? = null,
    /** shell / subagent / monitor / workflow / teammate / cloud session / MCP task */
    val type: String? = null,
    /** 任务状态 */
    val status: String? = null,
    /** 任务描述（≤1000 字符） */
    val description: String? = null,
    /** shell 型才有：命令 */
    val command: String? = null,
    /** subagent 型才有：agent 类型 */
    @SerialName("agent_type") val agentType: String? = null,
    /** MCP task 型才有：服务器名 */
    val server: String? = null,
    /** MCP task 型才有：工具名 */
    val tool: String? = null,
    /** 任务名 */
    val name: String? = null,
)

/** Stop 输入里的一条会话定时任务 */
@Serializable
data class SessionCron(
    /** 任务标识 */
    val id: String? = null,
    /** 计划表达式 */
    val schedule: String? = null,
    /** 是否循环 */
    val recurring: Boolean? = null,
    /** 到点要跑的 prompt */
    val prompt: String? = null,
)

/** Stop 输入：Claude 完成回复时触发。exit 2 或顶层 decision:"block" 阻止停止；连续 8 次续跑上限（CLAUDE_CODE_STOP_HOOK_BLOCK_CAP 可调） */
@Serializable
data class StopInput(
    /** true 表示本 hook 链已因 Stop hook 续跑过，防止无限循环 */
    @SerialName("stop_hook_active") val stopHookActive: Boolean? = null,
    /** 最后一条助手消息全文（transcript_path 异步写入可能滞后，要最终助手文本用它） */
    @SerialName("last_assistant_message") val lastAssistantMessage: String? = null,
    /** 还在跑的后台任务 */
    @SerialName("background_tasks") val backgroundTasks: List<BackgroundTask>? = null,
    /** 会话内的定时任务 */
    @SerialName("session_crons") val sessionCrons: List<SessionCron>? = null,
)

/** StopFailure 输入：turn 因 API 错误结束时触发。输出与 exit code 全被丢弃（terminalSequence 除外） */
@Serializable
data class StopFailureInput(
    /**
     * 错误类别（matcher 对它）：rate_limit / overloaded / authentication_failed / oauth_org_not_allowed /
     * account_on_hold / billing_error / invalid_request / model_not_found / server_error / max_output_tokens /
     * cloud_credential_error / unknown
     */
    val error: String? = null,
    /** 错误详情（可缺席；文档未写内部形状，用 JsonElement 承载） */
    @SerialName("error_details") val errorDetails: JsonElement? = null,
    /** 此处为 API 错误串（如 "API Error: Rate limit reached"） */
    @SerialName("last_assistant_message") val lastAssistantMessage: String? = null,
)

/** SubagentStart 输入：子 agent 启动时触发。专属字段为空——agent_id/agent_type 是通用字段（matcher 匹配 agent_type，插件作用域形如 my-plugin:reviewer） */
@Serializable
class SubagentStartInput

/** SubagentStop 输入：子 agent 结束时触发。agent_id/agent_type 是通用字段 */
@Serializable
data class SubagentStopInput(
    /** true 表示本 hook 链已因 SubagentStop hook 续跑过 */
    @SerialName("stop_hook_active") val stopHookActive: Boolean? = null,
    /** 子 agent 的转录路径 */
    @SerialName("agent_transcript_path") val agentTranscriptPath: String? = null,
    /** 子 agent 最后一条助手消息全文 */
    @SerialName("last_assistant_message") val lastAssistantMessage: String? = null,
)

/** TaskCreated 输入：TaskCreate 建任务时触发。continue:false 被忽略且 Claude 继续工作 */
@Serializable
data class TaskCreatedInput(
    /** 任务标识 */
    @SerialName("task_id") val taskId: String? = null,
    /** 任务标题 */
    @SerialName("task_subject") val taskSubject: String? = null,
    /** 任务描述（可缺席） */
    @SerialName("task_description") val taskDescription: String? = null,
    /** 团队成员名（可缺席） */
    @SerialName("teammate_name") val teammateName: String? = null,
    /** 团队名（deprecated，将移除） */
    @SerialName("team_name") val teamName: String? = null,
)

/** TaskCompleted 输入：任务标完成时触发。字段同 TaskCreated；TaskUpdate 工具触发时 continue:false 被忽略 */
@Serializable
data class TaskCompletedInput(
    /** 任务标识 */
    @SerialName("task_id") val taskId: String? = null,
    /** 任务标题 */
    @SerialName("task_subject") val taskSubject: String? = null,
    /** 任务描述（可缺席） */
    @SerialName("task_description") val taskDescription: String? = null,
    /** 团队成员名（可缺席） */
    @SerialName("teammate_name") val teammateName: String? = null,
    /** 团队名（deprecated，将移除） */
    @SerialName("team_name") val teamName: String? = null,
)

/** TeammateIdle 输入：团队成员将空闲时触发。exit 2 或 continue:false 保持其工作 */
@Serializable
data class TeammateIdleInput(
    /** 团队成员名 */
    @SerialName("teammate_name") val teammateName: String? = null,
    /** 团队名（deprecated，将移除） */
    @SerialName("team_name") val teamName: String? = null,
)
