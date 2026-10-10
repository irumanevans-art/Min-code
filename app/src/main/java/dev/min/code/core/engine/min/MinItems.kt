package dev.min.code.core.engine.min

import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.ChildSummary
import dev.min.agent.kernel.EndedTurn
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionPresentation
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.displayText
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.TurnTriggerView
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.withClippedResult

/*
 * 内核 SessionState -> ChatItem 列表（设计 5.2 的 1-5 条）。M2-A 的 `projectMinAgent` 是同一件事的旧版，
 * 它把生成中的那一块拼进 items、文案写死中文、不认识子 agent；B3 起适配器走这里。旧函数的文件不在 B3 许可改动的名单里，
 * 所以留着没动（现在只剩它自己的单测在用，B6 清理）。
 *
 * ## 为什么用 Note 承载几样东西
 *
 * 设计 3.11 要给 ChatItem 加 `UserText.continuation`、`Note.marker`、`AgentReport` 三处。B10 加了 `AgentReport`；
 * 另两处还是用现有的 [ChatItem.Note] 承载：中断 / 失败说明、续跑分隔、子 agent 唤醒分隔，只写在 [markerNote] / [dividerNote] 两个函数里。
 */

/** 子 agent 报告标签里的信息（`Reports.render` 的逆）。tools / tokens / duration 缺失或畸形时为 null */
data class MinReport(
    val sessionId: String,
    val agentType: String,
    val name: String?,
    val status: String,
    val fork: Boolean,
    val tools: Int?,
    val tokens: Long?,
    val durationMs: Long?,
    val body: String,
    val review: String? = null,
    val handback: Boolean = false,
) {
    /** 报告是不是「没做完 / 出错」：卡片按失败色画 */
    val isError: Boolean get() = status != "completed"
}

private const val REPORT_TAG = "subagent-report"
private val REPORT_SHAPE = Regex("^<$REPORT_TAG\\s+([^>]*)>\\n(.*)\\n</$REPORT_TAG>\\s*$", RegexOption.DOT_MATCHES_ALL)
private val ATTRIBUTE = Regex("([\\w-]+)=\"([^\"]*)\"")

/**
 * 解析内核交给父会话的报告（`Reports.render`）。不是这个形状返回 null，由调用方退回成普通文字。
 * 正文里的标签是被转义过的（子 agent 不能伪造「报告之外的话」），这里反转义。
 */
fun parseMinReport(text: String): MinReport? {
    val match = REPORT_SHAPE.matchEntire(text.trim()) ?: return null
    val attrs = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
    val type = attrs["type"] ?: return null
    val body = match.groupValues[2].replace("&lt;/$REPORT_TAG", "</$REPORT_TAG").replace("&lt;$REPORT_TAG", "<$REPORT_TAG")
    return MinReport(
        sessionId = attrs["id"].orEmpty(), agentType = type, name = attrs["name"],
        status = attrs["status"] ?: "completed", fork = attrs["fork"] == "true",
        tools = attrs["tools"]?.toIntOrNull(), tokens = attrs["tokens"]?.toLongOrNull(),
        durationMs = attrs["duration-ms"]?.toLongOrNull(), body = body,
        review = attrs["review"], handback = attrs["handback"] == "true",
    )
}

// ---- 内核类型 -> 通用类型 ----

fun failureKindOf(kind: ModelFailureKind?): FailureKind? = when (kind) {
    null -> null
    ModelFailureKind.Network -> FailureKind.Network
    ModelFailureKind.RateLimited -> FailureKind.RateLimited
    ModelFailureKind.Overloaded -> FailureKind.Overloaded
    ModelFailureKind.ContextOverflow -> FailureKind.ContextOverflow
    ModelFailureKind.Auth -> FailureKind.Auth
    ModelFailureKind.BadRequest -> FailureKind.BadRequest
    ModelFailureKind.ModelUnavailable -> FailureKind.ModelUnavailable
    ModelFailureKind.Server -> FailureKind.Server
    ModelFailureKind.Other -> FailureKind.Other
}

fun turnEndOf(outcome: TurnOutcome): TurnEnd = when (outcome) {
    TurnOutcome.Completed -> TurnEnd.Completed
    is TurnOutcome.Failed -> TurnEnd.Failed(failureKindOf(outcome.kind), outcome.message)
    is TurnOutcome.Interrupted -> TurnEnd.Interrupted(
        when (outcome.by) {
            InterruptedBy.User -> InterruptCause.User
            InterruptedBy.ProcessDeath -> InterruptCause.ProcessDeath
            InterruptedBy.Limit -> InterruptCause.Limit
            InterruptedBy.Hook -> InterruptCause.Hook
            // 花费上限也是一种上限：界面按「达到上限」处理（原因在引擎写的那条 Note 里）
            InterruptedBy.Budget -> InterruptCause.Limit
            InterruptedBy.Ended -> InterruptCause.Ended
        },
    )
}

fun triggerViewOf(trigger: TurnTrigger): TurnTriggerView = when (trigger) {
    TurnTrigger.User -> TurnTriggerView.User
    TurnTrigger.Resume -> TurnTriggerView.Resume
    TurnTrigger.Notification -> TurnTriggerView.Notification
    TurnTrigger.Hook -> TurnTriggerView.Hook
    TurnTrigger.Delegated -> TurnTriggerView.Delegated
    TurnTrigger.Compact -> TurnTriggerView.Compact
    TurnTrigger.Cron -> TurnTriggerView.Cron
}

fun EndedTurn.end(): TurnEnd = turnEndOf(outcome)

// ---- 承载（以后加了 ChatItem 变体只改这两处）----

private fun markerNote(id: String, text: String, isError: Boolean): ChatItem = ChatItem.Note(id, text, isError)
private fun dividerNote(id: String, text: String): ChatItem = ChatItem.Note(id, "— $text —")

/**
 * 投影一个会话的转录。
 *
 * @param subItems 某次 Agent 调用派生的子 agent 的转录（按调用 id），挂在那张工具卡的 `subItems` 上，
 *   展开工具卡就能看子任务的过程（CC 同款）。没有就空
 * @param childOf 报告里的子 agent id -> 它在本会话里的委派摘要，补上报告里没有的描述
 */
fun projectMinItems(
    state: SessionState,
    text: MinAgentText,
    subItems: (CallId) -> List<ChatItem>? = { null },
    childOf: (SessionId) -> ChildSummary? = { state.children[it] },
): List<ChatItem> = ItemsBuilder(state, text, subItems, childOf).build()

private class ItemsBuilder(
    private val state: SessionState,
    private val text: MinAgentText,
    private val subItems: (CallId) -> List<ChatItem>?,
    private val childOf: (SessionId) -> ChildSummary?,
) {
    private val items = mutableListOf<ChatItem>()

    /** 当前还能回退到的用户消息：长按这些才有「回到这里」 */
    private val rewindIds = state.rewindPoints.mapTo(HashSet()) { it.messageId.value }

    /** 上一个回合结束时 items 的长度：回执戳只往它之后的最后一条助手文字上盖 */
    private var stampFloor = 0

    fun build(): List<ChatItem> {
        notices(null)
        state.history.forEach { entry ->
            when (entry) {
                is HistoryEntry.User -> user(entry)
                is HistoryEntry.Assistant -> parts(entry.messageId.value, entry.parts)
            }
            notices(entry.messageId)
        }
        // 会话回顾（只给人看，不进模型）：回顾之后又有了新回合就过期了，不再显示
        state.recap?.takeIf { it.afterTurn == state.lastTurn?.turnId }?.let { items += ChatItem.Note("recap", text.recapLine(it.text)) }
        // 还没提升的输入：用户的排队气泡（Esc 可原样撤回）。子 agent / 父会话送来的不画，提升后会以报告卡出现。
        // id 直接用 clientMsgId：WithdrawQueued(clientId) 带的就是 UserText.id，不用再剥前缀
        state.inbox.filter { it.origin == InputOrigin.User }.forEach { input ->
            items += ChatItem.UserText(input.clientMsgId.value, plain(input.parts), queued = true)
        }
        return items
    }

    private fun plain(parts: List<Part>): String =
        parts.joinToString("\n") { if (it is TextPart) it.text else text.attachment() }

    private fun user(entry: HistoryEntry.User) {
        val id = entry.messageId.value
        when (val origin = entry.origin) {
            // 子会话视角的 Parent：委派来的任务原文，直接当一条用户气泡（它就是「上级对我说的话」）
            InputOrigin.User, is InputOrigin.Parent -> items += ChatItem.UserText(id, plain(entry.parts), rewindable = origin == InputOrigin.User && id in rewindIds)
            is InputOrigin.Agent -> {
                val raw = plain(entry.parts)
                val child = childOf(origin.session)
                when (origin.kind) {
                    AgentInputKind.Report -> {
                        val report = parseMinReport(raw)
                        items += if (report == null) ChatItem.UserText(id, raw) else reportCard(id, report, child)
                    }
                    AgentInputKind.Message -> items +=
                        ChatItem.Note(id, text.agentMessage(child?.agentType ?: "agent", child?.name) + "\n" + raw)
                }
            }
            // Stop hook 拦下「结束」时交给模型的理由：不是人说的话，作为说明显示
            is InputOrigin.Hook -> items += ChatItem.Note(id, text.hookFeedback(origin.event) + "\n" + plain(entry.parts))
            // 定时任务到点触发：会话预设的任务，不画成用户气泡（也就不能被当成「回退到这条用户消息」）
            is InputOrigin.Cron -> items += ChatItem.Note(id, text.cronFired(origin.cronId) + "\n" + plain(entry.parts))
            // 后台任务的通知：进程的输出，不是人说的话；去掉给模型看的标签壳，只留正文
            is InputOrigin.Task -> items += ChatItem.Note(id, text.taskNotice(origin.taskId) + "\n" + dev.min.agent.kernel.taskNoticeBody(plain(entry.parts)))
        }
    }

    private fun reportCard(id: String, report: MinReport, child: ChildSummary?) = ChatItem.AgentReport(
        id = id,
        // 线程 id 是发起子 agent 的那次 Agent 调用的 id；委派记录不在（父会话是被回退过的旧状态）就不可跳
        threadId = child?.callId?.value,
        agentType = report.agentType, name = report.name ?: child?.name,
        description = child?.description?.takeIf { it.isNotBlank() },
        status = report.status, isError = report.isError,
        toolCalls = report.tools, tokens = report.tokens, durationMs = report.durationMs,
        body = when (report.review) {
            "approved" -> text.reviewedReport() + "\n\n" + report.body
            "warning" -> text.warningReport() + "\n\n" + report.body
            "unavailable" -> text.unavailableReport() + "\n\n" + report.body
            else -> report.body
        },
    )

    private fun parts(messageId: String, content: List<Part>) {
        content.forEachIndexed { index, part ->
            val id = "$messageId:$index"
            when (part) {
                is TextPart -> if (part.text.isNotEmpty()) items += ChatItem.AssistantText(id, part.text)
                is ThinkingPart -> items += ChatItem.Thinking(id, part.text)
                is RedactedThinkingPart -> items += ChatItem.Thinking(id, text.redactedThinking())
                is ToolCallPart -> items += toolCard(id, part)
                else -> Unit
            }
        }
    }

    private fun toolCard(id: String, part: ToolCallPart): ChatItem.ToolCall {
        val phase = state.turn?.calls?.get(part.callId)?.phase
        val result = state.toolResults[part.callId] ?: (phase as? CallPhase.Done)?.result
        val denied = phase is CallPhase.Denied
        val error = result?.isError == true || denied
        val status = when {
            error -> ChatItem.ToolCall.Status.Error
            result != null -> ChatItem.ToolCall.Status.Done
            // 等审批也是 Running：「在等人」由交互队列表达，不由卡片状态表达
            else -> ChatItem.ToolCall.Status.Running
        }
        // 用户的话（问答的答案、拒绝的说明）不在 content 里（发给模型时要分开），但卡片照旧显示它们
        val body = result?.displayText()
            ?: (phase as? CallPhase.Denied)?.note
        val card = ChatItem.ToolCall(id, part.callId.value, part.name, part.input, status, isError = error).withClippedResult(body)
        // 内核给的预览已被截过：全文长度以它为准（取两者较大，避免预览把总长冲小）
        return card.copy(
            resultTotalChars = maxOf(card.resultTotalChars ?: 0, result?.fullChars ?: 0).takeIf { it > 0 },
            // 内核报的改动折成 Claude / Codex 那边同形的 unified diff，交给底部的「改动过的文件」面板
            editDiff = editDiffOf(result),
            subItems = subItems(part.callId).orEmpty(),
        )
    }

    /**
     * 编辑类工具改过的文件 → 与 Claude 的 `bashEditDiff` / Codex 的 fileChange 同形的 unified diff 文本。
     * 一次调用改多个文件时按顺序接起来（空行隔开），面板的 `diffPaths` 靠 `+++ b/…` 头认出每个文件。
     * 新建 / 删除补上 `new file mode` / `deleted file mode`，面板据此给图标、撤销时知道该删还是该还原。
     */
    private fun editDiffOf(result: ToolResult?): String? {
        val edits = result?.fileEdits.orEmpty().filter { it.diff != null || it.created || it.deleted }
        if (edits.isEmpty()) return null
        return edits.joinToString("\n\n") { edit ->
            val header = when {
                edit.created -> "new file mode"
                edit.deleted -> "deleted file mode"
                else -> null
            }
            // 没有 diff 正文（内容太大的整文件覆盖）时只留文件头，面板仍能列出这个文件
            val path = edit.path.value
            val body = edit.diff ?: "--- $path\n+++ $path"
            if (header == null) body else "$header\n$body"
        }
    }

    private fun notices(after: MessageId?) {
        state.presentation.filter { it.afterMessageId == after }.forEach(::notice)
    }

    private fun notice(entry: SessionPresentation) {
        val seq = entry.record.seq
        val atMs = entry.record.atMs
        // 回合被中断前已落盘的半截回答：留在中断说明之前（它不进模型历史，只给人看）
        if (entry.partialParts.isNotEmpty()) parts(entry.partialMessageId?.value ?: "notice:$seq:partial", entry.partialParts)
        when (val event = entry.record.event) {
            is Note -> items += ChatItem.Note("notice:$seq", event.text, event.level == NoteLevel.Error)
            is dev.min.agent.spi.NetworkDecision -> items += ChatItem.Note("network:$seq",
                text.networkDecision(event.domain, event.port, event.outcome.name, event.reason), event.outcome == dev.min.agent.spi.NetworkOutcome.Deny)
            is dev.min.agent.spi.WorkflowProgress -> items += ChatItem.Note("workflow:$seq", event.message)
            is TurnStarted -> when (event.trigger) {
                TurnTrigger.Resume -> items += dividerNote("resume:$seq", text.continued(atMs))
                TurnTrigger.Notification -> items += dividerNote("wake:$seq", text.subagentWoke())
                // 后台 asyncRewake hook 唤醒的回合：分隔线说明「是谁」把会话叫醒的
                TurnTrigger.Hook -> items += dividerNote("hook:$seq", text.hookWoke())
                // 定时任务到点唤醒的回合
                TurnTrigger.Cron -> items += dividerNote("cron:$seq", text.cronWoke())
                else -> Unit
            }
            is TurnEnded -> turnEnded(seq, atMs, event)
            is ConversationRewound -> rewound(event)
            is FilesRestored -> restored(event)
            else -> Unit
        }
    }

    /**
     * 回退掉的那段对话折成一行「已回退的 N 条消息」，点开是原样的条目（只读）。
     * 展开的内容用同一个投影，只是拿被回退掉的历史当状态——所以工具卡、报告卡长什么样这里也长什么样；
     * 回退点清空：被回退掉的消息不能再「回到这里」
     */
    private fun rewound(event: ConversationRewound) {
        val segment = state.rewound.firstOrNull { it.rewindId == event.rewindId } ?: return
        val deadIds = segment.entries.mapTo(HashSet()) { it.messageId }
        // 「恢复了文件」的记录挂在回退时最后一条消息之后——选「两者」时那条消息正好被回退掉。它说的是工作区不是对话，
        // 而且「撤销这次回退」的入口在它上面：不能跟着被折进去（那样就再也点不到了），所以拿出来排在折叠行后面
        val restores = state.presentation.filter { it.afterMessageId in deadIds && it.record.event is FilesRestored }
        val nested = ItemsBuilder(
            state.copy(
                history = segment.entries, toolResults = segment.toolResults,
                presentation = state.presentation.filter { it.afterMessageId in deadIds && it.record.event !is FilesRestored },
                inbox = emptyList(), turn = null, rewindPoints = emptyList(), rewound = emptyList(),
            ),
            text, subItems = { null }, childOf,
        ).build()
        items += ChatItem.Folded("rewound:${event.rewindId}", text.rewoundTitle(segment.entries.size), nested = nested)
        restores.forEach { restored(it.record.event as FilesRestored) }
    }

    /**
     * 恢复了代码：一行「恢复了 N 个文件……」，点开逐个文件与跳过的原因（CC 只给个数，路径藏在调试日志里）。
     * 能撤销 = 有「回滚前」快照、本身不是一次撤销、还没被撤销过
     */
    private fun restored(event: FilesRestored) {
        val kind = when {
            event.undoOf != null -> RestoreKind.Undo
            event.checkpointId != null -> RestoreKind.Checkpoint
            else -> RestoreKind.Calls
        }
        val lines = event.restored.map { text.restoredLine(it.value) } + event.deleted.map { text.deletedLine(it.value) } +
            event.skipped.map { text.skippedLine(it.path.value, it.reason) }
        val canUndo = event.preRestore != null && event.undoOf == null && state.restores.none { it.undoOf == event.restoreId }
        items += ChatItem.Folded(
            "restored:${event.restoreId}", text.restoredTitle(kind, event.restored.size, event.deleted.size, event.skipped.size),
            lines = lines, undoId = event.restoreId.takeIf { canUndo },
        )
    }

    private fun turnEnded(seq: Long, atMs: Long, event: TurnEnded) {
        when (val outcome = event.outcome) {
            TurnOutcome.Completed -> stampLastAnswer(event, atMs)
            is TurnOutcome.Failed -> items += markerNote(
                "notice:$seq",
                // 第一行是本地化的说明，其余是内核给的原文（详情，可展开）
                text.failed(failureKindOf(outcome.kind), atMs) + "\n" + outcome.message,
                isError = true,
            )
            is TurnOutcome.Interrupted -> items += markerNote(
                "notice:$seq",
                when (outcome.by) {
                    InterruptedBy.User -> text.stopped(atMs)
                    // 进程被杀的回合，「中断于何时」是被杀前最后一条事件，不是重开会话的这一刻
                    InterruptedBy.ProcessDeath -> text.processDeath(event.lastActivityAtMs ?: atMs)
                    InterruptedBy.Limit -> text.limit()
                    InterruptedBy.Hook -> text.hookStopped(atMs)
                    // 为什么停写在紧挨着的那条 Note（花了多少、上限多少）里，这里只标「已停止」
                    InterruptedBy.Budget -> text.stopped(atMs)
                    // 会话已由模型结束：只标「已停止」，原因是紧挨着的那条 Note（会话已由模型结束，新开会话才能继续）
                    InterruptedBy.Ended -> text.stopped(atMs)
                },
                isError = false,
            )
        }
        stampFloor = items.size
    }

    /** 回合正常结束：把耗时 / 输出量 / 钟点盖在这一轮最后一条回答上（每轮只盖一条，与 CC 的回执一致） */
    private fun stampLastAnswer(event: TurnEnded, atMs: Long) {
        val at = items.indexOfLast { it is ChatItem.AssistantText }
        if (at < stampFloor) return
        val answer = items[at] as ChatItem.AssistantText
        items[at] = answer.copy(
            durationMs = event.durationMs.takeIf { it > 0 }, outputTokens = event.usage.outputTokens.toInt(), finishedAt = atMs,
        )
        stampFloor = items.size
    }
}
