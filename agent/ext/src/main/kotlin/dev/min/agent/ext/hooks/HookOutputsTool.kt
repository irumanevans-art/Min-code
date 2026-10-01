package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 工具类事件的 hookSpecificOutput 形状（R1a.1 + R1a.2）：PreToolUse、PermissionRequest、PostToolUse、
 * PostToolUseFailure、PostToolBatch、PermissionDenied、WorktreeCreate。
 */

/** PreToolUse 的 hookSpecificOutput：工具调用执行前的许可决策。多 hook 优先级 deny>defer>ask>allow；exit 2 同 deny */
@Serializable
data class PreToolUseSpecificOutput(
    /** 应等于 "PreToolUse" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** allow / deny / ask / defer */
    @SerialName("permissionDecision") val permissionDecision: String? = null,
    /** deny 给 Claude，allow/ask 给用户，defer 忽略 */
    @SerialName("permissionDecisionReason") val permissionDecisionReason: String? = null,
    /** 整体替换工具入参；权限规则按新入参评估。AskUserQuestion/ExitPlanMode 的 allow 须配它 */
    @SerialName("updatedInput") val updatedInput: JsonObject? = null,
    /** 经 system reminder 注入工具结果旁的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
    // 旧格式：顶层 decision approve/block 已弃用，映射 allow/deny——字段在 [HookOutputCommon]，只转录不做转换。
    // defer 仅 -p 且单工具调用 turn（进程退出 stop_reason:"tool_deferred"，deferred_tool_use{id,name,input} 留在转录，
    // resume 后同 hook 再触发；工具消失则 tool_deferred_unavailable+is_error:true）。
    // _meta["anthropic/requiresUserInteraction"] 的 MCP 工具无法用 allow 跳过审批。
)

/** PermissionRequest 的 hookSpecificOutput：许可决定。deny/ask 规则仍生效 */
@Serializable
data class PermissionRequestSpecificOutput(
    /** 应等于 "PermissionRequest" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 决定对象；无决策时脚本不该写这个键 */
    val decision: PermissionRequestDecision? = null,
)

/** PermissionRequest 决定对象（hookSpecificOutput.decision）。exit 2 且无 decision 对象时不改变流程且 stderr 被丢弃；无提示会话无决策时自动 deny */
@Serializable
data class PermissionRequestDecision(
    /** allow / deny */
    val behavior: String? = null,
    /** allow 时整体替换工具入参（按新入参重评 deny/ask 规则） */
    @SerialName("updatedInput") val updatedInput: JsonObject? = null,
    /** allow 时回显 permission_suggestions 即可；条目负载文档未列，只转录 type/destination */
    @SerialName("updatedPermissions") val updatedPermissions: List<PermissionSuggestion>? = null,
    /** 决定说明 */
    val message: String? = null,
    /** deny 时为 true 则停下 Claude */
    val interrupt: Boolean? = null,
)

/** PostToolUse 的 hookSpecificOutput：顶层 decision:"block"+reason 在 [HookOutputCommon]；不改变已执行副作用，遥测记录原始输出 */
@Serializable
data class PostToolUseSpecificOutput(
    /** 应等于 "PostToolUse" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入工具结果旁的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
    /** 给 auto mode 分类器而非 Claude；单调用全部 hook 合计 ≤2,000 字符（v2.1.236+）；后台 hook 忽略 */
    @SerialName("classifierContext") val classifierContext: String? = null,
    /** 替换发给模型的工具输出；须匹配工具输出形状（如 Bash{stdout,stderr,interrupted,isImage}） */
    @SerialName("updatedToolOutput") val updatedToolOutput: JsonObject? = null,
    /** MCP 工具的输出替换（无 schema 校验） */
    @SerialName("updatedMCPToolOutput") val updatedMcpToolOutput: JsonObject? = null,
)

/** PostToolUseFailure 的 hookSpecificOutput：只有 additionalContext */
@Serializable
data class PostToolUseFailureSpecificOutput(
    /** 应等于 "PostToolUseFailure" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入工具结果旁的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)

/** PostToolBatch 的 hookSpecificOutput：additionalContext 在下一模型调用前注入一次 */
@Serializable
data class PostToolBatchSpecificOutput(
    /** 应等于 "PostToolBatch" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)

/** PermissionDenied 的 hookSpecificOutput：retry 允许模型重试（不改判拒）；分类器无结论时忽略 */
@Serializable
data class PermissionDeniedSpecificOutput(
    /** 应等于 "PermissionDenied" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** true 时允许模型重试 */
    val retry: Boolean? = null,
)

/** WorktreeCreate 的 hookSpecificOutput：返回 worktree 路径（仅 HTTP 型走这里；command 型在 stdout 打印路径，由执行逻辑处理）。相对路径按 hook 运行目录解析；拒绝含 . / .. 段的绝对路径与仓库根下经 symlink 的路径 */
@Serializable
data class WorktreeCreateSpecificOutput(
    /** 应等于 "WorktreeCreate" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 创建好的 worktree 路径；失败/无路径 = 创建失败 */
    @SerialName("worktreePath") val worktreePath: String? = null,
)
