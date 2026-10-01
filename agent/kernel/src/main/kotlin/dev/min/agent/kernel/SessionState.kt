package dev.min.agent.kernel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
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
    /**
     * 会话创建时冻结的记忆文件（最后一条 MemoryLoaded）。进系统提示的稳定前缀，会话内不变。
     * 有默认值：没有记忆文件的会话日志里根本没有那条事件
     */
    val memory: List<LoadedMemoryFile> = emptyList(),
    /** 展示用事件同样由日志折叠；不进入 modelMessages，避免 UI 说明改变模型历史。 */
    val presentation: List<SessionPresentation> = emptyList(),
    /** 子会话的来历；根会话为 null（docs/agent/detail/子agent设计.md §2.2） */
    val origin: SubagentOrigin? = null,
    /** 本会话委派出去的子 agent，按委派顺序 */
    val children: Map<SessionId, ChildSummary> = emptyMap(),
    /** 最近结束的那个回合。子 agent 的报告取它的结局 */
    val lastTurn: EndedTurn? = null,
)

/** 父会话视角的一个子 agent：委派时的样子 + 哪些回合的报告已经交付 */
data class ChildSummary(
    val child: SessionId,
    val callId: CallId,
    val agentType: String,
    val fork: Boolean,
    val background: Boolean,
    val description: String,
    val name: String?,
    /** 已交付给本会话的报告对应子会话的哪些回合（经收件箱，或前台调用的工具结果） */
    val reportedTurns: Set<TurnId> = emptySet(),
)

data class EndedTurn(
    val turnId: TurnId,
    val trigger: TurnTrigger,
    val outcome: TurnOutcome,
    val usage: Usage,
    val durationMs: Long,
    val toolCalls: Int,
)

data class SessionPresentation(
    val record: dev.min.agent.spi.EventRecord,
    val afterMessageId: MessageId?,
    /** 回合被关闭前的已持久片段，不能随 openRequest 一起丢掉。 */
    val partialMessageId: MessageId? = null,
    val partialParts: List<Part> = emptyList(),
)

data class SessionSettings(
    val cwd: GuestPath,
    val model: ModelRef,
    val mode: PermissionMode,
    val effort: Effort?,
)

data class PendingInput(
    val clientMsgId: ClientMsgId,
    val parts: List<Part>,
    val delivery: Delivery,
    val origin: InputOrigin = InputOrigin.User,
)

sealed interface HistoryEntry {
    val messageId: MessageId

    data class User(
        override val messageId: MessageId,
        val parts: List<Part>,
        /** 界面据此区分用户气泡与子 agent 报告；不影响模型看到的内容 */
        val origin: InputOrigin = InputOrigin.User,
    ) : HistoryEntry

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
