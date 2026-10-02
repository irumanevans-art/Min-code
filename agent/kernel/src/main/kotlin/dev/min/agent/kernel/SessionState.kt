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
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.TaskRecord
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
    /** 会话创建时冻结的权限规则（最后一条 PermissionRulesLoaded） */
    val rules: List<RuleEntry> = emptyList(),
    /** 本会话里人选「总是允许」攒下的规则，按答复顺序；来源记为 session */
    val sessionRules: List<RuleEntry> = emptyList(),
    /**
     * 最近一次完成的模型请求的用量：界面据它算「上下文用了多少」（输入 + 缓存读写 ÷ 窗口）；还没请求过为 null。
     * 压缩改写了模型视图之后清空——那次的用量已经不代表下一次请求的大小
     */
    val lastRequestUsage: Usage? = null,
    /** 模型视图里被遮蔽的工具结果 → 原文所在的会话附件（docs/agent/detail/压缩设计.md §1） */
    val masked: Map<CallId, String?> = emptyMap(),
    /** 最后一次摘要：模型视图里到 [CompactionView.through] 为止的对话换成它 */
    val compaction: CompactionView? = null,
    /** 连续失败的自动压缩次数，到 3 次熔断（成功一次清零） */
    val compactionFailures: Int = 0,
    /** 开始了还没有结局的压缩（进程死在中途时恢复用） */
    val openCompaction: String? = null,
    /**
     * 本会话的任务清单，按创建顺序（TaskCreate / TaskUpdate 工具维护，由 TaskCreated / TaskUpdated / TaskDeleted 折叠）。
     * 只属于本会话：子 agent 各有自己的一份，不与父会话共享。有默认值：没用过任务工具的会话日志里没有这些事件
     */
    val tasks: List<TaskRecord> = emptyList(),
    /** 已分配过的最大任务编号。删除任务不回退它，编号因此不会被复用 */
    val taskSeq: Int = 0,
)

data class CompactionView(val summary: String, val through: MessageId, val transcriptPath: String?)

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
    /** 回合里最后一次有动静的时间：进程被杀的回合是被杀前最后一条事件，其余就是结束那一刻 */
    val lastActivityAtMs: Long = 0,
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
    /**
     * 最近一次切进计划模式之前的模式（折叠时在模式变成 Plan 的那一刻记下）；从没进过为 null。
     * 离开计划模式不清空：它只在下次进入时被覆盖，ExitPlanMode 也只在计划模式里才用它。
     */
    val modeBeforePlan: PermissionMode? = null,
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
    /** 本回合向用户提的问题，含已答的：答复留在这里，等着的工具从状态里取；回合结束随回合一起清掉 */
    val questions: Map<QuestionId, AskedQuestion> = emptyMap(),
    /** 本回合最后一条事件的时间（折叠时随每条事件更新）。进程被杀时这就是「中断于何时」 */
    val lastEventAtMs: Long = startedAtMs,
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
    val askedAtMs: Long = 0,
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

    data class AwaitingApproval(
        val approvalId: ApprovalId,
        val ask: PermissionAsk,
        val requestedAtMs: Long = 0,
        /** 「总是允许」的建议规则与踩底线的原因，原样来自 ApprovalRequested，卡片据此给按钮与解释 */
        val suggestedRule: String? = null,
        val floorReason: String? = null,
    ) : CallPhase

    /** 放行了（规则、hook、模式或人），副作用还没开始 */
    data object Approved : CallPhase

    data class Denied(val note: String?) : CallPhase

    /** 副作用已开始（ToolEffectPending 已落盘） */
    data class EffectPending(val handle: ExecHandle?) : CallPhase

    data class Done(val result: ToolResult) : CallPhase
}
