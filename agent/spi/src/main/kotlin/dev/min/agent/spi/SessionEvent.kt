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
    /** 这条会话是不是 `-p` / 无头跑的（CC :78：`--continue` 跳过 -p / SDK 会话）；旧日志缺字段 = 交互 */
    val headless: Boolean = false,
    /** null 保留旧日志按进程默认加载的行为；显式选择随日志冻结，重开不会受其它会话影响。 */
    val loadingModes: SessionLoadingModes? = null,
) : SessionEvent

@Serializable
data class SessionLoadingModes(
    val safe: Boolean = false,
    val bare: Boolean = false,
    val restricted: Boolean = false,
) {
    init { require(!(safe && bare)) { "safe 与 bare 不能同时开启" } }
}

/**
 * 会话得了名字（`--name` 建会话时、会话内 `/rename` 改名时）。取最后一条为当前名字。
 * 名字在写入前已经规整（控制 / 不可见字符换成空格、去首尾空白、最长 200 字符）
 */
@Serializable
@SerialName("session_renamed")
data class SessionRenamed(val name: String) : SessionEvent

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
    /** 思考档位清回「不控制」（[effort] 的 null 表示不改，清掉要单独说） */
    val clearEffort: Boolean = false,
) : SessionEvent

/**
 * 世界状态增量（docs/agent/04-子系统设计.md §4.5）：日期、权限模式、模型与模型上次被告知的不一样了，
 * 在下一条提升为正式消息的输入上追加一段 [reminder]。系统提示与之前的消息一个字不改，缓存前缀不断。
 * 三个字段是这一刻的事实（之后比的基准）；文字落进日志而不是折叠时现拼：措辞以后改了，旧会话重放出的字节也不变
 */
@Serializable
@SerialName("world_noted")
data class WorldNoted(
    /** 当地日期 yyyy-MM-dd（按宿主时区） */
    val date: String,
    val mode: PermissionMode,
    /** 计划感知后的会话模型 id（opusplan 在计划里是 opus 档） */
    val model: String,
    val reminder: String,
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

    /** 后台 `asyncRewake` hook 退出码 2，把 stderr 作为系统提醒唤醒了空闲会话 */
    @SerialName("hook") Hook,

    /** 子会话的第一个回合：父会话刚委派过来（分身没有新输入，靠这个开局） */
    @SerialName("delegated") Delegated,

    /** 用户手动压缩（`/compact [焦点]`）：这一回合只做压缩，不调模型回答 */
    @SerialName("compact") Compact,

    /** 定时任务到点触发（M5）：会话空闲时收到一条 `origin = cron` 的输入，自动开回合 */
    @SerialName("cron") Cron,
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

    /** 模型不存在、已退役、账号无权调用：重试没有意义，模型回退链（`fallbackModel`）会换下一个 */
    @SerialName("model_unavailable") ModelUnavailable,
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
    /**
     * [willRetry] 为真时：重发前要等多久（毫秒）、这一类失败最多还允许重试几次（含这一次）。给 stream-json 的 `system/api_retry`
     * 用（SDK 的 `retry_delay_ms` / `max_retries`）；旧日志与不重试的失败没有（null），读端省略这两个键
     */
    val retryDelayMs: Long? = null,
    val maxRetries: Int? = null,
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
    @Serializable @SerialName("failed") data class Failed(
        val message: String,
        val kind: ModelFailureKind? = null,
        /** 内核自己的失败原因标记（与模型的 [kind] 正交）：目前只有结构化输出重试用尽。null = 没有单独归类 */
        val cause: String? = null,
    ) : TurnOutcome
    @Serializable @SerialName("interrupted") data class Interrupted(val by: InterruptedBy) : TurnOutcome
}

/** [TurnOutcome.Failed.cause] 的取值：结构化输出（--json-schema）重试用尽——CC 的 result.subtype `error_max_structured_output_retries` 靠它区分 */
const val FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES = "structured_output_retries"

/** [TurnEnded.limitCause] 的取值：`--max-budget-usd` 的本次运行花费到限（区别于回合数到限）——CC 的 result.subtype `error_max_budget_usd` 靠它区分 */
const val LIMIT_CAUSE_MAX_BUDGET_USD = "max_budget_usd"

@Serializable
enum class InterruptedBy {
    @SerialName("user") User,

    /** 恢复时发现上次进程死在回合中途 */
    @SerialName("process_death") ProcessDeath,

    /** maxTurns / 预算 / 循环检测等上限 */
    @SerialName("limit") Limit,

    /** 某个 hook 输出了 `continue: false`（理由在 Note 里） */
    @SerialName("hook") Hook,

    /** 定时任务的这次运行花到了单次花费上限（M5），只停这一次运行 */
    @SerialName("budget") Budget,

    /** 模型调了 EndConversation：安静停下这一回合，会话从此锁住（见 [ConversationEnded]） */
    @SerialName("conversation_ended") Ended,
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
    /**
     * outcome 是 [InterruptedBy.Limit] 时**为什么**到限（[LIMIT_CAUSE_MAX_BUDGET_USD]：本次运行的 USD 花费到限；
     * null = 回合数到限等旧有原因）。给读端区分用：-p 的 result.subtype、界面文案。旧日志缺字段 = 没写原因
     */
    val limitCause: String? = null,
) : SessionEvent

/**
 * 模型用 EndConversation 结束了会话（CC：会话锁住，只剩 /clear 之类能用）。同一次提交里紧跟 ToolOutcome 与 [TurnEnded]（Ended）写入；
 * 折叠后 [SessionState.ended] 为真，之后的用户输入不再开回合。恢复时从日志折叠出同样的锁。
 */
@Serializable
@SerialName("conversation_ended")
data object ConversationEnded : SessionEvent

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
