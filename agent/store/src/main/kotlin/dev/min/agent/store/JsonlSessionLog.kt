package dev.min.agent.store

import dev.min.agent.protocol.AgentJson
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE

/**
 * 一个会话的事件日志：一个 JSONL 文件，第一行是 [LogHeader]，之后每行一条 [EventRecord]。格式见 agent/store/FORMAT.md。
 *
 * ## 两档持久性为什么这样分
 *
 * 手机上最常见的是**进程被杀**，不是断电。数据只要经 write() 交给了内核，进程死了它照样会落到盘上——
 * 所以两档都在 [append] 返回前完成 write()，区别只在 [Durability.Sync] 另做一次 fsync，
 * 用来扛断电 / 内核崩溃。这样流式片段这类高频事件不用付 fsync 的代价，而「进程被杀不丢」对两档都成立。
 *
 * ## 为什么每次追加都重新打开文件
 *
 * 一个回合也就几十次追加，多一次 open 的开销可以忽略；换来的是这个类没有要关闭的资源，
 * 会话被丢弃、进程被杀时都不会泄漏文件句柄。
 */
class JsonlSessionLog private constructor(
    private val file: Path,
    override val header: LogHeader,
    private var lastSeq: Long,
    private val clock: Clock,
) : SessionLog {
    private val mutex = Mutex()

    override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
        if (events.isEmpty()) return emptyList()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val now = clock.nowMs()
                val records = events.mapIndexed { i, event -> EventRecord(lastSeq + 1 + i, now, event) }
                val bytes = buildString {
                    records.forEach { append(AgentJson.encodeToString(EventRecord.serializer(), it)).append('\n') }
                }.toByteArray(Charsets.UTF_8)
                FileChannel.open(file, WRITE).use { channel ->
                    val start = channel.size()
                    try {
                        channel.position(start)
                        channel.writeFully(ByteBuffer.wrap(bytes))
                        if (durability == Durability.Sync) channel.force(false)
                    } catch (e: IOException) {
                        // 写了一半（例如磁盘满）：把文件退回原长度，保证日志里不留半截记录，再把错误交给调用方——
                        // 调用方据此不去执行副作用（docs/agent/07-工程规范.md 第 7 条）
                        rollBack(channel, start, e)
                        throw e
                    }
                }
                lastSeq += records.size
                records
            }
        }
    }

    override fun read(fromSeq: Long): Flow<EventRecord> = flow {
        var headerSeen = false
        LogLines.forEachCompleteLine(file) { line, _ ->
            if (!headerSeen) {
                headerSeen = true
                return@forEachCompleteLine
            }
            val record = LogLines.decodeRecord(line, file)
            if (record.seq >= fromSeq) emit(record)
        }
    }.flowOn(Dispatchers.IO)

    private fun rollBack(channel: FileChannel, length: Long, cause: IOException) {
        try {
            channel.truncate(length)
        } catch (e: IOException) {
            cause.addSuppressed(e)
        }
    }

    companion object {
        /**
         * 打开已有日志：校验文件头版本，扫出最后一个 seq，并修掉被杀在 write() 中途留下的半行尾巴。
         *
         * 只修**尾部**（最后一个换行符之后的字节）。中间某一行完整却解析失败，说明文件损坏或由更新的版本写入，
         * 这时直接报错、不动文件——截掉它会连带删掉后面的有效记录。
         */
        internal fun open(file: Path, clock: Clock): JsonlSessionLog {
            var header: LogHeader? = null
            var lastSeq = 0L
            val validEnd = LogLines.forEachCompleteLineBlocking(file) { line, _ ->
                if (header == null) {
                    header = LogLines.decodeHeader(line, file)
                } else {
                    val record = LogLines.decodeRecord(line, file)
                    if (record.seq != lastSeq + 1) {
                        throw CorruptLogException(file, "seq 不连续：期望 ${lastSeq + 1}，读到 ${record.seq}")
                    }
                    lastSeq = record.seq
                }
            }
            val found = header ?: throw CorruptLogException(file, "没有文件头")
            FileChannel.open(file, WRITE).use { channel ->
                if (channel.size() > validEnd) {
                    channel.truncate(validEnd)
                    channel.force(false)
                }
            }
            return JsonlSessionLog(file, found, lastSeq, clock)
        }
    }
}

internal fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}
