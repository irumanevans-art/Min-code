package dev.min.agent.testkit

import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStatusException
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.agent.spi.SseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * 编解码器测试用的 HTTP 替身：按脚本回放，并记下发出去的每个请求（测试据此检查请求体与头）。
 *
 * 用法：`FakeHttpTransport(Reply.Sse(events))`。脚本按请求顺序逐个消费，用完再请求会抛错——
 * 测试里多发了一次请求应该是失败，而不是悄悄拿到一个空回复。
 */
class FakeHttpTransport(vararg replies: Reply) : HttpTransport {
    sealed interface Reply {
        /** 2xx，流式回放这些事件；[failAfter] 非空时在回放完之后抛出（模拟吐了一半断流） */
        data class Sse(
            val events: List<SseEvent>,
            val failAfter: IOException? = null,
            /** 响应头：回放事件之前交给 [HttpRequest.onResponseHeaders]（真传输收到状态行与头之后就回调） */
            val headers: List<Pair<String, String>> = emptyList(),
        ) : Reply

        /** 非 2xx，带响应体 */
        data class Status(val status: Int, val body: String, val headers: List<Pair<String, String>> = emptyList()) : Reply

        /** 连接阶段就失败（首字节前） */
        data class NetworkError(val error: IOException) : Reply

        /** 非流式的 2xx 响应 */
        data class Body(val body: String) : Reply
    }

    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<HttpRequest>()

    /** 最近一次请求的请求体（UTF-8 文本） */
    val lastBody: String get() = requests.last().body?.toString(Charsets.UTF_8) ?: error("没有请求体")

    fun header(name: String): String? =
        requests.last().headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun next(request: HttpRequest): Reply {
        requests += request
        return queue.removeFirstOrNull() ?: error("脚本用完了，却又发了请求：$request")
    }

    override suspend fun send(request: HttpRequest): HttpResponse = when (val reply = next(request)) {
        is Reply.Body -> HttpResponse(200, emptyList(), reply.body.toByteArray())
        is Reply.Status -> throw HttpStatusException(reply.status, reply.headers, reply.body)
        is Reply.NetworkError -> throw reply.error
        is Reply.Sse -> error("这个请求不是流式的，脚本却给了 SSE")
    }

    override fun stream(request: HttpRequest): Flow<SseEvent> = flow {
        when (val reply = next(request)) {
            is Reply.Sse -> {
                request.onResponseHeaders?.invoke(reply.headers)
                reply.events.forEach { emit(it) }
                reply.failAfter?.let { throw it }
            }
            is Reply.Status -> throw HttpStatusException(reply.status, reply.headers, reply.body)
            is Reply.NetworkError -> throw reply.error
            is Reply.Body -> error("这个请求是流式的，脚本却给了非流式响应")
        }
    }
}

/** 测试用的固定凭证 */
class FakeSecrets(private val key: String = "sk-test") : Secrets {
    override suspend fun apiKey(providerId: String): Secret = Secret(key)
}

/** 把一段抓包形式的 SSE 文本（`event: x\ndata: {...}\n\n` 块）拆成事件，夹具可以直接贴官方文档里的示例 */
fun parseSse(text: String): List<SseEvent> =
    text.trim().split(Regex("\\r?\\n\\r?\\n")).filter { it.isNotBlank() }.map { block ->
        var event: String? = null
        val data = StringBuilder()
        block.lines().forEach { line ->
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        SseEvent(event, data.toString())
    }
