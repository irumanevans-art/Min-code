package dev.min.agent.llm.openai

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.networkFailure
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.protocol.AgentJson
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpStatusException
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Secrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** 复用 Chat 的 HTTP 分类，Responses 只负责自己的 item 与事件，不在这里重试。 */
class OpenAIResponsesClient(
    private val provider: ProviderDescriptor,
    private val transport: HttpTransport,
    private val secrets: Secrets,
) : ModelClient {
    private val overflow = OverflowMatcher(provider.quirks.overflowPatterns)

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        val body = AgentJson.encodeToString(JsonObject.serializer(), buildResponsesBody(request))
        val key = secrets.apiKey(provider.id)?.reveal()
            ?: throw ModelException(ModelFailureKind.Auth, "没有 ${provider.id} 的 API 密钥", beforeFirstByte = true)
        val headers = LinkedHashMap<String, Pair<String, String>>()
        fun put(name: String, value: String) { headers[name.lowercase()] = name to value }
        when (provider.auth) {
            AuthScheme.XApiKey -> put("x-api-key", key)
            AuthScheme.Bearer -> put("authorization", "Bearer $key")
        }
        put("content-type", "application/json")
        put("accept", "text/event-stream")
        provider.extraHeaders.forEach { (name, value) -> put(name, value) }
        put("user-agent", "min-agent")
        val http = HttpRequest("POST", provider.baseUrl.trimEnd('/') + "/responses", headers.values.toList(), body.toByteArray(), request.timeoutMs)
        val parser = OpenAIResponsesStreamParser(request.profile, overflow)
        emitAll(transport.stream(http).transformWhile { event ->
            parser.feed(event).forEach { emit(it) }
            !parser.finished
        }.catch { e -> throw when (e) {
            is CancellationException -> e
            is ModelException -> e
            is HttpStatusException -> classifyHttpFailure(e.status, e.headers, e.body, overflow)
            is IOException -> networkFailure("网络中断", parser.seen == 0, e)
            else -> e
        } })
        if (!parser.finished) throw ModelException(ModelFailureKind.Network, "Responses 流在终止事件之前结束", beforeFirstByte = parser.seen == 0)
    }
}
