package dev.min.agent.kernel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.UserQuestion
import kotlinx.serialization.json.JsonObject

/**
 * 会话在某个 seq 上的完整状态 = 日志前缀的折叠结果（[fold]）。不可变：每条事件产生一个新状态。
 * 内核里没有任何别的地方保存会话状态——这是「日志即真相」在代码里的样子（docs/agent/02-总体架构.md §1）。
 */
data class SessionState(
    val header: LogHeader,
    /** 会话开始时的设置。系统提示里的环境信息由它和 [header] 推出，保证同一段日志组出同一个前缀 */
    val created: SessionCreated,
    val lastSeq: Long,
    val settings: SessionSettings,
    /** 收到了、还没提升成正式消息、也没被撤回的输入，按到达顺序 */
    val inbox: List<PendingInput>,
    /** 会话里收到过的全部输入 id：同一个 id 重发（通知栏重复点击、断网重连）只收一次 */
    val seenInputs: Set<ClientMsgId>,
    /** 模型看得到的对话（工具结果单独放在 [toolResults]，投影时按调用顺序插回） */
    val history: List<HistoryEntry>,
    val toolResults: Map<CallId, ToolResult>,
    /** 正在进行的回合；null = 空闲 */
    val turn: TurnState?,
    /** 已结束回合累计的用量 */
    val usage: Usage,
)

data class SessionSettings(
    val cwd: GuestPath,
    val model: ModelRef,
    val mode: PermissionMode,
    val effort: Effort?,
    /**
     * 最近一次切进计划模式之前的模式（折叠时在模式变成 Plan 的那一刻记下）；从没进过为 null。
     * 离开计划模式不清空：它只在下次进入时被覆盖，ExitPlanMode 也只在计划模式里才用它。
     */
    val modeBeforePlan: PermissionMode? = null,
)

data class PendingInput(val clientMsgId: ClientMsgId, val parts: List<Part>, val delivery: Delivery)

sealed interface HistoryEntry {
    val messageId: MessageId

    data class User(override val messageId: MessageId, val parts: List<Part>) : HistoryEntry

    data class Assistant(override val messageId: MessageId, val parts: List<Part>) : HistoryEntry {
        val toolCalls: List<ToolCallPart> get() = parts.filterIsInstance<ToolCallPart>()
    }
}

data class TurnState(
    val turnId: TurnId,
    val trigger: TurnTrigger,
    val startedAtMs: Long,
    /** 发出去了、还没完成也没失败的那次请求 */
    val openRequest: OpenRequest?,
    /** 本回合的工具调用，按规划顺序 */
    val calls: Map<CallId, CallState>,
    val usage: Usage,
    /** 本回合向用户提的问题，含已答的：答复留在这里，等着的工具从状态里取；回合结束随回合一起清掉 */
    val questions: Map<QuestionId, AskedQuestion> = emptyMap(),
) {
    /**
     * 还在等用户回答的提问，界面据此弹提问卡片（进程重启后同样从日志重建）。
     * 提问的调用已经不在执行中（不再等了）的不算，免得界面挂着一张答了也没人收的卡片。
     */
    val pendingQuestions: List<AskedQuestion>
        get() = questions.values.filter { it.outcome == null && calls[it.callId]?.phase is CallPhase.EffectPending }
}

/** 一次提问：哪个调用问的、问了什么、答复（还没答为 null） */
data class AskedQuestion(
    val questionId: QuestionId,
    val callId: CallId,
    val questions: List<UserQuestion>,
    val outcome: QuestionOutcome?,
)

data class OpenRequest(
    val requestId: RequestId,
    val attempt: Int,
    val messageId: MessageId?,
    /** 已落盘的半截回答（崩溃后给界面看） */
    val segments: List<Part>,
)

data class CallState(
    val callId: CallId,
    val tool: String,
    val input: JsonObject,
    val resumePolicy: ResumePolicy,
    val phase: CallPhase,
)

sealed interface CallPhase {
    /** 已规划，权限管线还没给结论 */
    data object Planned : CallPhase

    data class AwaitingApproval(val approvalId: ApprovalId, val ask: PermissionAsk) : CallPhase

    /** 放行了（规则、hook、模式或人），副作用还没开始 */
    data object Approved : CallPhase

    data class Denied(val note: String?) : CallPhase

    /** 副作用已开始（ToolEffectPending 已落盘） */
    data class EffectPending(val handle: ExecHandle?) : CallPhase

    data class Done(val result: ToolResult) : CallPhase
}
