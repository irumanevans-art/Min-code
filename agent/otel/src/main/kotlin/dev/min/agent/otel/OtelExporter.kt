package dev.min.agent.otel

import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把遥测条目导出到 OTLP 端点与（可选的）本机文件。
 *
 * 失败隔离：导出出任何错误（网络、非 2xx、写文件失败）都只记一次失败、不抛出——遥测不许影响会话。
 * 只有协程取消照常往上传，否则会吞掉宿主的取消（见 agent 的 runCatching 规则）。
 *
 * 令牌：头部只进请求，不进失败说明，也不进本机文件；失败说明只写类名或状态码。
 */
class OtelExporter(
    private val config: OtelConfig,
    private val transport: HttpTransport,
    private val timeoutMs: Long = 10_000,
    /**
     * console 导出器的输出口：每条遥测一行 JSON（与本机文件同一种平铺形状）。宿主决定写到哪：
     * CLI 写 stderr（stdout 是会话输出，-p 的结果与管道都读它，混进遥测会毁掉脚本消费）；null = 没有输出口，console 不输出。
     */
    private val console: ((String) -> Unit)? = null,
) {
    /** 累计失败次数（宿主可以拿来在界面上提示「遥测导出失败 N 次」） */
    var failures: Int = 0
        private set

    /** 最近一次失败的说明（不含令牌、不含响应正文） */
    var lastFailure: String? = null
        private set

    suspend fun export(items: List<OtelItem>) {
        // 默认关：连请求与文件都不碰
        if (!config.active || items.isEmpty()) return
        val metrics = items.filterIsInstance<OtelMetric>()
        val logs = items.filterIsInstance<OtelLog>()
        // traces 是 beta：没开 beta 开关时，宿主即使递来 span 也不导出
        val spans = if (config.tracesEnabled) items.filterIsInstance<OtelSpan>() else emptyList()
        val metricsEndpoint = config.metricsEndpoint
        if (metricsEndpoint != null && metrics.isNotEmpty()) {
            post("指标", metricsEndpoint, config.metricsHeaders, OtlpJson.metrics(metrics, config.resourceAttributes))
        }
        val logsEndpoint = config.logsEndpoint
        if (logsEndpoint != null && logs.isNotEmpty()) {
            post("日志", logsEndpoint, config.logsHeaders, OtlpJson.logs(logs, config.resourceAttributes))
        }
        val tracesEndpoint = config.tracesEndpoint
        if (tracesEndpoint != null && spans.isNotEmpty()) {
            post("traces", tracesEndpoint, config.tracesHeaders, OtlpJson.spans(spans, config.resourceAttributes))
        }
        if (config.metricsConsole) print(metrics)
        if (config.logsConsole) print(logs)
        if (config.tracesConsole) print(spans)
        config.localFile?.let { path ->
            val forFile = items.filter { it !is OtelSpan } + spans
            if (forFile.isNotEmpty()) writeFile(path, forFile)
        }
    }

    /** console 导出器：一条一行。输出口出错（管道断了）只计数，不影响导出方 */
    private fun print(items: List<OtelItem>) {
        val sink = console ?: return
        for (item in items) {
            try {
                sink(OtlpJson.fileLine(item))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure("console 输出失败：${e.javaClass.simpleName}")
                return
            }
        }
    }

    private suspend fun post(signal: String, url: String, headers: List<Pair<String, String>>, body: String) {
        try {
            val response = transport.send(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = headers + ("Content-Type" to "application/json"),
                    body = body.toByteArray(Charsets.UTF_8),
                    timeoutMs = timeoutMs,
                ),
            )
            if (response.status !in 200..299) recordFailure("$signal 导出被拒：HTTP ${response.status}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure("$signal 导出失败：${e.javaClass.simpleName}")
        }
    }

    private suspend fun writeFile(path: String, items: List<OtelItem>) {
        try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                file.absoluteFile.parentFile?.mkdirs()
                val lines = items.joinToString(separator = "") { OtlpJson.fileLine(it) + "\n" }
                file.appendText(lines, Charsets.UTF_8)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure("本机遥测文件写入失败：${e.javaClass.simpleName}")
        }
    }

    /** 宿主的读日志、映射等环节出错时，也把失败记在同一个计数上 */
    fun noteFailure(message: String) = recordFailure(message)

    private fun recordFailure(message: String) {
        failures++
        lastFailure = message
    }
}
