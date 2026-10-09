package dev.min.agent.kernel

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WEBSOCKET_MAX_MESSAGE_BYTES
import dev.min.agent.spi.WebSocketConnection
import dev.min.agent.spi.WebSocketEvent
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebSocket 源：每条文本消息一个事件（多行的也只算一个），二进制消息换成占位行，超过 1 MiB 结束，关闭时把关闭码告诉模型
 * （CC 的 Monitor 文档逐条如此）。连接由宿主端口打开并校验过地址（[dev.min.agent.spi.WebSocketPort]）；这里只管读与记录。
 *
 * ## 输出文件
 *
 * 命令源的输出文件是宿主的日志，WebSocket 没有——内核自己写一份，让模型同样能 `Read`。每个事件占一行：
 * 消息里的换行写成两个字符 `\n`（和事件在通知里的样子一致，「一行 = 一个事件」不被多行消息搅乱）。
 * 文件只留最近 [MAX_FILE_CHARS] 个字符（整份原子重写，所以不能无限长）；写文件按 [flushMs] 攒批，不是每条消息写一次。
 */
internal class WebSocketMonitorSource(
    private val connection: WebSocketConnection,
    private val fs: WorkspaceFs,
    override val outputPath: GuestPath,
    private val flushMs: () -> Long,
) : MonitorSource {
    override suspend fun collect(emit: (String) -> Unit): MonitorEnd = coroutineScope {
        val recent = ArrayDeque<String>()
        var chars = 0
        var dirty = false
        val lock = Any()

        suspend fun writeFile() {
            val text = synchronized(lock) {
                if (!dirty) return
                dirty = false
                recent.joinToString("\n", postfix = "\n")
            }
            // 写不进去（磁盘满）不该拖垮 watch：事件照样发给模型，只是文件落后
            catchingNonCancellation { fs.writeAtomic(outputPath, text.encodeToByteArray()) }
        }

        fun record(line: String) {
            synchronized(lock) {
                recent.addLast(line)
                chars += line.length + 1
                while (chars > MAX_FILE_CHARS && recent.size > 1) chars -= recent.removeFirst().length + 1
                dirty = true
            }
            emit(line)
        }

        val writer = launch {
            while (true) {
                delay(flushMs())
                writeFile()
            }
        }
        try {
            var end: MonitorEnd? = null
            while (end == null) {
                end = when (val event = connection.next()) {
                    is WebSocketEvent.Text -> { record(clipMonitorLine(oneLine(event.text))); null }
                    is WebSocketEvent.Binary -> { record("[binary frame, ${event.bytes} bytes]"); null }
                    is WebSocketEvent.Closed -> MonitorEnd.Closed(
                        "关闭码 ${event.code}" + event.reason.takeIf { it.isNotBlank() }?.let { "，原因：$it" }.orEmpty(),
                    )
                    is WebSocketEvent.TooLarge -> MonitorEnd.Failed(
                        "收到一条 ${event.bytes} 字节的消息，超过 ${WEBSOCKET_MAX_MESSAGE_BYTES / 1024 / 1024} MiB 的上限，已断开；" +
                            "如果还要看，请换一个带过滤的订阅",
                    )
                    is WebSocketEvent.Failed -> MonitorEnd.Failed(event.detail)
                }
            }
            end
        } finally {
            writer.cancel()
            withContext(NonCancellable) {
                writeFile()
                connection.close()
            }
        }
    }

    /** 事件 = 一行：消息里的换行写成反斜杠加 n 两个字符 */
    private fun oneLine(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", NEWLINE_ESCAPE)

    private companion object {
        const val MAX_FILE_CHARS = 1_000_000
        const val NEWLINE_ESCAPE = "\\n"
    }
}
