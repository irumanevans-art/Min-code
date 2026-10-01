package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 会话生命周期类事件的 hookSpecificOutput 形状（R1a.1 决策模式速查 + R1a.2 决策列）：
 * SessionStart、SubagentStart、SubagentStop、Stop。hookEventName 从用户脚本原样收成 String，
 * 不校验（校验与合并是执行逻辑的事）。
 */

/** SessionStart 的 hookSpecificOutput：additionalContext 注入对话开头（resume 时对中途事件重放已存文本；以 source=resume/fork 重跑可刷新）。仅支持 command 与 mcp_tool 类型 */
@Serializable
data class SessionStartSpecificOutput(
    /** 应等于 "SessionStart" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入对话开头的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
    /** -p 模式下用它造首个 turn */
    @SerialName("initialUserMessage") val initialUserMessage: String? = null,
    /** 会话标题（clear/compact 忽略） */
    @SerialName("sessionTitle") val sessionTitle: String? = null,
    /** 要监视的路径列表 */
    @SerialName("watchPaths") val watchPaths: List<String>? = null,
    /** 重扫 skills 目录 */
    @SerialName("reloadSkills") val reloadSkills: Boolean? = null,
    // 另可用 CLAUDE_ENV_FILE 文件持久化环境变量——文件机制，不是本类字段。
)

/** SubagentStart 的 hookSpecificOutput：additionalContext 注入子 agent 上下文开头；同 subagent 重跑时不重复注入 */
@Serializable
data class SubagentStartSpecificOutput(
    /** 应等于 "SubagentStart" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)

/** SubagentStop 的 hookSpecificOutput：additionalContext 作非错误反馈（顶层 decision:"block"+reason 在 [HookOutputCommon]） */
@Serializable
data class SubagentStopSpecificOutput(
    /** 应等于 "SubagentStop" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)

/** Stop 的 hookSpecificOutput：additionalContext 作非错误反馈，转录标 "Stop hook feedback"；顶层 decision:"block"+reason 在 [HookOutputCommon]；连续 8 次续跑上限（CLAUDE_CODE_STOP_HOOK_BLOCK_CAP 可调） */
@Serializable
data class StopSpecificOutput(
    /** 应等于 "Stop" */
    @SerialName("hookEventName") val hookEventName: String? = null,
    /** 经 system reminder 注入的上下文 */
    @SerialName("additionalContext") val additionalContext: String? = null,
)
