package dev.min.agent.spi

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 会话日志：按 seq 排列的 SessionEvent。内存状态 = fold(日志)；下一次模型请求必须能从日志逐字节重建
 * （docs/agent/02-总体架构.md §1、§5）。
 *
 * 格式纪律（agent/store/FORMAT.md）：变体与字段只加不改不删；默认值一旦发布就不能改（AgentJson 不写默认值）。
 * 这里只收 M1 用得到的事件，其余（压缩、检查点、子 agent……）在实现时追加。
 */

/** 存储层给每条事件包的一层：seq 与时间由存储分配，内核只产出事件本身 */
@Serializable
data class EventRecord(val seq: Long, val atMs: Long, val event: SessionEvent)

/** 日志文件第一行。读到比自己新的 [formatVersion] 主版本就拒绝打开 */
@Serializable
data class LogHeader(
    val formatVersion: Int,
    val sessionId: SessionId,
    val createdAtMs: Long,
    /** 子 agent 的会话指回父会话 */
    val parent: SessionId? = null,
)

@Serializable
sealed interface SessionEvent

// ---- 会话设置 ----

@Serializable
@SerialName("session_created")
data class SessionCreated(
    val cwd: GuestPath,
    val model: ModelRef,
    val mode: PermissionMode,
    val effort: Effort? = null,
) : SessionEvent

/**
 * 会话中途改设置。只写变了的字段。
 * 对模型的影响以「在对话末尾追加一段说明」表达，不改写前缀（docs/agent/04-子系统设计.md §4.5）。
 */
@Serializable
@SerialName("settings_changed")
data class SettingsChanged(
    val model: ModelRef? = null,
    val mode: PermissionMode? = null,
    val effort: Effort? = null,
    val cwd: GuestPath? = null,
) : SessionEvent

// ---- 输入：先进收件箱，到安全点才成为正式消息 ----

@Serializable
enum class Delivery {
    /** 回合结束后成为下一回合 */
    @SerialName("queue") Queue,

    /** 插话：当前工具批结束后、同一回合内送达 */
    @SerialName("steer") Steer,
}

@Serializable
@SerialName("input_received")
data class InputReceived(
    val clientMsgId: ClientMsgId,
    val parts: List<Part>,
    val delivery: Delivery,
    /** 谁送来的；默认是用户（默认值不落盘，旧日志读出来就是用户输入） */
    val origin: InputOrigin = InputOrigin.User,
) : SessionEvent

@Serializable
@SerialName("input_promoted")
data class InputPromoted(val clientMsgId: ClientMsgId, val messageId: MessageId) : SessionEvent

/** 还没提升的输入被用户撤回（Min 现有的「按 Esc 退回输入框」） */
@Serializable
@SerialName("input_withdrawn")
data class InputWithdrawn(val clientMsgId: ClientMsgId) : SessionEvent

// ---- 回合与模型请求 ----

@Serializable
enum class TurnTrigger {
    @SerialName("user") User,

    /** 进程被杀后由用户点「继续」恢复 */
    @SerialName("resume") Resume,

    /** 空闲时收到子 agent 的报告或消息，自动开回合接着处理 */
    @SerialName("notification") Notification,

    /** 子会话的第一个回合：父会话刚委派过来（分身没有新输入，靠这个开局） */
    @SerialName("delegated") Delegated,
}

@Serializable
@SerialName("turn_started")
data class TurnStarted(val turnId: TurnId, val trigger: TurnTrigger) : SessionEvent

/**
 * 发请求**之前**落盘（fsync）。[prefixFingerprint] 是稳定前缀的摘要：
 * 用来在界面上解释缓存为什么没命中，也用来在测试里断言前缀没被改写。
 */
@Serializable
@SerialName("model_request_started")
data class ModelRequestStarted(
    val turnId: TurnId,
    val requestId: RequestId,
    val attempt: Int,
    val model: ModelRef,
    val prefixFingerprint: String,
) : SessionEvent

/** 流式中按时间 / 字节攒批落盘的半截回答；崩溃后给界面展示「被中断的回答」 */
@Serializable
@SerialName("assistant_segment")
data class AssistantSegment(
    val turnId: TurnId,
    val requestId: RequestId,
    val messageId: MessageId,
    val parts: List<Part>,
) : SessionEvent

/**
 * 一条完整的模型回复。[truncated] 为真表示输出被上限截断：这时回复里的工具调用**不执行**
 * （参数可能不完整），改为回给模型一句「请缩小或分批重发」（pi 的做法）。
 */
@Serializable
@SerialName("assistant_message_completed")
data class AssistantMessageCompleted(
    val turnId: TurnId,
    val requestId: RequestId,
    val messageId: MessageId,
    val parts: List<Part>,
    val usage: Usage,
    val stop: StopReason,
    val truncated: Boolean = false,
) : SessionEvent

@Serializable
enum class ModelFailureKind {
    @SerialName("network") Network,
    @SerialName("rate_limited") RateLimited,
    @SerialName("overloaded") Overloaded,
    @SerialName("context_overflow") ContextOverflow,
    @SerialName("auth") Auth,
    @SerialName("bad_request") BadRequest,
    @SerialName("server") Server,
    @SerialName("other") Other,
}

@Serializable
@SerialName("model_request_failed")
data class ModelRequestFailed(
    val turnId: TurnId,
    val requestId: RequestId,
    val attempt: Int,
    val kind: ModelFailureKind,
    val message: String,
    val httpStatus: Int? = null,
    val willRetry: Boolean = false,
) : SessionEvent

// ---- 工具：planned → (审批) → effect_pending → outcome ----

/**
 * 进程在 [ToolEffectPending] 之后、[ToolOutcome] 之前被杀时，恢复时怎么办（docs/agent/02-总体架构.md §5）。
 */
@Serializable
enum class ResumePolicy {
    /** 只读工具：直接重跑 */
    @SerialName("replay") Replay,

    /** 后台进程：[ExecHandle] 记着 pid 与日志路径，重新挂上 */
    @SerialName("reattach") Reattach,

    /** 其余：合成「执行被中断，结果未知，不要盲目重试有副作用的操作」 */
    @SerialName("unknown") Unknown,
}

@Serializable
@SerialName("tool_planned")
data class ToolPlanned(
    val turnId: TurnId,
    val callId: CallId,
    val tool: String,
    val input: JsonObject,
    val resumePolicy: ResumePolicy,
) : SessionEvent

/** 审批是持久请求：进程重启后照这条重新挂到界面上 */
@Serializable
@SerialName("approval_requested")
data class ApprovalRequested(
    val turnId: TurnId,
    val approvalId: ApprovalId,
    val callId: CallId,
    val ask: PermissionAsk,
    /** 「总是允许」时建议写下的规则（尽量窄）；null = 不该给「总是允许」（踩了底线、复合命令……） */
    val suggestedRule: String? = null,
    /** 踩了安全底线的原因：界面据此解释「为什么连 bypass 也要问你」；没踩为 null */
    val floorReason: String? = null,
) : SessionEvent

@Serializable
@SerialName("approval_resolved")
data class ApprovalResolved(
    val approvalId: ApprovalId,
    val choice: ApprovalChoice,
    val decidedBy: DecidedBy,
) : SessionEvent

/** 副作用开始前的最后一步，必须 fsync；落盘失败就不执行 */
@Serializable
@SerialName("tool_effect_pending")
data class ToolEffectPending(
    val turnId: TurnId,
    val callId: CallId,
    val handle: ExecHandle? = null,
) : SessionEvent

@Serializable
@SerialName("tool_outcome")
data class ToolOutcome(
    val turnId: TurnId,
    val callId: CallId,
    val result: ToolResult,
) : SessionEvent

// ---- 回合结束 ----

@Serializable
sealed interface TurnOutcome {
    @Serializable @SerialName("completed") data object Completed : TurnOutcome
    /** [kind]：最后那次请求失败的种类（界面据此说「先检查供应商」「上下文已满」）；内核自身的错误为 null */
    @Serializable @SerialName("failed") data class Failed(val message: String, val kind: ModelFailureKind? = null) : TurnOutcome
    @Serializable @SerialName("interrupted") data class Interrupted(val by: InterruptedBy) : TurnOutcome
}

@Serializable
enum class InterruptedBy {
    @SerialName("user") User,

    /** 恢复时发现上次进程死在回合中途 */
    @SerialName("process_death") ProcessDeath,

    /** maxTurns / 预算 / 循环检测等上限 */
    @SerialName("limit") Limit,
}

@Serializable
@SerialName("turn_ended")
data class TurnEnded(
    val turnId: TurnId,
    val outcome: TurnOutcome,
    val usage: Usage,
    val durationMs: Long,
    /**
     * 回合里最后一条事件的时间。进程被杀后补的收尾事件本身发生在重开时，「中断于何时」要看这个；
     * 旧日志没有它（null），界面退回用事件自己的时间
     */
    val lastActivityAtMs: Long? = null,
) : SessionEvent

// ---- 给人看的 ----

@Serializable
enum class NoteLevel {
    @SerialName("info") Info,
    @SerialName("warning") Warning,
    @SerialName("error") Error,
}

/** 只给人看，不进模型上下文 */
@Serializable
@SerialName("note")
data class Note(val level: NoteLevel, val text: String) : SessionEvent
