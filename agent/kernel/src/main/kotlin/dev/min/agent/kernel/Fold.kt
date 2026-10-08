package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.ForkSeeded
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputBlocked
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.ToolInputRewritten
import dev.min.agent.spi.ToolOutputsMasked
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryAttached
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.QuestionAnswered
import dev.min.agent.spi.QuestionAsked
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskUpdated
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger

/**
 * 日志 → 状态。纯函数：同一段日志永远折叠出同一个状态，这是恢复测试和确定性回归的基础。
 *
 * 严格：事件顺序不合法（例如给不存在的调用写结果）直接抛 [IllegalStateException]。
 * 日志只由内核自己写，顺序不对说明内核有 bug，宁可大声失败，也不要带着错的状态继续跑。
 */
fun fold(header: LogHeader, records: List<EventRecord>): SessionState {
    val first = records.firstOrNull()?.event
    check(first is SessionCreated) { "日志第一条事件必须是 SessionCreated，实际是 $first" }
    val initial = SessionState(
        header = header,
        created = first,
        lastSeq = 0,
        settings = SessionSettings(first.cwd, first.model, first.mode, first.effort),
        inbox = emptyList(),
        seenInputs = emptySet(),
        history = emptyList(),
        toolResults = emptyMap(),
        turn = null,
        usage = Usage(),
    )
    return records.fold(initial) { state, record -> state.apply(record) }
}

fun SessionState.apply(record: EventRecord): SessionState {
    check(record.seq == lastSeq + 1) { "seq 不连续：当前 $lastSeq，收到 ${record.seq}" }
    val next = when (val e = record.event) {
        is SessionCreated -> {
            check(lastSeq == 0L) { "SessionCreated 只能是第一条事件" }
            this
        }
        is dev.min.agent.spi.SessionRenamed -> copy(name = e.name)
        is dev.min.agent.spi.AutoReviewSettingsLoaded -> copy(autoReviewSettings = e)
        is dev.min.agent.spi.AutoReviewed -> updateTurn(e.turnId) { it.copy(usage = it.usage + e.usage) }.copy(
            presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
        )
        is dev.min.agent.spi.ModelCharged -> {
            val amount = e.amount
            val currency = e.currency
            if (amount == null || currency == null) copy(unpricedModelCalls = unpricedModelCalls + 1)
            else copy(modelCosts = modelCosts + (currency to modelCosts.getOrDefault(currency, 0.0) + amount))
        }
        is dev.min.agent.spi.CwdChanged -> {
            check(settings.cwd == e.oldCwd) { "cwd_changed 的原目录不匹配" }
            val reminder = listOf("Working directory changed from ${e.oldCwd} to ${e.cwd}. Resolve subsequent paths and commands against the new directory.") + e.memoryFiles.map(dev.min.agent.context.MemoryRender::section)
            // 工具中切目录时，下一次模型请求就是当前工具结果之后；空闲切换才等下一条输入。
            val anchor = if (turn != null) history.lastOrNull { it is HistoryEntry.Assistant }?.messageId else null
            copy(
                settings = settings.copy(cwd = e.cwd), worktree = e.worktree,
                cwdMemory = (cwdMemory + e.memoryFiles).distinctBy { it.path },
                rules = e.rules ?: rules,
                additionalDirectories = e.additionalDirectories ?: additionalDirectories,
                pendingInputContexts = if (anchor == null) pendingInputContexts + reminder else pendingInputContexts,
                hookContexts = if (anchor != null) hookContexts + (anchor to hookContexts[anchor].orEmpty() + reminder) else hookContexts,
                memoryPathsInContext = memoryPathsInContext + e.memoryFiles.map { it.path },
            )
        }
        is dev.min.agent.spi.WorktreeFinished -> copy(finishedWorktrees = finishedWorktrees + e)
        is dev.min.agent.spi.CwdWatchPathsChanged -> copy(cwdWatchPaths = e.paths)
        // 取最后一条：以后显式刷新记忆时再追加一条，整份替换前一份快照
        // 快照里的路径换成新快照的；按需加载过的路径原样保留
        is MemoryLoaded -> copy(
            memory = e.files,
            autoMemory = e.autoMemory,
            memoryPathsInContext = memoryPathsInContext - memory.map { it.path }.toSet() + e.files.map { it.path },
        )
        is MemoryAttached -> {
            check(history.any { it.messageId == e.assistantMessageId && it is HistoryEntry.Assistant }) {
                "按需记忆挂靠的助手消息 ${e.assistantMessageId} 不存在"
            }
            copy(
                memoryAttachments = memoryAttachments + (e.assistantMessageId to memoryAttachments[e.assistantMessageId].orEmpty() + e.files),
                memoryCovered = if (e.covered.isEmpty()) memoryCovered else
                    memoryCovered + (e.assistantMessageId to memoryCovered[e.assistantMessageId].orEmpty() + e.covered),
                memoryPathsInContext = memoryPathsInContext + e.files.map { it.path } + e.covered,
            )
        }
        // 同记忆：取最后一条，以后显式刷新时整份替换
        is SkillsLoaded -> copy(skills = e)
        is dev.min.agent.spi.OutputStyleLoaded -> copy(outputStyle = e)
        is dev.min.agent.spi.StructuredOutputDelivered -> copy(structuredOutput = e)
        is dev.min.agent.spi.SessionBranched -> copy(branched = e)
        is SettingsChanged -> copy(
            settings = settings.copy(
                cwd = e.cwd ?: settings.cwd,
                model = e.model ?: settings.model,
                mode = e.mode ?: settings.mode,
                effort = e.effort ?: if (e.clearEffort) null else settings.effort,
                modeBeforePlan = settings.modeBeforePlanAfter(e.mode),
            ),
        )
        is InputReceived -> if (fromRewoundChild(e.origin)) {
            // 已回退掉的子 agent 送来的报告：记下见过（重发只收一次），但不进收件箱（检查点与rewind设计 §4.4）
            check(e.clientMsgId !in seenInputs) { "重复的输入 ${e.clientMsgId}" }
            copy(seenInputs = seenInputs + e.clientMsgId)
        } else {
            check(e.clientMsgId !in seenInputs) { "重复的输入 ${e.clientMsgId}" }
            // 只有 Stop / SubagentStop hook 会送来 hook 来源的输入：每条算一次「拦下结束」
            val stopHook = e.origin is InputOrigin.Hook
            copy(
                inbox = inbox + PendingInput(e.clientMsgId, e.parts, e.delivery, e.origin),
                seenInputs = seenInputs + e.clientMsgId,
                children = reported(e.origin),
                turn = if (stopHook && turn != null) turn.copy(stopHookBlocks = turn.stopHookBlocks + 1) else turn,
            )
        }
        is InputPromoted -> {
            val input = inbox.firstOrNull { it.clientMsgId == e.clientMsgId }
                ?: error("提升了不在收件箱里的输入 ${e.clientMsgId}")
            // 回退点记的是这条消息落盘之前的对话状态，所以先记点、再提升
            withRewindPoint(e.messageId, fromUser = input.origin == InputOrigin.User, seq = record.seq).copy(
                inbox = inbox - input,
                history = history + HistoryEntry.User(e.messageId, input.parts, input.origin),
                // hook 上下文跟着提升的消息走：SubagentStart 等着的挂在这条输入上（不区分来源），
                // SessionStart 等着的只跟人发的消息走
                hookContexts = hookContexts + (
                    e.messageId to hookContexts[e.messageId].orEmpty() + pendingInputContexts +
                        (if (input.origin == InputOrigin.User) pendingHookContexts else emptyList())
                    ),
                pendingInputContexts = emptyList(),
                pendingHookContexts = if (input.origin == InputOrigin.User) emptyList() else pendingHookContexts,
            )
        }
        is InputWithdrawn -> copy(inbox = inbox.filterNot { it.clientMsgId == e.clientMsgId })
        is TurnStarted -> {
            check(turn == null) { "上一回合 ${turn?.turnId} 还没结束就开始了 ${e.turnId}" }
            val started = copy(turn = TurnState(e.turnId, e.trigger, record.atMs, openRequest = null, calls = emptyMap(), usage = Usage()))
            // 续跑、被子 agent 报告或后台 asyncRewake hook 唤醒的回合在转录里留一道分隔；用户发起的回合由用户消息本身标示
            if (e.trigger == TurnTrigger.Resume || e.trigger == TurnTrigger.Notification || e.trigger == TurnTrigger.Hook || e.trigger == TurnTrigger.Cron) {
                started.copy(presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId))
            } else started
        }
        is ModelRequestStarted -> updateTurn(e.turnId) {
            it.copy(openRequest = OpenRequest(e.requestId, e.attempt, messageId = null, segments = emptyList()))
        }
        is AssistantSegment -> updateTurn(e.turnId) { t ->
            val open = checkNotNull(t.openRequest) { "没有进行中的请求却收到了片段" }
            check(open.requestId == e.requestId) { "片段属于 ${e.requestId}，当前请求是 ${open.requestId}" }
            t.copy(openRequest = open.copy(messageId = e.messageId, segments = open.segments + e.parts))
        }
        is AssistantMessageCompleted -> updateTurn(e.turnId) { t ->
            check(t.openRequest?.requestId == e.requestId) { "完成的是 ${e.requestId}，当前请求是 ${t.openRequest?.requestId}" }
            t.copy(openRequest = null, usage = t.usage + e.usage, modelReplied = true)
        }.copy(history = history + HistoryEntry.Assistant(e.messageId, e.parts), lastRequestUsage = e.usage)
        is ModelRequestFailed -> updateTurn(e.turnId) { t ->
            check(t.openRequest?.requestId == e.requestId) { "失败的是 ${e.requestId}，当前请求是 ${t.openRequest?.requestId}" }
            t.copy(openRequest = null)
        }
        is ModelFallback -> updateTurn(e.turnId) { t ->
            check(t.openRequest == null) { "模型回退发生在请求失败之后，当前仍有进行中的请求 ${t.openRequest?.requestId}" }
            val failed = t.fallback?.model ?: settings.model
            check(failed == e.from) { "回退的起点 ${e.from} 与失败的模型 $failed 对不上" }
            t.copy(fallback = FallbackInUse(settings.model, e.to, e.reason, e.message))
        }.copy(presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId))
        is ToolPlanned -> updateTurn(e.turnId) { t ->
            check(e.callId !in t.calls) { "重复规划调用 ${e.callId}" }
            t.copy(calls = t.calls + (e.callId to CallState(e.callId, e.tool, e.input, e.resumePolicy, CallPhase.Planned)))
        }
        is ApprovalRequested -> updateCall(e.turnId, e.callId) { phase ->
            check(phase == CallPhase.Planned) { "只有刚规划的调用才能请求审批，${e.callId} 处于 $phase" }
            CallPhase.AwaitingApproval(e.approvalId, e.ask, record.atMs, e.suggestedRule, e.floorReason)
        }
        is ApprovalResolved -> {
            val t = checkNotNull(turn) { "没有进行中的回合却收到审批结果 ${e.approvalId}" }
            val call = t.calls.values.firstOrNull { (it.phase as? CallPhase.AwaitingApproval)?.approvalId == e.approvalId }
                ?: error("没有在等 ${e.approvalId} 的调用")
            val resolved = when (val choice = e.choice) {
                is ApprovalChoice.Deny -> CallPhase.Denied(choice.note, choice.fromHost)
                ApprovalChoice.Once, is ApprovalChoice.Always -> CallPhase.Approved
            }
            val always = (e.choice as? ApprovalChoice.Always)?.rule
            updateCall(t.turnId, call.callId) { resolved }.let { s ->
                if (always == null) s
                else s.copy(sessionRules = s.sessionRules + RuleEntry(RuleKindData.Allow, always, SESSION_RULE_SOURCE, s.settings.cwd))
            }
        }
        is PermissionRulesLoaded -> copy(rules = e.rules, additionalDirectories = e.additionalDirectories)
        is HooksLoaded -> copy(hooks = e.sources, hooksHttpPolicy = e.httpPolicy)
        is dev.min.agent.spi.ComponentHooksRegistered -> {
            // 同名组件重复登记（模型又调了一次同一个 skill）不叠加：处理器翻倍等于同一个门禁跑两遍
            val exists = componentHooks.any { it.kind == e.kind && it.name == e.name }
            copy(componentHooks = if (exists) componentHooks.map { if (it.kind == e.kind && it.name == e.name) e else it } else componentHooks + e)
        }
        is dev.min.agent.spi.ComponentHooksUpdated -> copy(
            componentHooks = componentHooks.map { c ->
                if (c.kind == e.kind && c.name == e.name) c.copy(hooks = e.hooks) else c
            }.filterNot { it.kind == e.kind && it.name == e.name && it.hooks.isEmpty() },
        )
        is HooksRan -> copy(presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId))
        is HookContextAdded -> when (val target = e.target) {
            HookContextTarget.NextUserMessage -> copy(pendingHookContexts = pendingHookContexts + e.text)
            HookContextTarget.NextMessage -> copy(pendingInputContexts = pendingInputContexts + e.text)
            is HookContextTarget.Message -> {
                check(history.any { it.messageId == target.messageId && it is HistoryEntry.User }) { "hook 上下文挂靠的用户消息 ${target.messageId} 不存在" }
                copy(hookContexts = hookContexts + (target.messageId to hookContexts[target.messageId].orEmpty() + e.text))
            }
            is HookContextTarget.ToolResults -> {
                check(history.any { it.messageId == target.assistantMessageId && it is HistoryEntry.Assistant }) {
                    "hook 上下文挂靠的助手消息 ${target.assistantMessageId} 不存在"
                }
                copy(hookContexts = hookContexts + (target.assistantMessageId to hookContexts[target.assistantMessageId].orEmpty() + e.text))
            }
            is HookContextTarget.Compaction -> {
                check(compaction?.compactionId == target.compactionId) {
                    "hook 上下文挂靠的压缩 ${target.compactionId} 不是当前摘要（${compaction?.compactionId}）"
                }
                copy(
                    compactionContexts = compactionContexts +
                        (target.compactionId to compactionContexts[target.compactionId].orEmpty() + e.text),
                )
            }
        }
        is ToolInputRewritten -> updateTurn(e.turnId) { t ->
            val call = t.calls[e.callId] ?: error("回合 ${e.turnId} 里没有调用 ${e.callId}")
            check(call.phase == CallPhase.Planned) { "只有还没判定的调用才能改写入参，${e.callId} 处于 ${call.phase}" }
            t.copy(calls = t.calls + (e.callId to call.copy(input = e.input)))
        }
        is InputBlocked -> {
            check(inbox.any { it.clientMsgId == e.clientMsgId }) { "拦下的输入 ${e.clientMsgId} 不在收件箱里" }
            copy(
                inbox = inbox.filterNot { it.clientMsgId == e.clientMsgId },
                presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
            )
        }
        is ToolOutputsMasked -> updateTurn(e.turnId) { it }.copy(
            masked = masked + e.outputs.associate { it.callId to it.path },
            lastRequestUsage = null,
        )
        is CompactionStarted -> updateTurn(e.turnId) { it }.let { s ->
            check(s.openCompaction == null) { "上一次压缩 ${s.openCompaction} 还没结束就开始了 ${e.compactionId}" }
            s.copy(openCompaction = e.compactionId)
        }
        is ContextCompacted -> {
            check(openCompaction == e.compactionId) { "结束的压缩 ${e.compactionId} 与进行中的 $openCompaction 对不上" }
            val boundary = history.indexOfFirst { it.messageId == e.through }
            check(boundary >= 0) { "压缩的边界 ${e.through} 不在历史里" }
            // 边界及之前的消息被摘要取代，挂在它们上面的按需记忆已不在视图里：只留快照里的与边界之后消息上的
            val stillAttached = history.drop(boundary + 1).flatMap { entry ->
                memoryAttachments[entry.messageId].orEmpty().map { it.path } + memoryCovered[entry.messageId].orEmpty()
            }
            updateTurn(e.turnId) { it.copy(usage = it.usage + e.usage) }.copy(
                memoryPathsInContext = memory.map { it.path }.toSet() + stillAttached,
                compaction = CompactionView(e.compactionId, e.summary, e.through, e.transcriptPath),
                // 旧摘要连同挂在它末尾的 SessionStart(compact) 上下文一起被新摘要取代
                compactionContexts = emptyMap(),
                openCompaction = null,
                compactionFailures = 0,
                lastRequestUsage = null,
                presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
            )
        }
        is CompactionFailed -> {
            check(openCompaction == e.compactionId) { "失败的压缩 ${e.compactionId} 与进行中的 $openCompaction 对不上" }
            updateTurn(e.turnId) { it.copy(usage = it.usage + e.usage) }.copy(
                openCompaction = null,
                compactionFailures = compactionFailures + 1,
            )
        }
        is ToolEffectPending -> updateCall(e.turnId, e.callId) { phase ->
            check(phase == CallPhase.Planned || phase == CallPhase.Approved) {
                "${e.callId} 处于 $phase，不能开始副作用"
            }
            CallPhase.EffectPending(e.handle)
        }
        is ToolOutcome -> updateCall(e.turnId, e.callId) { phase ->
            check(phase !is CallPhase.Done) { "${e.callId} 已经有结果了" }
            CallPhase.Done(e.result)
        }.withEdits(record.seq, e.callId, e.result).copy(
            toolResults = toolResults + (e.callId to e.result),
            children = e.result.effects.fold(children) { current, effect ->
                when (effect) {
                    is SessionEffect.ReportDelivered -> {
                        val child = current[effect.child]
                        if (child == null) current else current +
                            (child.child to child.copy(reportedTurns = child.reportedTurns + effect.childTurn))
                    }
                    // 模式变更由内核紧跟着写成 SettingsChanged，在那条事件上折叠
                    is SessionEffect.ModeChange -> current
                    // 任务清单的修改同理：由内核紧跟着写成 TaskCreated 等事件，在那些事件上折叠
                    is SessionEffect.TaskCreate, is SessionEffect.TaskUpdate, is SessionEffect.TaskDelete -> current
                    // 定时任务同理：内核紧跟着写成 cron_scheduled / cron_cancelled
                    is SessionEffect.CronCreate, is SessionEffect.CronDelete -> current
                    is SessionEffect.McpLoadTools -> current
                }
            },
        )
        is TurnEnded -> {
            val t = checkNotNull(turn) { "没有进行中的回合却收到了结束 ${e.turnId}" }
            check(t.turnId == e.turnId) { "结束的是 ${e.turnId}，当前回合是 ${t.turnId}" }
            copy(
                turn = null,
                usage = usage + t.usage,
                presentation = presentation + SessionPresentation(
                    record, history.lastOrNull()?.messageId, t.openRequest?.messageId, t.openRequest?.segments.orEmpty(),
                ),
                lastTurn = EndedTurn(e.turnId, t.trigger, e.outcome, t.usage, e.durationMs, t.calls.size, e.lastActivityAtMs ?: record.atMs),
            )
        }
        is SubagentSpawned -> {
            check(e.child !in children) { "重复委派子会话 ${e.child}" }
            copy(children = children + (e.child to ChildSummary(e.child, e.callId, e.agentType, e.fork, e.background, e.description, e.name)))
        }
        is SubagentOrigin -> {
            check(lastSeq == 1L) { "SubagentOrigin 必须紧跟在 SessionCreated 之后" }
            copy(origin = e)
        }
        is ForkSeeded -> {
            check(origin?.fork == true) { "只有分身会话才能继承对话" }
            check(history.isEmpty() && turn == null) { "继承的对话必须在任何回合之前写入" }
            copy(
                history = e.messages.map { m ->
                    when (m.role) {
                        Role.User -> HistoryEntry.User(m.messageId, m.parts)
                        Role.Assistant -> HistoryEntry.Assistant(m.messageId, m.parts)
                    }
                },
                toolResults = e.results.associate { it.callId to it.result },
                masked = e.masked.associate { it.callId to it.path },
                // 父会话压缩过：分身的视图同样从摘要开始（含挂在摘要末尾的 hook 上下文）
                compaction = e.compaction?.let { CompactionView(it.compactionId, it.summary, it.through, it.transcriptPath) },
                compactionContexts = e.compaction?.let { mapOf(it.compactionId to it.contexts) }.orEmpty(),
            )
        }
        is QuestionAsked -> withQuestionAsked(e, record.atMs)
        is QuestionAnswered -> withQuestionAnswered(e)
        is TaskCreated, is TaskUpdated, is TaskDeleted -> {
            taskProblem(e)?.let { error("任务事件不合法：$it") }
            applyTaskEvent(e)
        }
        is dev.min.agent.spi.TeamCreated, is dev.min.agent.spi.TeamMemberJoined, is dev.min.agent.spi.TeamTaskCreated,
        is dev.min.agent.spi.TeamTaskUpdated, is dev.min.agent.spi.TeamTaskDeleted, is dev.min.agent.spi.TeamDisbanded -> {
            teamProblem(e)?.let { error("团队事件不合法：$it") }
            applyTeamEvent(e)
        }
        is dev.min.agent.spi.CronScheduled, is dev.min.agent.spi.CronFired, is dev.min.agent.spi.CronCancelled, is dev.min.agent.spi.CronExpired,
        is dev.min.agent.spi.CronBudgetStopped -> {
            cronProblem(e)?.let { error("定时任务事件不合法：$it") }
            applyCronEvent(e)
        }
        // 邮件事件只记「收到过什么」；校验在发送端（Hub），收件人的日志里不必有团队状态
        is dev.min.agent.spi.TeamMessage -> copy(teamMail = teamMail + TeamMailItem(e.teamId, e.messageId, e.from, e.text, e.broadcast))
        // 回顾只给人看：不碰 history，不进 modelMessages；花的钱计入会话用量
        is dev.min.agent.spi.SessionRecap -> copy(
            recap = RecapView(e.text, e.trigger, lastTurn?.turnId),
            usage = usage + e.usage,
            presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
        )
        is Note -> copy(presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId))
        is dev.min.agent.spi.WebSearchUsed -> copy(webSearches = webSearches + 1)
        is dev.min.agent.spi.WebExtractionCompleted -> updateTurn(e.turnId) { it.copy(usage = it.usage + e.usage) }
        is CheckpointTaken -> withCheckpoint(record, e)
        is FilesRestored -> withRestore(record, e)
        is ConversationRewound -> withRewind(record, e)
        // MCP 快照整份替换；变化说明给界面看（模型从工具表与系统提示里看到变化）
        is dev.min.agent.spi.McpServersLoaded -> copy(mcpServers = e)
        is dev.min.agent.spi.McpAuthentication -> this
        is dev.min.agent.spi.McpToolSearchConfigured -> copy(mcpToolSearch = e)
        is dev.min.agent.spi.McpToolsLoaded -> copy(loadedMcpTools = (loadedMcpTools + e.names).distinct())
        is dev.min.agent.spi.McpUrlElicitationAsked -> {
            check(e.questionId !in mcpUrls)
            copy(mcpUrls = mcpUrls + (e.questionId to McpUrlPending(e, atMs = record.atMs)))
        }
        is dev.min.agent.spi.McpUrlElicitationResolved -> {
            val pending = checkNotNull(mcpUrls[e.questionId])
            check(pending.action == null && e.action in listOf("accept", "decline", "cancel"))
            copy(mcpUrls = mcpUrls + (e.questionId to pending.copy(action = e.action)))
        }
        is dev.min.agent.spi.McpToolsSnapshot -> copy(
            mcpTools = e,
            presentation = if (e.note == null) presentation else presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
        )
    }
    val stamped = next.turn?.let { next.copy(turn = it.copy(lastEventAtMs = record.atMs)) } ?: next
    return stamped.copy(lastSeq = record.seq, lastEventAtMs = record.atMs)
}

/** 「总是允许」攒下的规则的来源名（审计时显示「是本会话里你允许的」） */
const val SESSION_RULE_SOURCE = "session"

private fun SessionState.fromRewoundChild(origin: InputOrigin): Boolean =
    origin is InputOrigin.Agent && children[origin.session]?.rewound == true

/** 子 agent 的报告进了收件箱：记下它对应的子回合已交付，补发与去重据此跳过 */
private fun SessionState.reported(origin: InputOrigin): Map<SessionId, ChildSummary> {
    if (origin !is InputOrigin.Agent || origin.kind != AgentInputKind.Report) return children
    val turn = origin.childTurn ?: return children
    val child = children[origin.session] ?: return children
    return children + (child.child to child.copy(reportedTurns = child.reportedTurns + turn))
}

private fun SessionState.updateTurn(turnId: TurnId, change: (TurnState) -> TurnState): SessionState {
    val t = checkNotNull(turn) { "没有进行中的回合，事件却属于 $turnId" }
    check(t.turnId == turnId) { "事件属于 $turnId，当前回合是 ${t.turnId}" }
    return copy(turn = change(t))
}

private fun SessionState.updateCall(
    turnId: TurnId,
    callId: CallId,
    change: (CallPhase) -> CallPhase,
): SessionState = updateTurn(turnId) { t ->
    val call = t.calls[callId] ?: error("回合 $turnId 里没有调用 $callId")
    t.copy(calls = t.calls + (callId to call.copy(phase = change(call.phase))))
}
