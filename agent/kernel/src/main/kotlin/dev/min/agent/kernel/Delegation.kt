package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ForkSeeded
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.MemoryAttached
import dev.min.agent.spi.MaskedOutput
import dev.min.agent.spi.SeedCompaction
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TaskUpdated
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.Role
import dev.min.agent.spi.SeedMessage
import dev.min.agent.spi.SeedResult
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome

/**
 * 一类子 agent 的定义。内置三类在 [BuiltinAgents]；`.claude/agents/` 下的自定义由组合根从 `:agent:ext` 的 AgentDef 转过来。
 * 字段语义照 Claude Code 的 frontmatter（docs/agent/detail/子agent设计.md §4）。
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    /** 角色说明，拼在子 agent 系统提示末尾 */
    val instructions: String,
    /** 能用的工具；null = 子 agent 可用的全部 */
    val tools: List<String>? = null,
    /** 先于 [tools] 应用的排除名单 */
    val disallowedTools: List<String> = emptyList(),
    /** null 或 `inherit` = 父会话的模型 */
    val model: String? = null,
    /** null = 继承父会话的模式 */
    val permissionMode: PermissionMode? = null,
    val maxTurns: Int? = null,
    val effort: Effort? = null,
    /** 不加载 CLAUDE.md 一类的记忆文件（Explore / Plan、定义里的 omitClaudeMd） */
    val omitMemory: Boolean = false,
    /** 定义要求总在后台跑 */
    val alwaysBackground: Boolean = false,
    /**
     * 只读的内置类型（Explore / Plan）：模式固定为 dontAsk、工具里没有写类工具。
     * 比 Claude Code 严一点：父会话是 bypass 也不放开——它们按定义就不该改东西
     */
    val readOnly: Boolean = false,
    /**
     * frontmatter 的 `hooks:` 原文（CC 的 Hooks in skills and agents）：只在**这个子 agent 运行期间**生效——
     * 写进子会话自己的日志，会话结束即出局，不进父会话。子会话再派生时经 inheritedComponentHooks
     * 把登记时刻的快照传给孙会话（M8）。其中的 `Stop` 在登记时改写成 `SubagentStop`
     * （CC：子会话里触发的是它自己的完成事件）。[hooksLayer] 是定义来自的层，项目层要过信任
     */
    val hooks: kotlinx.serialization.json.JsonObject? = null,
    val hooksLayer: String? = null,
    val isolationWorktree: Boolean = false,
    val source: String = "builtin",
)

object BuiltinAgents {
    const val GENERAL_PURPOSE = "general-purpose"

    private val READ_ONLY_TOOLS = listOf("Read", "Grep", "Glob", "Bash")

    val all: List<AgentDefinition> = listOf(
        AgentDefinition(
            name = GENERAL_PURPOSE,
            description = "通用：多步骤的复杂任务、需要改文件或跑命令的独立工作。",
            instructions = "Work through the task end to end. Use any tool you need.",
        ),
        AgentDefinition(
            name = "Explore",
            description = "只读探索：在代码库里找文件、找定义、回答「X 在哪 / 怎么实现的」。说明要多彻底（quick / medium / very thorough）。",
            instructions = """
                You explore codebases read-only. Search broadly first (Glob, Grep), then read only the parts that matter.
                Never modify anything. Report concrete findings with file paths and line numbers; say what you could not find.
            """.trimIndent(),
            tools = READ_ONLY_TOOLS,
            permissionMode = PermissionMode.DontAsk,
            omitMemory = true,
            readOnly = true,
        ),
        AgentDefinition(
            name = "Plan",
            description = "只读调研并给出实施计划：读相关代码、权衡做法，返回分步计划与关键文件。",
            instructions = """
                You research read-only and produce an implementation plan. Read the relevant code, weigh the options,
                and return a step-by-step plan that names the files to change and the risks. Never modify anything.
            """.trimIndent(),
            tools = READ_ONLY_TOOLS,
            permissionMode = PermissionMode.DontAsk,
            omitMemory = true,
            readOnly = true,
        ),
    )
}

/** 分身被委派时，那次 Agent 调用的结果：分身就是从这里开始干活的 */
internal fun forkDirective(prompt: String): String = """
    You are a fork of the conversation above: a copy of the agent that made this Agent call, now running on your own in the
    background. The original agent continues separately and will receive your final reply as your report.
    Do the task below directly with your tools. Do not delegate it again (the Agent tool will refuse). Make your final reply a
    complete, self-contained report.

    Task:
""".trimIndent() + "\n" + prompt

internal const val SIBLING_CALL_RESULT = "This call is handled by the original agent; its result is not visible to you."

/**
 * 分身继承的对话：父会话到 [callId] 所在那条助手消息为止（含），以及这些消息里每个调用的结果。
 * 更早的调用用父会话的真实结果；那条消息里的调用用合成结果（设计 §3.1）。消息与结果逐字节照抄，前缀才能与父会话相同。
 * 父会话压缩过时：继承从摘要边界开始，压缩视图（摘要 + 挂在它末尾的 SessionStart(compact) 上下文）放进
 * [ForkSeeded.compaction]——分身回放被摘要掉的原始对话反而会让前缀与父会话对不上。
 */
internal fun forkSeed(parent: SessionState, callId: CallId, prompt: String): ForkSeeded {
    val at = parent.history.indexOfLast { it is HistoryEntry.Assistant && it.toolCalls.any { c -> c.callId == callId } }
    check(at >= 0) { "父会话里找不到发起委派的调用 $callId" }
    val inherited = parent.history.subList(parent.forkBoundary(at), at + 1)
    val messages = inherited.map { entry ->
        when (entry) {
            is HistoryEntry.User -> SeedMessage(entry.messageId, Role.User, entry.parts, entry.origin)
            is HistoryEntry.Assistant -> SeedMessage(entry.messageId, Role.Assistant, entry.parts)
        }
    }
    val delegating = inherited.last() as HistoryEntry.Assistant
    val results = inherited.filterIsInstance<HistoryEntry.Assistant>().flatMap { entry ->
        entry.toolCalls.map { call ->
            val result = when {
                entry !== delegating -> parent.toolResults[call.callId] ?: error("调用 ${call.callId} 还没有结果，不能分身")
                call.callId == callId -> ToolResult(listOf(TextPart(forkDirective(prompt))))
                else -> ToolResult(listOf(TextPart(SIBLING_CALL_RESULT)))
            }
            SeedResult(call.callId, result)
        }
    }
    val view = parent.compaction
    val seed = view?.let { c ->
        SeedCompaction(c.compactionId, c.summary, c.through, c.transcriptPath, parent.compactionContexts[c.compactionId].orEmpty())
    }
    // 委派消息里的调用在分身里都是合成结果，不能用父会话的遮蔽盖掉；摘要边界之前的调用也不继承。
    val masked = inherited.dropLast(1).filterIsInstance<HistoryEntry.Assistant>().flatMap { entry ->
        entry.toolCalls.filter { it.callId in parent.masked }.map { MaskedOutput(it.callId, parent.masked[it.callId]) }
    }
    return ForkSeeded(messages, results, seed, masked)
}

/** 分身继承的起点：父会话压缩过就是摘要边界（含）之后，否则从头。挂在被摘要掉消息上的东西一并出局 */
internal fun SessionState.forkBoundary(at: Int): Int = compaction
    ?.let { c -> history.indexOfFirst { it.messageId == c.through } }
    ?.takeIf { it >= 0 }?.plus(1)
    ?: 0

/**
 * 分身除了对话文字，还要接着父会话里**模型已经看得见**的几样状态，紧跟 [forkSeed] 写入：
 * - hook 加的上下文（挂在继承来的消息上的 system-reminder）：没有它们，分身的请求前缀就与父会话不再逐字相同；
 *   发起委派的那条助手消息除外——它的工具结果在分身里换成了指令，挂在后面的上下文属于父会话那边的兄弟调用。
 *   父会话压缩过时只继承摘要边界之后的：被摘要掉的消息不在分身日志里，挂在上面的上下文也已不在父会话视图里；
 *   摘要末尾的 SessionStart(compact) 上下文不在这里复制——它随 [ForkSeeded.compaction] 的种子进分身。
 * - 按需加载的记忆（MemoryAttached）：与 hook 上下文同理，渲染在继承来的助手消息的工具结果之后，缺了前缀就对不上；同样不含发起委派的那条消息。
 *   分身的「已加载记忆路径」由折叠从这些事件里重新算出。
 * - 任务清单：继承的对话里有 TaskCreate 的结果，分身拿着同样的编号去 TaskUpdate 就该找得到。给的是**副本**：
 *   用已有的任务事件重放出同样的编号、状态与依赖（删过的编号先建后删，之后不会复用），分身改的只是自己那份。
 */
internal fun forkInheritance(parent: SessionState, callId: CallId): List<SessionEvent> {
    val at = parent.history.indexOfLast { it is HistoryEntry.Assistant && it.toolCalls.any { c -> c.callId == callId } }
    // 与 forkSeed 同一条切线：被摘要掉的原始消息不进分身日志，挂在它们上的 hook 上下文与按需记忆同样不继承
    // （它们已经不在父会话的模型视图里）；Compaction 目标上的上下文不走这里，随 ForkSeeded 的压缩种子进分身
    val inherited = parent.history.subList(parent.forkBoundary(at), at + 1)
    val contexts = inherited.flatMap { entry ->
        val texts = parent.hookContexts[entry.messageId].orEmpty()
        when {
            texts.isEmpty() -> emptyList()
            entry is HistoryEntry.User -> texts.map { HookContextAdded(HookContextTarget.Message(entry.messageId), FORK_INHERITED, it) }
            entry === inherited.last() -> emptyList()
            else -> texts.map { HookContextAdded(HookContextTarget.ToolResults(entry.messageId), FORK_INHERITED, it) }
        }
    }
    // 按需加载的记忆同理（它们渲染在对应助手消息的工具结果之后）；发起委派的那条消息上的除外。
    // 分身的「已在上下文里的记忆路径」随这些事件在折叠里重新算出，不另外传
    val attached = inherited.dropLast(1).mapNotNull { entry ->
        val files = parent.memoryAttachments[entry.messageId].orEmpty()
        val covered = parent.memoryCovered[entry.messageId].orEmpty()
        if (files.isEmpty() && covered.isEmpty()) null else MemoryAttached(entry.messageId, files, covered = covered)
    }
    val live = parent.tasks.associateBy { it.id }
    val ids = (1..parent.taskSeq).map { it.toString() }
    val created = ids.map { id ->
        val t = live[id]
        if (t == null) TaskCreated(id, "（已删除）", "") else TaskCreated(id, t.subject, t.description, t.activeForm, t.metadata)
    }
    val deleted = ids.filter { it !in live }.map { TaskDeleted(it) }
    val updated = parent.tasks.mapNotNull { t ->
        val patch = TaskPatch(
            status = t.status.takeIf { it != TaskStatus.Pending },
            owner = t.owner,
            addBlocks = t.blocks,
        )
        if (patch.isEmpty) null else TaskUpdated(t.id, patch)
    }
    return contexts + attached + created + deleted + updated
}

/** 分身继承来的 hook 上下文在日志里记的「来源事件」 */
private const val FORK_INHERITED = "inherited"

/**
 * 交给父会话的报告（设计 §6）。正文里出现的报告标签一律转义：子 agent（或它读到的网页、文件）不能伪造一段「报告之外的话」。
 */
internal object Reports {
    private const val TAG = "subagent-report"

    fun render(child: SessionId, summary: ChildSummary?, agentType: String, fork: Boolean, ended: EndedTurn, state: SessionState): String {
        val reviewed = state.subagentReport?.takeIf { it.turnId == ended.turnId }
        // 中断、请求上限或异常可能跳过正常收尾；auto 下不能因此泄漏未经审查的最后消息。
        val withheld = state.autoReportReviewRequired() && reviewed == null || reviewed?.review == "withheld"
        val body = (reviewed?.body ?: if (withheld) "[子 agent 未完成报告审查，正文未交付。]" else finalText(state)).ifBlank { "（子 agent 没有给出文字报告）" }
        val status = if (withheld) "failed" else statusOf(ended.outcome)
        val tokens = ended.usage.let { it.inputTokens + it.outputTokens + it.cacheReadTokens + it.cacheWriteTokens }
        val name = summary?.name?.let { " name=\"${attr(it)}\"" }.orEmpty()
        return buildString {
            append("<$TAG id=\"${child.value}\" type=\"${attr(agentType)}\"$name fork=\"$fork\" status=\"$status\"")
            reviewed?.review?.let { append(" review=\"${attr(it)}\" handback=\"${reviewed.handback}\"") }
            append(" tools=\"${ended.toolCalls}\" tokens=\"$tokens\" duration-ms=\"${ended.durationMs}\">\n")
            append(escape(body.trim()))
            state.worktree?.let { w ->
                append("\n\nWorktree: ").append(escape(w.path.value)).append("; branch: ").append(escape(w.branch ?: "hook-managed"))
                append("; ").append(if (state.finishedWorktrees.lastOrNull()?.removed == true) "removed (no changes)" else "retained")
            }
            // 供应商错误也可能回显原请求；固定的 headless 文案可交付，其他 Auto 错误详情不旁路终报审查。
            val extra = if (state.autoReportReviewRequired() && ended.outcome is TurnOutcome.Failed &&
                ended.outcome.message !in setOf(HEADLESS_CLASSIFIER_SAFETY_ERROR, HEADLESS_CLASSIFIER_CONTEXT_ERROR))
                "[子 agent 失败，未经审查的错误详情未交付。]" else note(ended.outcome)
            extra?.let { append("\n\n").append(it) }
            append("\n</$TAG>")
        }
    }

    fun isError(outcome: TurnOutcome, state: SessionState? = null): Boolean = outcome !is TurnOutcome.Completed ||
        state?.let { s ->
            val reviewed = s.subagentReport?.takeIf { it.turnId == s.lastTurn?.turnId }
            s.autoReportReviewRequired() && (reviewed == null || reviewed.review == "withheld")
        } == true

    /** 子回合的结论：最后一条助手消息的正文 */
    private fun finalText(state: SessionState): String =
        state.history.lastOrNull { it is HistoryEntry.Assistant }
            ?.let { (it as HistoryEntry.Assistant).parts.filterIsInstance<TextPart>().joinToString("") { p -> p.text } }
            .orEmpty()

    private fun statusOf(outcome: TurnOutcome): String = when (outcome) {
        TurnOutcome.Completed -> "completed"
        is TurnOutcome.Failed -> "failed"
        is TurnOutcome.Interrupted -> when (outcome.by) {
            InterruptedBy.User -> "interrupted"
            InterruptedBy.ProcessDeath -> "interrupted-by-restart"
            InterruptedBy.Limit -> "turn-limit"
            InterruptedBy.Hook -> "stopped-by-hook"
            InterruptedBy.Budget -> "stopped-by-budget"
        }
    }

    private fun note(outcome: TurnOutcome): String? = when (outcome) {
        TurnOutcome.Completed -> null
        is TurnOutcome.Failed -> "[子 agent 失败：${escape(outcome.message)}]"
        is TurnOutcome.Interrupted -> "[子 agent 没有做完。需要的话用 SendMessage 让它继续。]"
    }

    private fun attr(text: String) = text.replace("\"", "'").replace("<", "‹").replace(">", "›")

    /** 只转义本系统的标签，其余原样：报告里的代码、HTML 不该被改写 */
    fun escape(text: String): String =
        text.replace("<$TAG", "&lt;$TAG").replace("</$TAG", "&lt;/$TAG")
}
