package dev.min.agent.cli

import kotlinx.coroutines.*
import sun.misc.Signal
import java.util.concurrent.atomic.AtomicBoolean

internal class EvalInterrupted : CancellationException("plugin eval interrupted")

/** SIGINT 只取消正在执行的 case/judge；聚合器仍活着，才能把已经完成的结果写成部分报告。 */
internal class EvalInterrupt {
    private val stopped = AtomicBoolean()
    private val active = mutableSetOf<Job>()
    val interrupted get() = stopped.get()
    fun interrupt() {
        val jobs = synchronized(active) { stopped.set(true); active.toList() }
        jobs.forEach { it.cancel(EvalInterrupted()) }
    }
    suspend fun <T> run(block: suspend () -> T): T = supervisorScope {
        val job = async(start = CoroutineStart.LAZY) { block() }
        synchronized(active) { active += job; if (stopped.get()) job.cancel(EvalInterrupted()) else job.start() }
        try { job.await() } catch(e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (interrupted) throw EvalInterrupted()
            throw e
        } finally { synchronized(active) { active -= job } }
    }
    fun install(): AutoCloseable {
        val signal = Signal("INT")
        val previous = Signal.handle(signal) { interrupt() }
        return AutoCloseable { Signal.handle(signal, previous) }
    }
}
