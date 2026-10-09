package dev.min.agent.kernel

import dev.min.agent.spi.Scheduler
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌面（CLI、测试）用的定时器：进程内一个协程，每 [periodMs] 回调一次。只在进程活着时工作，进程退出它就没了——
 * 与 Claude Code「会话开着、空闲时才触发」一致；Android 的实现见 app 的 `HeartbeatScheduler`。
 * [periodMs] 默认 15 秒：cron 表达式的最小粒度是一分钟，15 秒的检查误差够小、开销可以忽略
 */
class InProcessScheduler(
    private val scope: CoroutineScope,
    private val periodMs: Long = DEFAULT_PERIOD_MS,
) : Scheduler {
    init {
        require(periodMs > 0) { "定时器的间隔必须是正数。" }
    }

    override fun start(onTick: suspend () -> Unit): AutoCloseable {
        val job = scope.launch {
            while (isActive) {
                delay(periodMs)
                // 一次回调失败（某个会话的日志写不进去）不能停掉整个定时器；取消照常往外传
                catchingNonCancellation { onTick() }
            }
        }
        return AutoCloseable { job.cancel() }
    }

    companion object {
        const val DEFAULT_PERIOD_MS = 15_000L
    }
}
