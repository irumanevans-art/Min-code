package dev.min.agent.otel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 周期导出：会话进行中按间隔把新事件导出去，而不是只在进程退出时导一次。
 *
 * 节拍取已开启信号里最短的间隔（原文默认：指标 60000、日志 5000、traces 5000，所以通常是 5 秒）；
 * 每个节拍读一次新记录，再让 [OtelTail.flushDue] 按各信号自己的间隔决定发哪些。
 * 时间来源 [now] 与 [delay] 都可被测试替换成虚拟时钟（协程测试的 currentTime），不真等。
 *
 * 失败隔离：读与导出的错误在 [OtelTail] / [OtelExporter] 里已被收成计数；这里再兜一层，
 * 防止任何意外让循环停掉——遥测只能是旁路。只有协程取消照常往上传，宿主据此停掉循环。
 */
class OtelPeriodic(
    private val tail: OtelTail,
    private val config: OtelConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val tick = tickMs(config)
        while (true) {
            delay(tick)
            try {
                tail.collect()
                tail.flushDue(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 意外错误：本节拍作废，下个节拍照常
            }
        }
    }

    companion object {
        /** 节拍下限：间隔设得再小（如 10ms）也不去空转地翻日志 */
        const val MIN_TICK_MS = 250L

        fun tickMs(config: OtelConfig): Long {
            val intervals = buildList {
                add(config.metricIntervalMs)
                add(config.logIntervalMs)
                if (config.tracesEnabled) add(config.traceIntervalMs)
            }
            return maxOf(intervals.min(), MIN_TICK_MS)
        }
    }
}
