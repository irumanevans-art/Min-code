package dev.min.agent.ext.hooks

/** 引擎解读完输出之后、某事件按 CC 原文要丢掉的那一类字段。 */
internal enum class DiscardedField {
    /** 决定（顶层 decision / hookSpecificOutput 里的许可），丢掉后按 Neutral。 */
    Decision,
    /** 决定的理由文本。 */
    Reason,
    /** `continue:false` 与 `stopReason`：丢掉后这个 hook 停不住回合。 */
    Continue,
    SystemMessage,
    /** additionalContext，含 exit 2 的 stderr 转成的上下文。 */
    Context,
    UpdatedInput,
    /** 事件专属的 hookSpecificOutput 整体。 */
    Specific,
    /** 退出码 / 输出校验产生的非阻断错误（原文说「退出码被忽略」的事件）。 */
    NonBlockingError,
}

private val ALL_OUTPUT = DiscardedField.entries.toSet()

/** worktree 两个事件：原始退出码由生命周期处理，JSON 里的控制与通知一概不认，只留 specificOutput 里的路径。 */
private val WORKTREE_DISCARDS = setOf(DiscardedField.Decision, DiscardedField.Reason, DiscardedField.Continue,
    DiscardedField.Context, DiscardedField.UpdatedInput, DiscardedField.SystemMessage)

/**
 * 逐事件的「该丢弃的输出字段」表。**一事件一行、每行注明原文出处**，不要在 [interpret] 里为某个事件另写 if：
 * 原文改了，只改这张表。出处行号指 `research/glm/cache/cc/hooks.md`。
 *
 * 表里没有的事件 = 原文没说丢弃，输出按各自的决策控制处理。
 * Elicitation / ElicitationResult 原文也丢 systemMessage 与 continue（:3421、:3468），但它们的样本还挂着
 * 「等 MCP elicitation」，这里先不动，等那条线接上时一起加。
 */
internal val HOOK_OUTPUT_DISCARDS: Map<HookEvent, Set<DiscardedField>> = mapOf(
    // :1282 每个退出码都丢 JSON 输出字段（systemMessage / continue / additionalContext…），退出码与 stderr 也忽略（:882）
    HookEvent.Setup to ALL_OUTPUT,
    // :1321 无决策控制，JSON 输出字段丢弃；退出码忽略（:895）
    HookEvent.InstructionsLoaded to ALL_OUTPUT,
    // :1487 只认 displayContent（留在 specificOutput 里），不能阻断；systemMessage 与 continue 丢弃
    HookEvent.MessageDisplay to setOf(DiscardedField.Decision, DiscardedField.Reason, DiscardedField.Continue,
        DiscardedField.SystemMessage),
    // :2445 decision:block / exit 2 取消建任务（决定与理由保留）；continue:false 忽略，Claude 继续工作
    HookEvent.TaskCreated to setOf(DiscardedField.Continue),
    // :2627、:862 输出与退出码全部忽略（terminalSequence 除外——它不进裁决，引擎本来就不带）
    HookEvent.StopFailure to ALL_OUTPUT,
    // :2339 通知 hook 不能把会话停下，也不能给用户塞 systemMessage；terminalSequence 仍有效
    HookEvent.Notification to setOf(DiscardedField.Continue, DiscardedField.SystemMessage),
    // :2770 只认阻断决定；reason 接受但不展示、阻断不给人与模型任何消息（exit 2 的 stderr 也一样）；systemMessage / continue 丢弃
    HookEvent.ConfigChange to setOf(DiscardedField.Reason, DiscardedField.Continue, DiscardedField.SystemMessage),
    // :2848 不能阻断已完成的加目录，continue 丢弃；systemMessage 是否给 Claude 看取决于来源，由内核按 target 处理
    HookEvent.DirectoryAdded to setOf(DiscardedField.Continue),
    // :2805 CwdChanged 读 watchPaths 与 systemMessage（只通知人），丢弃 continue；无决策控制
    HookEvent.CwdChanged to setOf(DiscardedField.Decision, DiscardedField.Reason, DiscardedField.Continue,
        DiscardedField.Context, DiscardedField.UpdatedInput),
    // :2928 FileChanged 读 watchPaths 与 systemMessage，丢弃 continue；不能阻断
    HookEvent.FileChanged to setOf(DiscardedField.Continue),
    // :2938、:2998 只认成功与否和返回的路径；systemMessage 与 continue 丢弃
    HookEvent.WorktreeCreate to WORKTREE_DISCARDS,
    HookEvent.WorktreeRemove to WORKTREE_DISCARDS,
    // :3055 阻止压缩之外 systemMessage / continue 丢弃
    HookEvent.PreCompact to setOf(DiscardedField.Continue, DiscardedField.SystemMessage),
    // :3074 无决策控制，拦不住压缩结果；systemMessage / continue 丢弃
    HookEvent.PostCompact to setOf(DiscardedField.Decision, DiscardedField.Reason, DiscardedField.Continue,
        DiscardedField.SystemMessage),
    // :3338 无决策控制，JSON 输出字段全丢，只做清理（stderr 仍给用户看，所以错误保留）
    HookEvent.SessionEnd to (ALL_OUTPUT - DiscardedField.NonBlockingError),
)

/** 按 [HOOK_OUTPUT_DISCARDS] 丢字段；表里没有的事件原样返回。 */
internal fun HookVerdict.discardingFieldsOf(event: HookEvent): HookVerdict {
    val dropped = HOOK_OUTPUT_DISCARDS[event] ?: return this
    var v = this
    if (DiscardedField.Decision in dropped) v = v.copy(decision = HookDecision.Neutral)
    if (DiscardedField.Reason in dropped) v = v.copy(reason = null)
    if (DiscardedField.Continue in dropped) v = v.copy(shouldContinue = true, stopReason = null)
    if (DiscardedField.SystemMessage in dropped) v = v.copy(systemMessage = null)
    if (DiscardedField.Context in dropped) v = v.copy(additionalContext = emptyList())
    if (DiscardedField.UpdatedInput in dropped) v = v.copy(updatedInput = null)
    if (DiscardedField.Specific in dropped) v = v.copy(specificOutput = null)
    if (DiscardedField.NonBlockingError in dropped) v = v.copy(nonBlockingError = null)
    return v
}
