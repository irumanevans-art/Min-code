package dev.min.agent.spi

import dev.min.agent.protocol.ClientMsgId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 定时任务（CronCreate / CronDelete / CronList，M5 定时调度）进日志的形状。
 *
 * 会话级：任务存在**根会话自己的日志**里，状态 [CronTask] 完全由下面四个事件折叠出来；重开会话时折叠出未过期的任务
 * 就是「恢复」（照 Claude Code：`--resume` 恢复未过期任务、新会话清空会话级任务）。触发时在该会话里写一条
 * 来源为 [InputOrigin.Cron] 的输入——是定时任务的话，不是人发的话。
 *
 * 全部 append-only；格式纪律同 SessionEvent.kt：变体与字段只加不改不删，默认值发布后不能改。
 */

/** 每个会话最多同时存在的定时任务数（Claude Code 的上限，文档 scheduled-tasks） */
const val CRON_MAX_ACTIVE = 50

/** 循环任务创建 7 天后自动过期（Claude Code 同；一次性任务不过期，触发一次就结束） */
const val CRON_RECURRING_TTL_MS = 7L * 24 * 60 * 60 * 1000

/** 间隔写法的下限：一分钟（cron 表达式本身的最小粒度也是分钟） */
const val CRON_MIN_INTERVAL_MS = 60_000L

/*
 * 定时任务**没有默认花费上限**：Claude Code 没有，各大 agent 的定时任务也都是花用户自己的额度（用户的口径：「照各大 agent 的实际做法」）。
 * 早先拍过一个 0.05 美元的默认值，在 Opus 上几乎第一个请求就超，等于任务一触发就被掐——删掉了。
 * 上限现在是**可选**的 Min 扩展：模型或用户给某个任务设 `max_budget_usd`，宿主还可以设一个天花板（`cronMaxBudgetUsd`，取小）。
 */

/**
 * 新建一条定时任务。[cronId] 由内核分配（会话内递增、不复用）；[cron]（5 字段表达式）与 [intervalMs]（间隔）恰好给一个。
 * [recurring] 为假 = 一次性：[cron] 表示创建后第一次匹配的时刻，[intervalMs] 表示创建后过这么久。
 * [expiresAtMs] 只有循环任务有（[CRON_RECURRING_TTL_MS]）。时间都由内核写入时给定，折叠不读时钟
 */
@Serializable
@SerialName("cron_scheduled")
data class CronScheduled(
    val cronId: String,
    val prompt: String,
    val recurring: Boolean,
    /** 单次运行的花费上限（美元）；null = 不限（默认，同 Claude Code）。宿主的 cronMaxBudgetUsd 天花板在触发时再取小，不改写这里 */
    val maxBudgetUsd: Double? = null,
    val scheduledAtMs: Long,
    val cron: String? = null,
    val intervalMs: Long? = null,
    val expiresAtMs: Long? = null,
    /** 谁建的：null = CronCreate；[CRON_SOURCE_WAKEUP] = ScheduleWakeup（自驱循环的下一轮，同一时刻最多一个） */
    val source: String? = null,
) : SessionEvent

/** [CronScheduled.source] 的取值：ScheduleWakeup 安排的下一轮 */
const val CRON_SOURCE_WAKEUP = "wakeup"

/**
 * 到点触发了：与同一次提交里的 `input_received`（`origin = cron`，`clientMsgId` 即这里的 [clientMsgId]）成对出现。
 * 一次性任务触发后即结束
 */
@Serializable
@SerialName("cron_fired")
data class CronFired(
    val cronId: String,
    val firedAtMs: Long,
    val clientMsgId: ClientMsgId,
) : SessionEvent

/** 用户 / 模型取消（CronDelete），或分支时不继承 */
@Serializable
@SerialName("cron_cancelled")
data class CronCancelled(val cronId: String) : SessionEvent

@Serializable
enum class CronExpiry {
    /** 循环任务满 7 天 */
    @SerialName("age") Age,

    /** 连续两次运行都花到了单次上限（任务自己的或宿主天花板）：暂停任务（结束，要继续得重新建）。单次超限只停那一次运行，见 [CronBudgetStopped] */
    @SerialName("budget") Budget,

    /** 一次性任务的时刻在会话没开着的时候过去了（不补跑） */
    @SerialName("missed") Missed,

    /** 不再写出：任务设了 USD 上限而当前模型没有 USD 价格时，现在是「这一次跳过」，任务保持进行中。保留取值只为读旧日志 */
    @SerialName("no_pricing") NoPricing,
}

@Serializable
@SerialName("cron_expired")
data class CronExpired(
    val cronId: String,
    val reason: CronExpiry,
    /** 给人看的一句话（花了多少、上限多少） */
    val detail: String? = null,
) : SessionEvent

/**
 * 一次运行因为花费到了上限被停下（任务本身**不**因此结束，除非这是连续第二次）。写在被停的那次运行里：
 * [fire] 是这次运行是任务的第几次触发（= [CronFired] 之后任务的触发次数），折叠据此判断「连续」——
 * 紧挨着上一次触发也被停才算连续，中间有一次正常结束就从头数。一次性任务与 ScheduleWakeup 的唤醒（触发后即 `Fired`）同样记，不静默。
 * 连续第二次超限时内核紧跟着写一条 [CronExpired]（reason = budget）暂停任务。
 */
@Serializable
@SerialName("cron_budget_stopped")
data class CronBudgetStopped(
    val cronId: String,
    val fire: Int,
    /** 给人看的一句话（花了多少、上限多少） */
    val detail: String? = null,
) : SessionEvent
