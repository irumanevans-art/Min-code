package dev.min.agent.mcp

import dev.min.agent.spi.LiveProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

class StdioMcpTransport(
    private val process: LiveProcess,
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    private val stderrMaxLines: Int = 100,
    private val stderrMaxBytes: Int = 64 * 1024,
    private val onDebug: (String) -> Unit = {},
) : McpTransport {
    private val collected = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val writes = Mutex()
    private val tail = ArrayDeque<String>()
    private var tailBytes = 0
    init { require(maxMessageBytes > 0 && stderrMaxLines > 0 && stderrMaxBytes > 0) { "MCP stdio 消息和日志上限必须大于零。" } }
    fun stderrTail(): List<String> = synchronized(tail) { tail.toList() }

    override val incoming: Flow<JsonObject> = flow {
        check(collected.compareAndSet(false, true)) { "MCP stdio 输入只能收集一次。" }
        try {
            coroutineScope {
                val logs = launch {
                    // 超长行丢弃余下部分，避免凭证的续行脱离头名后被当成普通日志保存。
                    val partial = java.io.ByteArrayOutputStream()
                    var dropping = false
                    process.stderr.collect { bytes ->
                        for (b in bytes) {
                            if (b == 10.toByte() || b == 13.toByte()) {
                                if (!dropping && partial.size() > 0) appendLog(partial.toString("UTF-8"))
                                partial.reset(); dropping = false
                            } else if (!dropping) {
                                partial.write(b.toInt())
                                if (partial.size() >= stderrMaxBytes) {
                                    appendLog("MCP stderr 一行超过 $stderrMaxBytes 字节上限，已省略。")
                                    partial.reset(); dropping = true
                                }
                            }
                        }
                    }
                    if (!dropping && partial.size() > 0) appendLog(partial.toString("UTF-8"))
                }
                process.stdout.lines(maxMessageBytes).collect { line ->
                    if (line.isNotBlank()) decodeMessages(line, onDebug, tolerateNoise = true) { emit(it) }
                }
                // stdout EOF 也代表连接结束；主动关停保证一个仍活着但关 stdout 的服务器不让这里无期限等待。
                if (!process.exit.isCompleted) close()
                val code = process.exit.await()
                logs.join()
                throw McpTransportClosed("MCP stdio 服务器连接已断开（退出码 $code）。", code, stderrTail())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: McpTransportClosed) { throw e }
        catch (e: Exception) { throw McpTransportClosed("MCP stdio 服务器读取失败。", if (process.exit.isCompleted) process.exit.await() else null, stderrTail()) }
        finally { withContext(NonCancellable) { close() } }
    }

    override suspend fun send(message: JsonObject) = writes.withLock {
        if (closed.get()) throw McpTransportClosed("MCP stdio 连接已关闭。", stderrTail = stderrTail())
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size - 1 > maxMessageBytes) throw McpTransportClosed("MCP stdio 待发送消息超过 $maxMessageBytes 字节上限。")
        try { process.write(bytes) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw McpTransportClosed("MCP stdio 服务器写入失败。", stderrTail = stderrTail()) }
    }
    override suspend fun close() {
        if (closed.compareAndSet(false, true)) withContext(NonCancellable) { process.shutdown() }
    }
    private fun appendLog(raw: String) = synchronized(tail) {
        val line = safeLog(raw)
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (bytes > stderrMaxBytes) return@synchronized
        tail.addLast(line); tailBytes += bytes
        while (tail.size > stderrMaxLines || tailBytes > stderrMaxBytes) tailBytes -= tail.removeFirst().toByteArray(Charsets.UTF_8).size
    }
}
