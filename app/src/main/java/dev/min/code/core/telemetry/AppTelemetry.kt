package dev.min.code.core.telemetry

import dev.min.agent.otel.OtelPeriodic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App 的自动导出节奏：**有会话在跑回合的时候**按 CLI 同样的间隔导出（`OTEL_METRIC_EXPORT_INTERVAL` / `OTEL_LOGS_EXPORT_INTERVAL` /
 * `OTEL_TRACES_EXPORT_INTERVAL` 的默认值：指标 60 秒、日志与 traces 5 秒），回合都结束了就把没发的发完、收工。
 *
 * 不保活、不额外唤醒：
 * - 没有前台服务、AlarmManager、WorkManager；节拍只是进程里的协程 `delay`，进程被系统冻结时它也不走；
 * - 没有会话在跑（[busy] 为假）时连节拍都没有——闲着的 App 里没有任何定时器；
 * - 进程没了就不导：丢的至多是缓冲里没到间隔的那一段（游标每个节拍落盘，下次从这里续）。
 *
 * 为什么每个「忙」的阶段开一个新的导出会话、闲下来就 [TelemetrySession.finish]：游标只存在文件里，手动「立即导出」
 * 也读同一个文件，两者不会各带一份内存里的进度而重复发送；有阶段在跑时手动导出直接用它的会话（[exportNow]），同一把锁。
 *
 * 设置里关了遥测：当前阶段取消，取消时补发一次；之后什么都不做（默认关）。
 */
class AppTelemetry(
    private val scope: CoroutineScope,
    private val exporter: MinTelemetryExporter,
    private val settings: Flow<TelemetrySettings>,
    /** 头部令牌：每个阶段开始时现取（界面改了头部，下一个阶段起生效） */
    private val headers: suspend () -> String,
    /** 有没有会话在跑回合 */
    private val busy: Flow<Boolean>,
    /** 节拍；null = 取配置里最短的间隔（照 CLI，通常 5 秒）。测试给小值 */
    private val tickMs: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var active: TelemetrySession? = null

    fun start(): Job = scope.launch {
        settings.distinctUntilChanged().collectLatest { s ->
            if (!s.enabled) return@collectLatest
            busy.distinctUntilChanged().collectLatest { isBusy ->
                if (isBusy) phase(s)
            }
        }
    }

    /** 一个「忙」的阶段：开会话、按节拍导出；被取消（闲下来、设置变了、进程停）时在 finally 里补发并落游标 */
    private suspend fun phase(s: TelemetrySettings) {
        val session = lock.withLock { exporter.open(s, headers(), baseline = true) } ?: return
        lock.withLock { active = session }
        try {
            val tick = tickMs ?: OtelPeriodic.tickMs(session.config)
            while (true) {
                delay(tick)
                lock.withLock { session.tick(now()) }
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    try {
                        session.finish()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 遥测不许影响别的：补发失败就算了，游标没落盘的那一段下次会重读
                    }
                    if (active === session) active = null
                }
            }
        }
    }

    /** 「立即导出」。有自动导出的阶段在跑就用它的会话（同一份进度），否则单次导出（从头读没有游标的会话） */
    suspend fun exportNow(s: TelemetrySettings, headersNow: String): TelemetryExportReport = lock.withLock {
        val running = active
        if (running != null && s.enabled) running.finish() else exporter.export(s, headersNow)
    }
}
