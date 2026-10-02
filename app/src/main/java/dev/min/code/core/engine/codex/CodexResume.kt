package dev.min.code.core.engine.codex

import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.offerResume
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Codex 的「继续」键（▶）判定里属于 Codex 的那部分（设计 §6.5）。出现条件是通用的（[offerResume]）。
 *
 * **只做映射，不接线**，而且 Codex 现状根本没有 ▶：停止键杀的是整个 app-server（`turn/interrupt` 没接，
 * [EngineCapabilities.Codex] 的 `resume = None`），[offerResume] 会直接返回 null。B8 接上 `turn/interrupt`
 * 之后把 [CodexMapContext.capabilities] 换成 [EngineCapabilities.CodexWithTurnInterrupt] 即生效；
 * 此外 manager 还要补 `lastTurnEnd`（见 [CodexStateMapper] 头注释）。
 */
internal object CodexResume {

    fun tailOf(items: List<ChatItem>): HistoryTail {
        val last = items.lastOrNull { it !is ChatItem.Note && it !is ChatItem.ProcessOutput && !(it is ChatItem.UserText && it.queued) }
            ?: return HistoryTail.Empty
        return when (last) {
            is ChatItem.UserText, is ChatItem.Thinking -> HistoryTail.UserMessage
            is ChatItem.AssistantText -> HistoryTail.AssistantFinal
            is ChatItem.ToolCall ->
                if (last.status == ChatItem.ToolCall.Status.Running) HistoryTail.AssistantWithToolCalls else HistoryTail.ToolResults
            else -> HistoryTail.Empty
        }
    }

    /**
     * @param lastEnd 上一回合怎么结束的（manager 的 `lastTurnEnd`，B8 才有）；null = 不知道，不出 ▶
     */
    fun offer(
        state: CodexAppServerManager.State,
        lastEnd: TurnEnd?,
        capabilities: EngineCapabilities = EngineCapabilities.Codex,
    ): ResumeOffer? {
        if (lastEnd == null) return null
        val exited = state.status == SessionStatus.Closed || state.status == SessionStatus.Failed
        return offerResume(
            capabilities = capabilities,
            turnRunning = state.busy,
            stopping = false,
            // 要有 threadId 才能 thread/resume；只读回放（Idle，没进程）也算能跑：▶ 会先接回进程
            canRun = state.threadId != null,
            lastEnd = lastEnd,
            since = null,
            tail = tailOf(state.items),
            pendingInputs = state.queued.size,
            offerId = "${state.threadId}@${state.turnId ?: "none"}",
            needsRelaunch = exited || state.status == SessionStatus.Idle,
        )
    }

    /**
     * 旧会话的 rollout 最后一个回合有没有收尾：App 被杀后重开用（§6.5 表最后一行）。
     * 回合开始事件之后没有（完成 | 中止）事件 = 停在半路。
     *
     * **事件名未与真 rollout 校准**（设计标「待确认」）：这里按上游 `EventMsg` 的 snake_case 名字写
     * （`task_started` / `turn_started` 开，`task_complete` / `turn_complete` / `turn_aborted` / `task_aborted` 收），
     * B8 拿真 rollout 对一遍再信它。
     */
    fun rolloutEndedMidTurn(lines: Sequence<String>): Boolean {
        var open = false
        for (line in lines) {
            val obj = runCatching { Json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject ?: continue
            if ((obj["type"] as? JsonPrimitive)?.content != "event_msg") continue
            val kind = ((obj["payload"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: continue
            when (kind) {
                "task_started", "turn_started" -> open = true
                "task_complete", "turn_complete", "turn_aborted", "task_aborted" -> open = false
            }
        }
        return open
    }
}
