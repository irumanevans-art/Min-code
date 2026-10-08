package dev.min.agent.kernel

import dev.min.agent.spi.CRON_MAX_ACTIVE
import dev.min.agent.spi.CRON_MIN_INTERVAL_MS
import dev.min.agent.spi.CronBoard
import dev.min.agent.spi.CronCancelled
import dev.min.agent.spi.CronExpiry
import dev.min.agent.spi.CronExpired
import dev.min.agent.spi.CronFired
import dev.min.agent.spi.CronParsed
import dev.min.agent.spi.CronExpression
import dev.min.agent.spi.CronScheduled
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.CronTask
import dev.min.agent.spi.SessionEvent
import java.time.ZoneId

/*
 * 定时任务的折叠与校验：[SessionState.crons] 完全由 cron_scheduled / cron_fired / cron_cancelled / cron_expired 折叠出来
 * （M5 定时调度）。和任务清单、团队同一个做法——校验 [cronProblem] 是纯函数，折叠用它守日志完整性（违反即内核 bug），
 * 工具声明的效果在落事件之前用同一份判断筛一遍。
 *
 * 触发、过期、花费上限这些**要读时钟、要碰会话**的事不在这里，在 [CronEngine]；这里只回答「日志里的状态是什么」。
 */

internal fun SessionState.nextCronId(): String = "c${cronSeq + 1}"

/** 给工具看的视图。定时任务只属于根会话：子 agent / 团队成员建了也没人会触发它，所以直接拒绝并说明 */
internal fun SessionState.cronBoard(nowMs: Long, zone: ZoneId, budgetCeilingUsd: Double? = null) = CronBoard(
    tasks = crons,
    nextId = nextCronId(),
    nowMs = nowMs,
    zone = zone,
    budgetCeilingUsd = budgetCeilingUsd,
    refusal = if (origin != null) "定时任务只能在根会话里建：子 agent 与团队成员的会话不会被定时触发。请让负责人（主会话）来建。" else null,
)

internal fun SessionState.cronTask(id: String): CronTask? = crons.firstOrNull { it.id == id }

/** 这条定时任务事件在当前状态上不合法的原因；合法（或根本不是定时任务事件）返回 null */
internal fun SessionState.cronProblem(e: SessionEvent): String? = when (e) {
    is CronScheduled -> {
        val cron = e.cron
        val interval = e.intervalMs
        val parsed = cron?.let { CronExpression.parse(it) }
        when {
            origin != null -> "定时任务只能建在根会话里。"
            e.cronId != nextCronId() -> "定时任务编号 ${e.cronId} 不是下一个编号 ${nextCronId()}。"
            e.prompt.isBlank() -> "定时任务要有提示词。"
            (cron == null) == (interval == null) -> "cron 表达式与间隔要恰好给一个。"
            interval != null && interval < CRON_MIN_INTERVAL_MS -> "间隔最短一分钟。"
            parsed is CronParsed.Invalid -> parsed.reason
            e.maxBudgetUsd?.let { !it.isFinite() || it <= 0 } == true -> "单次花费上限必须是大于 0 的有限数。"
            crons.count { it.active } >= CRON_MAX_ACTIVE -> "一个会话最多同时有 $CRON_MAX_ACTIVE 个定时任务，先删掉几个。"
            else -> null
        }
    }
    is CronFired -> activeTask(e.cronId)
    is CronCancelled -> activeTask(e.cronId)
    is CronExpired -> activeTask(e.cronId)
    else -> null
}

private fun SessionState.activeTask(id: String): String? = when (val t = cronTask(id)) {
    null -> "没有定时任务 $id。"
    else -> if (t.active) null else "定时任务 $id 已经结束了（${t.endedBecause ?: t.status.name}）。"
}

/** 把一条定时任务事件折进状态；别的事件原样返回。合法性由调用方（[cronProblem]）先判 */
internal fun SessionState.applyCronEvent(e: SessionEvent): SessionState = when (e) {
    is CronScheduled -> copy(
        crons = crons + CronTask(
            e.cronId, e.prompt, e.recurring, e.maxBudgetUsd, e.scheduledAtMs,
            cron = e.cron, intervalMs = e.intervalMs, expiresAtMs = e.expiresAtMs, source = e.source,
        ),
        cronSeq = cronSeq + 1,
    )
    is CronFired -> copy(
        crons = crons.map { t ->
            if (t.id != e.cronId) t
            else t.copy(
                fires = t.fires + 1,
                lastFiredAtMs = e.firedAtMs,
                // 一次性任务触发一次就结束；循环任务继续
                status = if (t.recurring) CronStatus.Active else CronStatus.Fired,
                endedBecause = if (t.recurring) null else "一次性任务已触发",
            )
        },
    )
    is CronCancelled -> copy(crons = crons.map { if (it.id == e.cronId) it.copy(status = CronStatus.Cancelled, endedBecause = "已取消") else it })
    is CronExpired -> copy(
        crons = crons.map { if (it.id == e.cronId) it.copy(status = CronStatus.Expired, endedBecause = expiryText(e)) else it },
    )
    else -> this
}

/** 过期原因的中文，写进任务的 endedBecause、通知与 Note 里 */
internal fun expiryText(e: CronExpired): String {
    val head = when (e.reason) {
        CronExpiry.Age -> "循环任务满 7 天自动过期"
        CronExpiry.Budget -> "花费上限"
        CronExpiry.Missed -> "一次性任务的时刻在会话没开着时过去了，不补跑"
        CronExpiry.NoPricing -> "当前模型没有 USD 定价，花费估算不了，按保守值不触发"
    }
    return e.detail?.let { "$head（$it）" } ?: head
}

/**
 * 一次定时运行的花费记账：从触发那一刻起算，到会话重新空闲为止。花费计入会话（ModelCharged 照常累计），
 * 这里只是「这次运行从多少起算」——上限按**一次运行**算，不是任务的累计（每次触发都有新的 0.05 美元额度）。
 * 运行期间用户插进来的话也算在这次运行里：保守，宁可早停
 */
internal class CronRun(
    val cronId: String,
    val limitUsd: Double,
    val baselineUsd: Double,
    val baselineUnpriced: Int,
) {
    fun spentUsd(state: SessionState): Double = (state.modelCosts["USD"] ?: 0.0) - baselineUsd

    /** 花到上限、或有一次花费记不出价格（无法核对）：返回给人看的原因；没事返回 null */
    fun problem(state: SessionState): String? {
        val spent = spentUsd(state)
        return when {
            spent >= limitUsd -> "这次运行花了 ${money(spent)}，到了单次上限 ${money(limitUsd)}"
            state.unpricedModelCalls > baselineUnpriced -> "这次运行里有模型请求没有 USD 价格，花费无法核对，按到上限处理"
            else -> null
        }
    }
}

internal fun money(usd: Double): String = "$" + String.format(java.util.Locale.ROOT, "%.4f", usd)
