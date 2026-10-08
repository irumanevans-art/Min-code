package dev.min.code.core.engine.min

import dev.min.agent.spi.Scheduler
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.sync.Mutex

/**
 * 定时任务在 Android 上的定时器（M5）：**心跳驱动**，不是闹钟。宿主每心跳一次就调 [beat]，内核借这一下检查有没有到点的任务。
 *
 * 为什么不用 AlarmManager：精确闹钟要额外权限（SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM），本期不要；不精确闹钟又只会在
 * 进程被杀后把 App 拉起来——而被拉起来的是一个没有会话在线的空进程，定时任务属于会话，拉起来也没得触发。
 * 所以这里的承诺很小，也必须写清楚（不能假装可靠）：
 *  - **App 被系统杀掉、或切到后台一段时间后进程被冻结时定时任务不跑**，重新打开会话时未过期的任务恢复，错过的不补跑；
 *  - **不保证精确时刻**：到点后最迟在下一次心跳触发（[HEARTBEAT_MS]，大约一分钟），手机进了 Doze 更晚；
 *  - 只在进程活着时工作。进程能活多久靠前台服务（`ClaudeCodeForegroundService`）：它现在只在有回合在跑时保活，
 *    **只有定时任务、没有回合在跑的会话不会让前台服务一直在**——要做成“为定时任务保活”是界面 / 服务那一层的事，本期没做。
 *
 * 纯 Kotlin、没有 Android 依赖，所以能在 JVM 上单测。
 */
class HeartbeatScheduler : Scheduler {
    @Volatile
    private var onTick: (suspend () -> Unit)? = null

    /** 一次回调没返回就不再叠第二次（内核的触发本身是串行的，这里再挡一层，免得心跳比回调快时堆起协程） */
    private val running = Mutex()

    override fun start(onTick: suspend () -> Unit): AutoCloseable {
        this.onTick = onTick
        return AutoCloseable { if (this.onTick === onTick) this.onTick = null }
    }

    /** 宿主的心跳：没接内核、或上一次回调还没完时什么都不做；回调抛的异常吞掉（取消除外），下次心跳照常 */
    suspend fun beat() {
        val tick = onTick ?: return
        if (!running.tryLock()) return
        try {
            catchingNonCancellation { tick() }
        } finally {
            running.unlock()
        }
    }

    companion object {
        /** 心跳间隔：cron 的最小粒度是一分钟，再密也触发不出更多；手机上也不该为定时任务更频繁地醒 */
        const val HEARTBEAT_MS = 60_000L
    }
}
