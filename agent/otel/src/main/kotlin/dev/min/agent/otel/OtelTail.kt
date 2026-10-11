package dev.min.agent.otel

import dev.min.agent.spi.SessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList

/**
 * 把一个会话日志接到导出器上：记住读到哪一条（游标），每次 [pump] 只处理游标之后的新记录。
 *
 * 为什么是游标而不是每次全量：同一个日志会被多次 pump（回合结束、退出时），全量会把旧事件反复导出。
 * 游标只在内存里：进程重启后从宿主给的 [fromSeq] 重新开始（CLI 取会话当前的末尾，见 Main），
 * 不会把以前的会话在本进程里重放一遍。
 *
 * 游标不因导出失败回退：失败的那批只计数、不重发（至多一次）。否则一个坏端点会让同一批事件永远堆积。
 */
class OtelTail(
    private val log: SessionLog,
    private val config: OtelConfig,
    private val exporter: OtelExporter,
    fromSeq: Long = 1,
) {
    private var cursor = fromSeq
    private val mapper = OtelMapper(config.options(log.header.sessionId.value))

    /** 导出游标之后的新记录，返回处理了几条。遥测关闭时直接返回 0，既不读日志也不映射 */
    suspend fun pump(): Int {
        if (!config.active) return 0
        val records = try {
            log.read(cursor).toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exporter.noteFailure("读会话日志失败：${e.javaClass.simpleName}")
            return 0
        }
        if (records.isEmpty()) return 0
        val items = ArrayList<OtelItem>()
        for (record in records) {
            cursor = record.seq + 1
            // 映射是纯计算，但它是新代码：出错只丢这一条的遥测，不能让会话收尾跟着失败
            try {
                items += mapper.feed(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exporter.noteFailure("映射第 ${record.seq} 条失败：${e.javaClass.simpleName}")
            }
        }
        exporter.export(items)
        return records.size
    }
}
