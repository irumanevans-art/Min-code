package dev.min.code.core.engine.min

import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.ChildSummary
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.InputOrigin
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.threadPhaseOfTurn
import dev.min.code.core.session.ChatItem

/**
 * 根会话的子 agent 树 -> 平铺的 [AgentThread] 列表（设计 5.7）。
 *
 * 树来自各级会话的 `state.children`（委派时写进父日志），每个子会话的状态由 `nodes` 给
 * （`hub.open(root)` 会递归打开整棵子树，卸下时也是整棵一起）。深度优先、按委派顺序，
 * 靠 [AgentThread.parentThreadId] 还原成树。
 *
 * 线程 id 用发起它的那次 Agent 调用的 id（`ChildSummary.callId`）：它在父日志里本来就有、重启后不变，
 * 而且 `ChatItem.ToolCall.toolUseId` 就是它，切换条按工具卡认线程不用额外对照表。
 *
 * @param itemsOf 各会话的转录（已经投影好；子 agent 的转录同时挂在 Agent 工具卡的 subItems 上，共用同一份列表）
 * @param canRun 供应商档还在；子 agent 的 ▶ 与根会话同一条件（它用的档是委派时按父会话 / 定义解出来的）
 */
fun minThreads(
    root: SessionId,
    nodes: Map<SessionId, SessionState>,
    itemsOf: (SessionId) -> List<ChatItem>,
    lookup: MinModelLookup,
    canRun: (SessionState) -> Boolean,
): List<AgentThread> {
    val result = mutableListOf<AgentThread>()
    fun walk(parentId: SessionId, parentThread: String?, depth: Int) {
        val parent = nodes[parentId] ?: return
        for ((childId, summary) in parent.children) {
            val state = nodes[childId]
            result += threadOf(summary, state, parentThread, depth, itemsOf(childId), lookup, canRun)
            walk(childId, summary.callId.value, depth + 1)
        }
    }
    walk(root, null, 1)
    return result
}

private fun threadOf(
    summary: ChildSummary,
    state: SessionState?,
    parentThread: String?,
    depth: Int,
    items: List<ChatItem>,
    lookup: MinModelLookup,
    canRun: (SessionState) -> Boolean,
): AgentThread {
    val threadId = summary.callId.value
    // 子会话不在线（不该发生：卸下是整棵树一起）：给一个停下的空线程，不让整棵树的投影因它崩掉
    if (state == null) {
        return AgentThread(
            threadId = threadId, parentThreadId = parentThread, depth = depth, agentType = summary.agentType, name = summary.name,
            description = summary.description, prompt = null, fork = summary.fork, background = summary.background,
            phase = AgentThread.Phase.Stopped, activity = null, items = emptyList(), pendingInteractions = 0, usage = null,
            childRef = SessionRef(EngineKind.MinAgent, summary.child.value), tracked = false, messageable = false,
        )
    }
    val running = state.turn != null
    val ended = state.lastTurn
    val resume = minResumeOffer(state, canRun = canRun(state))
    return AgentThread(
        threadId = threadId,
        parentThreadId = parentThread,
        depth = state.origin?.depth ?: depth,
        agentType = summary.agentType,
        name = summary.name,
        description = summary.description,
        prompt = promptOf(state),
        fork = summary.fork,
        background = summary.background,
        phase = threadPhaseOfTurn(running, ended?.end()),
        // 在干什么：正在跑的那次调用的工具名（等审批的也算，卡片上要看得出它卡在哪）
        activity = state.turn?.calls?.values?.lastOrNull { it.phase !is CallPhase.Done && it.phase !is CallPhase.Denied }?.tool,
        items = items,
        pendingInteractions = 0, // 由调用方按交互队列统一填（withPendingCounts）
        usage = usageViewOf(state, lookup.profileOf(state.settings.model)?.contextWindow),
        childRef = SessionRef(EngineKind.MinAgent, summary.child.value),
        // 切换条列它：在跑，或报告还没交付，或停在半路等人决定要不要接着做
        tracked = running || ended == null || ended.turnId !in summary.reportedTurns || resume != null,
        messageable = true,
        resume = resume,
    )
}

/** 委派来的任务原文：第一条来自上级的输入（已提升的在历史里，还在收件箱的在 inbox 里） */
private fun promptOf(state: SessionState): String? {
    val promoted = state.history.firstOrNull { it is HistoryEntry.User && it.origin is InputOrigin.Parent } as? HistoryEntry.User
    val parts = promoted?.parts ?: state.inbox.firstOrNull { it.origin is InputOrigin.Parent }?.parts ?: return null
    return parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }.ifBlank { null }
}
