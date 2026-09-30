package dev.min.agent.store

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Clock
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * 会话目录的根：`<root>/<sessionId>/events.jsonl`（docs/agent/03-核心契约.md §8）。
 * Android 宿主传 App 私有目录下的路径——**不放进 rootfs**，免得被模型的 Bash 改掉。
 */
class FileSessionStore(private val root: Path, private val clock: Clock) : SessionStore {

    override suspend fun create(header: LogHeader): SessionLog = withContext(Dispatchers.IO) {
        require(header.formatVersion == LOG_FORMAT_VERSION) { "新会话必须用当前格式版本 $LOG_FORMAT_VERSION" }
        val file = eventsFile(header.sessionId)
        Files.createDirectories(file.parent)
        // CREATE_NEW：同一个 id 建两次是调用方的 bug，宁可报错也不覆盖已有会话
        FileChannel.open(file, CREATE_NEW, WRITE).use { channel ->
            val line = AgentJson.encodeToString(LogHeader.serializer(), header) + "\n"
            channel.writeFully(ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
            channel.force(false)
        }
        JsonlSessionLog.open(file, clock)
    }

    override suspend fun open(id: SessionId): SessionLog? = withContext(Dispatchers.IO) {
        val file = eventsFile(id)
        if (Files.exists(file)) JsonlSessionLog.open(file, clock) else null
    }

    override suspend fun list(): List<LogHeader> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(root)) return@withContext emptyList()
        Files.list(root).use { dirs ->
            dirs.toList()
                .map { it.resolve(EVENTS_FILE) }
                .filter { Files.isRegularFile(it) }
                .mapNotNull { file -> LogLines.firstCompleteLine(file)?.let { LogLines.decodeHeader(it, file) } }
                .sortedBy { it.createdAtMs }
        }
    }

    private fun eventsFile(id: SessionId): Path {
        // id 会变成目录名：只收安全字符，杜绝 `../` 之类把会话写到根目录外面
        require(SAFE_ID.matches(id.value)) { "非法会话 id：${id.value}" }
        return root.resolve(id.value).resolve(EVENTS_FILE)
    }

    companion object {
        const val EVENTS_FILE = "events.jsonl"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
