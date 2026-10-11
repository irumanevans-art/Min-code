package dev.min.agent.otel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import kotlinx.coroutines.flow.asFlow
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SseEvent
import kotlinx.coroutines.flow.Flow

/** 脚本化的传输：按顺序回放每次请求的结果，并记下请求。脚本用完再请求就失败，避免悄悄多发 */
class ScriptedTransport(vararg replies: suspend (HttpRequest) -> HttpResponse) : HttpTransport {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        val reply = queue.removeFirstOrNull() ?: error("脚本用完了，却又发了请求")
        return reply(request)
    }

    override fun stream(request: HttpRequest): Flow<SseEvent> = error("遥测不用流式")
    override suspend fun open(request: HttpRequest): HttpStreamingResponse = error("遥测不用原样流")
}

val OK: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, emptyList(), ByteArray(0)) }

/** 内存里的会话日志（可指定父会话、创建时间）：测试往里加事件，导出端只读 */
class FakeSessionLog(
    id: String,
    parent: String? = null,
    createdAtMs: Long = 0,
) : SessionLog {
    val records = mutableListOf<EventRecord>()
    var reads = 0
    var appends = 0

    override val header: LogHeader = LogHeader(
        formatVersion = 1, sessionId = SessionId(id), createdAtMs = createdAtMs, parent = parent?.let { SessionId(it) },
    )

    override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
        appends++
        return emptyList()
    }

    override fun read(fromSeq: Long): Flow<EventRecord> {
        reads++
        return records.filter { it.seq >= fromSeq }.toList().asFlow()
    }

    /** 追加一条事件：seq 自增，时间由测试给定 */
    fun add(atMs: Long, event: SessionEvent): EventRecord {
        val rec = EventRecord(seq = records.size + 1L, atMs = atMs, event = event)
        records += rec
        return rec
    }
}
