package dev.min.agent.spi

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` 的取消安全版：普通异常装进 [Result]，[CancellationException] 原样抛出。
 *
 * 为什么不用 runCatching：它把取消也当失败吞掉，外层 Job 取消了、这里却继续往下跑，
 * 回合中断、进程被杀后的恢复都会因此变得不可预测。agent/ 里 runCatching 被 agentSourceCheck 禁用。
 */
inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
