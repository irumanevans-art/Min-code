package dev.min.agent.otel

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
