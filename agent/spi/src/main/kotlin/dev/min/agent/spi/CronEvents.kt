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

/**
 * 每个定时任务单次运行的默认花费上限（美元）。无人值守地花用户的钱，所以手机上每个任务必须有上限，默认 0.05，可改
 * （M5 总览 §四 决定 3）。触发前估算不了的（模型没有 USD 定价）按保守值处理：不触发、记过期
 */
const val CRON_DEFAULT_BUDGET_USD = 0.05

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
    val maxBudgetUsd: Double,
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

    /** 一次运行花到了 [CronScheduled.maxBudgetUsd]，或花费无法核对：停下这次运行并取消任务 */
    @SerialName("budget") Budget,

    /** 一次性任务的时刻在会话没开着的时候过去了（不补跑） */
    @SerialName("missed") Missed,

    /** 当前模型没有 USD 定价，花费估算不了，按保守值不触发 */
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
