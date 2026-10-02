package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * 工具类事件的 stdin 专属字段（R1a.2）：PreToolUse、PermissionRequest、PostToolUse、PostToolUseFailure、
 * PostToolBatch、PermissionDenied，以及它们共用的 mcp_server 与 permission_suggestions 条目。
 * tool_input / tool_response 按工具不同而不同，用 JsonObject / JsonElement 承载，不为每个工具建类。
 */

/** MCP 工具附带的来源信息（v2.1.274+；MCP 工具的各工具事件才带） */
@Serializable
data class McpServerInfo(
    /** MCP 服务器名 */
    val name: String? = null,
    /** 来源：plugin / sdk / user / project…… */
    val source: String? = null,
)

/** PermissionRequest 的一条权限建议；回显 updatedPermissions 时也用它 */
@Serializable
data class PermissionSuggestion(
    /** addRules / replaceRules / removeRules / setMode / addDirectories / removeDirectories */
    val type: String? = null,
    /** session / localSettings / projectSettings / userSettings */
    val destination: String? = null,
    // 每条建议的具体负载（rules、mode、directories 等键）调研文档未列，未转录——见汇报。
    // setMode 含 bypassPermissions 时仅在会话以相应旗标启动时生效，且永不持久化为 defaultMode。
)

/** PreToolUse 输入：工具调用执行前触发。超时不阻断（Agent SDK 回调 hook 超时阻断）；@ 引用的文件不触发；EndConversation 不触发 */
@Serializable
data class PreToolUseInput(
    /** 工具名 */
    @SerialName("tool_name") val toolName: String? = null,
    /**
     * 工具入参，按工具不同：Bash{command,description,timeout,run_in_background}（PowerShell 同）、
     * Write{file_path,content}、Edit{file_path,old_string,new_string,replace_all}、Read{file_path,offset,limit}、
     * Glob{pattern,path}、Grep{pattern,path,glob,output_mode,-i,multiline}、WebFetch{url,prompt}、
     * WebSearch{query,allowed_domains,blocked_domains}、Agent{prompt,description,subagent_type,model}、
     * AskUserQuestion{questions,answers}、ExitPlanMode{plan,planFilePath,allowedPrompts(deprecated,被忽略)}。
     * 文件工具的 file_path 恒为绝对路径 + 平台分隔符（Windows 反斜杠，比较前需先归一化）。
     */
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    /** 工具调用标识 */
    @SerialName("tool_use_id") val toolUseId: String? = null,
    /** MCP 工具才带（v2.1.274+） */
    @SerialName("mcp_server") val mcpServer: McpServerInfo? = null,
)

/** PermissionRequest 输入：工具调用需要权限决定时触发。exit 2 无 decision 对象时不改变流程且 stderr 被丢弃；无提示会话无决策时自动 deny；沙箱网络请求不触发（用 Notification permission_prompt） */
@Serializable
data class PermissionRequestInput(
    /** 工具名 */
    @SerialName("tool_name") val toolName: String? = null,
    /** 工具入参（无 tool_use_id） */
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    /** MCP 工具才带（v2.1.274+） */
    @SerialName("mcp_server") val mcpServer: McpServerInfo? = null,
    /** 建议的权限更新（type 与 destination 见 [PermissionSuggestion]） */
    @SerialName("permission_suggestions") val permissionSuggestions: List<PermissionSuggestion>? = null,
)

/**
 * PostToolUse 输入：工具调用成功后触发。
 * 注意：R1a.2 此行只列了 tool_input + tool_response + duration_ms，未列 tool_name 与 mcp_server
 * （同类事件都列了）——按「文档没有的字段不加」处理，见汇报。
 */
@Serializable
data class PostToolUseInput(
    /** 工具入参 */
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    /**
     * 结构化输出，按工具不同（与发给模型的 tool_result 序列化内容可以不同）。Agent 工具前台完成时含
     * status("completed"/"async_launched")/agentId/content/resolvedModel/modelsUsed(v2.1.212+)/totalTokens/
     * totalDurationMs/totalToolUseCount/usage；后台启动只有 status/agentId/description/prompt/outputFile/resolvedModel。
     * bashEditDiffEnabled 时 Bash 工具有 tool_response.bashEditDiff{changedFiles,files,moreFiles,unavailable,skipped,shared}
     * （v2.1.269+，public beta）。
     */
    @SerialName("tool_response") val toolResponse: JsonElement? = null,
    /** 工具耗时 ms（不含权限提示与 PreToolUse 时间） */
    @SerialName("duration_ms") val durationMs: Long? = null,
)

/** PostToolUseFailure 输入：工具调用失败后触发。执行前被拒（未知工具/校验失败/权限拒绝）不触发 */
@Serializable
data class PostToolUseFailureInput(
    /** 工具名 */
    @SerialName("tool_name") val toolName: String? = null,
    /** 工具入参 */
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    /** 失败原因 */
    val error: String? = null,
    /** 是否被用户中断（可缺席） */
    @SerialName("is_interrupt") val isInterrupt: Boolean? = null,
    /** 耗时 ms（可缺席） */
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** MCP 工具才带（v2.1.274+） */
    @SerialName("mcp_server") val mcpServer: McpServerInfo? = null,
)

/**
 * PostToolBatch 输入：一批并行工具全部结算后、下一次模型调用前触发。
 * tool_calls 的每个元素调研文档未逐一列键（只说明其 tool_response 为发给模型的 tool_result 序列化内容，
 * 与 PostToolUse 的结构化 Output 不同），整条用 JsonElement 承载——见汇报。
 */
@Serializable
data class PostToolBatchInput(
    /** 批内每个调用的原始 JSON */
    @SerialName("tool_calls") val toolCalls: List<JsonElement>? = null,
)

/** PermissionDenied 输入：auto mode 拒绝工具调用时触发 */
@Serializable
data class PermissionDeniedInput(
    /** 工具名 */
    @SerialName("tool_name") val toolName: String? = null,
    /** 工具入参 */
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    /** 工具调用标识 */
    @SerialName("tool_use_id") val toolUseId: String? = null,
    /** 拒绝理由：`[规则名]`、"Auto mode could not evaluate this action and is blocking it for safety"、"Classifier unavailable" */
    val reason: String? = null,
    /** MCP 工具才带（v2.1.274+） */
    @SerialName("mcp_server") val mcpServer: McpServerInfo? = null,
)
