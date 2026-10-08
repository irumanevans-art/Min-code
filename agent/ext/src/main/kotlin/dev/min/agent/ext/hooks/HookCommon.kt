package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Claude Code hooks 的 JSON 形状：脚本从 stdin 收一段 JSON、往 stdout 写一段 JSON，字段名与含义必须与
 * Claude Code 完全一致，否则用户的现成脚本就坏了。本包只照调研文档转录形状（research/inventory/
 * claude-code/02-hooks-settings-env-cli.md §R1a.0/§R1a.1）：输入类由 Min 构造，输出类从用户脚本宽松解析；
 * 执行、匹配、合并决策不在这里。
 *
 * 文档没写类型的字段按含义取最宽松的类型，枚举值一律收成 String（值集写在注释里）——输出侧是用户脚本
 * 写的 JSON，枚举反序列化遇到未预期的值会直接炸。文档里没有的字段一个都没有加。
 */

/**
 * 所有事件共有的 stdin 字段（R1a.0）。由 Min 构造，不反序列化第三方输入；可缺席的字段一律可空 + 默认 null。
 * 附带环境语义：hook 进程继承父环境，但 OTEL_* 导出变量被剥离（CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1 时再剥离更多）；
 * 没有 $CLAUDE_MODEL 环境变量。
 */
@Serializable
data class HookInputCommon(
    /** 当前会话标识 */
    @SerialName("session_id") val sessionId: String? = null,
    /** 当前 user prompt 的 UUID（OTel prompt.id 关联用）；首次用户输入前缺席；v2.1.196+ */
    @SerialName("prompt_id") val promptId: String? = null,
    /** 会话 JSONL 路径（异步写入，可能滞后；要最终助手文本用 Stop/SubagentStop 的 last_assistant_message） */
    @SerialName("transcript_path") val transcriptPath: String? = null,
    /** hook 调用时的工作目录 */
    val cwd: String? = null,
    /** 会话 scratchpad 目录路径（v2.1.257+；无 scratchpad 时缺席） */
    @SerialName("scratchpad_dir") val scratchpadDir: String? = null,
    /** default / plan / acceptEdits / auto / dontAsk / bypassPermissions（Manual 一直以 "default" 到达）；非所有事件都有 */
    @SerialName("permission_mode") val permissionMode: String? = null,
    /** 对象 {level}；工具使用类事件（PreToolUse/PostToolUse/Stop/SubagentStop 等）在模型支持时携带；同值经 $CLAUDE_EFFORT 环境变量给命令与 Bash 工具 */
    val effort: EffortInfo? = null,
    /** 事件名 */
    @SerialName("hook_event_name") val hookEventName: HookEvent? = null,
    /** `--agent` 或子 agent 内触发时的附加字段 */
    @SerialName("agent_id") val agentId: String? = null,
    /** 子 agent 的 type 优先于会话 `--agent` */
    @SerialName("agent_type") val agentType: String? = null,
)

/** hooks 事件的 effort 对象（R1a.0：effort{level}） */
@Serializable
data class EffortInfo(
    /** low / medium / high / xhigh / max */
    val level: String? = null,
)

/**
 * 所有事件共有的 stdout 字段（R1a.1）。从用户脚本宽松解析：未知字段忽略；文档的缺省值写在各字段注释里，
 * 由执行逻辑取用。additionalContext/systemMessage/initialUserMessage 与纯 stdout 各限 10,000 字符，
 * 超限落盘为会话目录文件并给前 2,000 字符预览。
 */
@Serializable
data class HookOutputCommon(
    /** false 时 Claude 在 hook 后完全停止；优先于事件级 decision。文档默认 true */
    @SerialName("continue") val shouldContinue: Boolean? = null,
    /** continue=false 时给用户的消息；留在对话里 Claude 可见 */
    val stopReason: String? = null,
    /** 无效果（接受但忽略；成功 hook 的 stdout 本就不进转录，只进 debug log）。文档默认 false */
    val suppressOutput: Boolean? = null,
    /** 给用户的警告；SDK/stream-json 下可为 SDKInformationalMessage */
    val systemMessage: String? = null,
    /** 代发的终端转义序列（限 OSC 0/1/2/9/99/777 与 BEL，ST/BEL 终止；-p/SDK 下忽略；Notification/StopFailure 等丢弃其他字段的事件也可用） */
    val terminalSequence: String? = null,
    /**
     * 部分事件的阻断/反馈格式（顶层，配合 [reason]）：多数事件取 "block"。
     * PreToolUse 的旧格式 approve/block 已弃用——映射到 hookSpecificOutput 的 allow/deny；这里只收字段不做转换。
     */
    val decision: String? = null,
    /** 顶层 [decision] 的理由 */
    val reason: String? = null,
    /** 事件富控制的载体，须含 hookEventName；各事件的形状见对应的 XxxSpecificOutput */
    val hookSpecificOutput: JsonElement? = null,
    val watchPaths: List<String>? = null,
)

/**
 * hooks 的 33 个事件（hooks.md 事件表 §1）。每条注释一句：何时触发、能不能阻断。
 */
@Serializable
enum class HookEvent {
    /** 会话开始或恢复时触发；stdout 可加入上下文，不阻断（source = startup/resume/clear/compact/fork） */
    @SerialName("SessionStart") SessionStart,

    /** `--init-only` 或 `-p` 模式的 `--init`/`--maintenance` 触发；CI/脚本一次性准备，不能阻断（所有 JSON 输出字段被丢弃） */
    @SerialName("Setup") Setup,

    /** 提交 prompt 后、Claude 处理前触发；exit 2 或 decision:"block" 阻断并抹掉 prompt，stdout 加入上下文 */
    @SerialName("UserPromptSubmit") UserPromptSubmit,

    /** 命令展开成 prompt 后、送达前触发；可阻断展开（decision:"block"），stdout 加入上下文 */
    @SerialName("UserPromptExpansion") UserPromptExpansion,

    /** 工具调用执行前触发；hookSpecificOutput.permissionDecision（allow/deny/ask/defer）+ updatedInput 可控制，exit 2 同 deny；超时不阻断（Agent SDK 回调 hook 超时阻断） */
    @SerialName("PreToolUse") PreToolUse,

    /** 工具调用需要权限决定时触发；exit 2 无效——须用 hookSpecificOutput.decision.behavior */
    @SerialName("PermissionRequest") PermissionRequest,

    /** auto mode 拒绝工具调用时触发；hookSpecificOutput.retry: true 允许模型重试（分类器无结论时忽略） */
    @SerialName("PermissionDenied") PermissionDenied,

    /** 工具调用成功后触发；顶层 decision:"block" 可反馈，但不撤销已执行的副作用 */
    @SerialName("PostToolUse") PostToolUse,

    /** 工具调用失败后触发；只有 additionalContext（执行前被拒的不触发） */
    @SerialName("PostToolUseFailure") PostToolUseFailure,

    /** 一批并行工具全部结算后、下一次模型调用前触发；只有 additionalContext */
    @SerialName("PostToolBatch") PostToolBatch,

    /** 发送通知时触发（matcher 匹配通知类型）；无阻断，systemMessage/continue 被丢弃，terminalSequence 有效 */
    @SerialName("Notification") Notification,

    /** 助手文本显示时触发；hookSpecificOutput.displayContent 替换屏幕显示（纯显示）；默认超时 10s */
    @SerialName("MessageDisplay") MessageDisplay,

    /** 子 agent 启动时触发；additionalContext 注入子 agent 上下文开头 */
    @SerialName("SubagentStart") SubagentStart,

    /** 子 agent 结束时触发；exit 2 阻止子 agent 停止（同 Stop：decision:"block"+reason） */
    @SerialName("SubagentStop") SubagentStop,

    /** TaskCreate 建任务时触发；exit 2 或 decision:"block" 取消建任务（continue:false 被忽略且 Claude 继续工作） */
    @SerialName("TaskCreated") TaskCreated,

    /** 任务标完成时触发；exit 2 或 continue:false 阻止完成（TaskUpdate 工具触发时 continue:false 被忽略） */
    @SerialName("TaskCompleted") TaskCompleted,

    /** Claude 完成回复时触发；exit 2 或 decision:"block" 阻止停止（连续 8 次续跑上限） */
    @SerialName("Stop") Stop,

    /** turn 因 API 错误结束时触发；输出与 exit code 全被丢弃（terminalSequence 除外） */
    @SerialName("StopFailure") StopFailure,

    /** 团队成员将空闲时触发；exit 2 或 continue:false 保持其工作 */
    @SerialName("TeammateIdle") TeammateIdle,

    /** CLAUDE.md 或 .claude/rules/ 下的 *.md 载入上下文时触发；无决策控制（审计用）。Claude 经 Project instructions 直读 AGENTS.md 时不触发 */
    @SerialName("InstructionsLoaded") InstructionsLoaded,

    /** 会话中配置文件变化时触发；exit 2 或 decision:"block" 阻止变更生效（policy_settings 不可阻断） */
    @SerialName("ConfigChange") ConfigChange,

    /** 工作目录变化（cd）时触发；无决策控制（读 watchPaths+systemMessage、丢弃 continue） */
    @SerialName("CwdChanged") CwdChanged,

    /** 会话中加目录（/add-dir、SDK register_repo_root）时触发；无阻断，后台运行（600s 默认超时） */
    @SerialName("DirectoryAdded") DirectoryAdded,

    /** 被监视文件变化时触发（matcher 指定文件名）；无阻断（文件系统 watcher，Edit/Write/Bash/外部进程都触发） */
    @SerialName("FileChanged") FileChanged,

    /** worktree 创建时触发；非零退出中止创建；command 型在 stdout 打印路径、HTTP 型用 hookSpecificOutput.worktreePath 返回 */
    @SerialName("WorktreeCreate") WorktreeCreate,

    /** worktree 移除时触发；exit 非零且目录仍在 = 移除失败；JSON 输出被丢弃 */
    @SerialName("WorktreeRemove") WorktreeRemove,

    /** 压缩前触发；exit 2 或 decision:"block" 阻止压缩（manual 的 stderr 显示给用户） */
    @SerialName("PreCompact") PreCompact,

    /** 压缩后触发；无阻断（systemMessage/continue 被丢弃） */
    @SerialName("PostCompact") PostCompact,

    /** 模型切换前触发；可阻断（exit 2 / decision:"block" / hookSpecificOutput.permissionDecision） */
    @SerialName("PreModelSwitch") PreModelSwitch,

    /** 模型切换后触发；additionalContext 随切换后下一请求带上；无阻断 */
    @SerialName("PostModelSwitch") PostModelSwitch,

    /** MCP 请求用户输入时触发；hookSpecificOutput.action+content 可程序化应答；exit 2 阻断但 hookSpecificOutput 被忽略 */
    @SerialName("Elicitation") Elicitation,

    /** 用户答复 MCP 请求后触发；hookSpecificOutput.action+content 覆写答复（发回 MCP 服务器前）；exit 2 改为 decline */
    @SerialName("ElicitationResult") ElicitationResult,

    /** 会话终止时触发；无阻断；SessionEnd 类 hook 共享 1.5 秒预算（CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS 可升至 60s） */
    @SerialName("SessionEnd") SessionEnd,
}
