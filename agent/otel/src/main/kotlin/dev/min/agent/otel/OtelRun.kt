package dev.min.agent.otel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * 一次 CLI 运行的遥测：[start] 起周期导出，[finish] 停掉周期循环后补最后一次导出。
 *
 * 为什么先 cancelAndJoin 再 pump：两者读同一份游标，并发会重复或漏发；停稳了再补，缓冲里没来得及发的也在这一次发出去。
 */
class OtelRun(private val tail: OtelTail, private val config: OtelConfig) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch { OtelPeriodic(tail, config).run() }
    }

    suspend fun finish() {
        job?.cancelAndJoin()
        tail.pump()
    }
}
