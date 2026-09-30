package dev.min.agent.store

import dev.min.agent.protocol.AgentJson
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.LogHeader
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 日志格式的当前主版本。读到更大的就拒绝打开（agent/store/FORMAT.md） */
const val LOG_FORMAT_VERSION = 1

class UnsupportedLogFormatException(val file: Path, val found: Int) :
    IOException("$file 的格式版本 $found 比本程序支持的 $LOG_FORMAT_VERSION 新：请升级 Min")

class CorruptLogException(val file: Path, detail: String) : IOException("$file 已损坏：$detail")

/**
 * 按「完整行」读日志：只认以 '\n' 结尾的行。最后一个换行符之后的字节是正在写（或被杀在写一半）的尾巴，一律不读。
 * 这样读者与追加者并发时也不会读到半条记录。
 */
internal object LogLines {
    private const val BUFFER_SIZE = 64 * 1024
    private const val NEWLINE = '\n'.code

    /** 逐个完整行回调，返回最后一个完整行结束处的字节偏移（= 可保留的文件长度） */
    fun forEachCompleteLineBlocking(file: Path, onLine: (line: String, endOffset: Long) -> Unit): Long {
        var consumed = 0L
        var validEnd = 0L
        val pending = ByteArrayOutputStream()
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var segmentStart = 0
                for (i in 0 until n) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        pending.write(buffer, segmentStart, i - segmentStart)
                        val end = consumed + i + 1
                        onLine(pending.toString(Charsets.UTF_8), end)
                        validEnd = end
                        pending.reset()
                        segmentStart = i + 1
                    }
                }
                pending.write(buffer, segmentStart, n - segmentStart)
                consumed += n
            }
        }
        return validEnd
    }

    /** 只读第一行（文件头），列会话时不必把整个日志读一遍；没有完整的第一行返回 null */
    fun firstCompleteLine(file: Path): String? {
        val bytes = ByteArrayOutputStream()
        Files.newInputStream(file).use { input ->
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == NEWLINE) return bytes.toString(Charsets.UTF_8)
                bytes.write(b)
            }
        }
    }

    /** 挂起版，给 Flow 用（调用方负责切到 IO 调度器） */
    suspend fun forEachCompleteLine(file: Path, onLine: suspend (line: String, endOffset: Long) -> Unit) {
        val lines = ArrayList<Pair<String, Long>>()
        // 先同步读出这一刻的全部完整行再逐个回调：回调里可能挂起（下游收集慢），不能攥着输入流挂起
        forEachCompleteLineBlocking(file) { line, end -> lines += line to end }
        lines.forEach { (line, end) -> onLine(line, end) }
    }

    fun decodeHeader(line: String, file: Path): LogHeader {
        val header = try {
            AgentJson.decodeFromString(LogHeader.serializer(), line)
        } catch (e: SerializationException) {
            throw CorruptLogException(file, "文件头解析失败：${e.message}")
        } catch (e: IllegalArgumentException) {
            throw CorruptLogException(file, "文件头解析失败：${e.message}")
        }
        if (header.formatVersion > LOG_FORMAT_VERSION) throw UnsupportedLogFormatException(file, header.formatVersion)
        return header
    }

    fun decodeRecord(line: String, file: Path): EventRecord =
        try {
            AgentJson.decodeFromString(EventRecord.serializer(), line)
        } catch (e: SerializationException) {
            throw CorruptLogException(file, "记录解析失败（可能由更新的版本写入）：${e.message}")
        } catch (e: IllegalArgumentException) {
            throw CorruptLogException(file, "记录解析失败：${e.message}")
        }
}
