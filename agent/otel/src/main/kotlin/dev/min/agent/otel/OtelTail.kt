package dev.min.agent.otel

import dev.min.agent.protocol.Effort
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolPlanned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前应该导出的会话日志（根会话 + 它的子孙，或存储里的全部会话）。
 * [fresh] 为真时要重新发现（进程退出、手动导出时：宁可多读一次，也别漏掉刚建的子会话）；
 * 为假时实现可以用短时间内的缓存（周期导出每 5 秒一次，不必每次都把会话目录翻一遍）。
 */
fun interface OtelSessionSource {
    suspend fun logs(fresh: Boolean): List<SessionLog>
}

/**
 * 一个会话的导出进度。
 * [next]：指标与日志已经处理到这里（下一条要读的 seq）。
 * [spansFrom]：span 要从这里重放——最早一个还开着的 span 的起点，没有开着的就等于 [next]。
 * 为什么 span 要单独一个游标：span 要等结束才能导出，跨进程（App 每次「立即导出」都是新进程视角）时，
 * 开着的 span 的状态在内存里、丢了，只能从它的起点重放才能看到完整的起止。
 */
data class OtelCursor(val next: Long, val spansFrom: Long)

/**
 * 把一批会话日志接到导出器上：记住每个会话读到哪一条（游标），每次只处理游标之后的新记录。
 *
 * 为什么是游标而不是每次全量：同一批日志会被多次读（周期导出、进程退出时），全量会把旧事件反复导出。
 * 游标不因导出失败回退：失败的那批只计数、不重发（至多一次）。否则一个坏端点会让同一批事件永远堆积。
 * 例外是 span 的跨进程续导：从最早开着的 span 的起点重放，期间已导出过的 span 会再发一次——
 * span id 由日志确定地算出（见 [SpanIds]），后端按 id 去重，不会变成两份。
 *
 * 读与导出分开：[collect] 读新记录、映射进缓冲；[flushDue] 按各信号的间隔把到点的缓冲发出去（周期导出用）；
 * [pump] 读完立刻全部发出去（进程退出、手动导出用）。
 */
class OtelTail(
    private val source: OtelSessionSource,
    private val config: OtelConfig,
    private val exporter: OtelExporter,
    /** 已有游标的会话从游标起；其余会话从头（或见 [replayFromMs]） */
    initialCursors: Map<String, OtelCursor> = emptyMap(),
    /**
     * 比这个时刻早创建、又没有游标的会话（CLI 恢复会话时带出来的老子会话），从日志末尾起，不把历史在本进程里重放一遍；
     * null = 没有游标的会话一律从头。
     */
    private val replayFromMs: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 单个会话的便捷构造：[fromSeq] 之前的记录当作已处理（CLI 取会话当前的末尾，见 DesktopApp） */
    constructor(log: SessionLog, config: OtelConfig, exporter: OtelExporter, fromSeq: Long = 1) : this(
        OtelSessionSource { _ -> listOf(log) }, config, exporter,
        initialCursors = mapOf(log.header.sessionId.value to OtelCursor(fromSeq, fromSeq)),
    )

    private class SessionState(
        val log: SessionLog,
        val mapper: OtelMapper,
        val spans: OtelSpanMapper?,
        /** 指标与日志映射器从这条起才被喂入（之前的只为 span 重放） */
        val metricsFrom: Long,
        var readNext: Long,
    )

    private val states = LinkedHashMap<String, SessionState>()
    /** 当前这一轮读到的会话（按 id）。span 挂靠只在这些会话里找父 */
    private var byId: Map<String, SessionLog> = emptyMap()
    private val spanLinks = OtelSpanLinks({ config.inbound }) { id -> byId[id] }
    private val cursorsIn = HashMap(initialCursors)
    private val mutex = Mutex()

    private val metricsBuf = ArrayList<OtelItem>()
    private val logsBuf = ArrayList<OtelItem>()
    private val spansBuf = ArrayList<OtelItem>()
    private val lastFlush = hashMapOf(Signal.Metrics to clock(), Signal.Logs to clock(), Signal.Traces to clock())

    private enum class Signal { Metrics, Logs, Traces }

    /** 下一条要读的 seq（单会话时宿主据此把游标存下来） */
    val nextSeq: Long get() = states.values.firstOrNull()?.readNext ?: cursorsIn.values.firstOrNull()?.next ?: 1L

    /** 已经接入导出的会话数（界面据此说「几个会话」） */
    val sessionCount: Int get() = states.size

    /** 全部会话的游标快照：宿主落盘，下次进程从这里续 */
    fun cursors(): Map<String, OtelCursor> {
        val out = LinkedHashMap<String, OtelCursor>(cursorsIn)
        for ((id, s) in states) out[id] = OtelCursor(s.readNext, s.spans?.openSinceSeq?.coerceAtMost(s.readNext) ?: s.readNext)
        return out
    }

    /** 读新记录、映射进缓冲，不导出。返回处理了几条。遥测关闭时直接返回 0，既不读日志也不映射 */
    suspend fun collect(fresh: Boolean = false): Int = mutex.withLock { collectLocked(fresh) }

    /** 读完立刻把全部缓冲发出去（进程退出、手动导出）。返回处理了几条 */
    suspend fun pump(): Int = mutex.withLock {
        val n = collectLocked(fresh = true)
        flushLocked(Signal.entries.toSet())
        n
    }

    /** 把已到间隔的信号的缓冲发出去（间隔照原文：指标 60000、日志 5000、traces 5000，可用环境变量改） */
    suspend fun flushDue(nowMs: Long) = mutex.withLock {
        val due = HashSet<Signal>()
        if (nowMs - lastFlush.getValue(Signal.Metrics) >= config.metricIntervalMs) due += Signal.Metrics
        if (nowMs - lastFlush.getValue(Signal.Logs) >= config.logIntervalMs) due += Signal.Logs
        if (nowMs - lastFlush.getValue(Signal.Traces) >= config.traceIntervalMs) due += Signal.Traces
        flushLocked(due, nowMs)
    }

    // ---- 读 ----

    private suspend fun collectLocked(fresh: Boolean): Int {
        if (!config.active) return 0
        val logs = try {
            source.logs(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exporter.noteFailure("读会话列表失败：${e.javaClass.simpleName}")
            return 0
        }
        byId = logs.associateBy { it.header.sessionId.value }
        var count = 0
        for (log in logs) {
            val id = log.header.sessionId.value
            val state = states[id] ?: try {
                createState(log)?.also { states[id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exporter.noteFailure("准备会话 $id 的遥测失败：${e.javaClass.simpleName}")
                null
            } ?: continue
            count += readSession(state)
        }
        return count
    }

    private suspend fun readSession(state: SessionState): Int {
        // 进程内续读：span 映射器的状态是连续的，从上次读到的地方接着读；首次读的起点在 createState 里定
        val records = try {
            state.log.read(state.readNext).toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exporter.noteFailure("读会话日志失败：${e.javaClass.simpleName}")
            return 0
        }
        if (records.isEmpty()) return 0
        for (record in records) {
            // 映射是纯计算，但它是新代码：出错只丢这一条的遥测，不能让会话收尾跟着失败
            if (record.seq >= state.metricsFrom) feedMetrics(state, record)
            feedSpans(state, record)
            state.readNext = record.seq + 1
        }
        return records.size
    }

    private fun feedMetrics(state: SessionState, record: EventRecord) {
        try {
            for (item in state.mapper.feed(record)) (if (item is OtelLog) logsBuf else metricsBuf) += item
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exporter.noteFailure("映射第 ${record.seq} 条失败：${e.javaClass.simpleName}")
        }
    }

    private fun feedSpans(state: SessionState, record: EventRecord) {
        val spans = state.spans ?: return
        try {
            spansBuf += spans.feed(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exporter.noteFailure("span 映射第 ${record.seq} 条失败：${e.javaClass.simpleName}")
        }
    }

    // ---- 建会话的状态：来历、span 挂靠、起点 ----

    private suspend fun createState(log: SessionLog): SessionState? {
        val id = log.header.sessionId.value
        val lineage = spanLinks.lineageOf(log)
        val link = if (config.tracesEnabled) spanLinks.linkOf(id) else null
        val options = config.options(id).copy(lineage = lineage)
        val cursor = cursorsIn[id]
        val metricsFrom: Long = when {
            cursor != null -> cursor.next
            replayFromMs != null && log.header.createdAtMs < replayFromMs -> {
                // 本进程启动之前就存在的会话：不重放历史，从日志末尾起
                (log.read(1).toList().lastOrNull()?.seq ?: 0L) + 1
            }
            else -> 1L
        }
        // 跨进程续导时 span 映射器不是从日志开头读起：开头的 SessionCreated / SettingsChanged 里的 effort 要先折出来，否则续导的 llm_request 会缺 effort
        val readFromSeq = cursor?.let { minOf(it.next, it.spansFrom) } ?: metricsFrom
        val spans = if (config.tracesEnabled) OtelSpanMapper(options, link, initialEffort = if (readFromSeq > 1) effortBefore(log, readFromSeq) else null) else null
        // 首次读的起点：span 要从游标里记的「开着的 span 的起点」重放，指标与日志从 metricsFrom 起
        val readFrom = if (spans != null && cursor != null) minOf(cursor.next, cursor.spansFrom) else metricsFrom
        cursorsIn.remove(id)
        return SessionState(log, OtelMapper(options), spans, metricsFrom, readFrom)
    }

    /** [seq] 之前最后生效的 effort：读日志里的 SessionCreated 与 SettingsChanged 折出来 */
    private suspend fun effortBefore(log: SessionLog, seq: Long): Effort? {
        var effort: Effort? = null
        for (record in log.read(1).takeWhile { it.seq < seq }.toList()) {
            when (val e = record.event) {
                is SessionCreated -> effort = e.effort
                is SettingsChanged -> {
                    if (e.clearEffort) effort = null
                    e.effort?.let { effort = it }
                }
                else -> Unit
            }
        }
        return effort
    }

    // ---- 发 ----

    private suspend fun flushLocked(signals: Set<Signal>, nowMs: Long = clock()) {
        for (signal in Signal.entries.filter { it in signals }) {
            val buf = when (signal) {
                Signal.Metrics -> metricsBuf
                Signal.Logs -> logsBuf
                Signal.Traces -> spansBuf
            }
            lastFlush[signal] = nowMs
            if (buf.isEmpty()) continue
            val batch = buf.toList()
            // 发完才清：发送中途被取消，批还在缓冲里，收尾那次 pump 会再发（至少一次，好过丢）
            exporter.export(batch)
            buf.subList(0, batch.size).clear()
        }
    }
}
