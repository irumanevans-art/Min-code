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

/** 只做协议投影和错误分类：重试与记账由内核统一负责，避免失败时重复执行。 */
class OpenAIChatClient(
    private val provider: ProviderDescriptor,
    private val transport: HttpTransport,
    private val secrets: Secrets,
) : ModelClient {
    private val quirks = provider.quirks.openAiChat
    private val overflow = OverflowMatcher(provider.quirks.overflowPatterns)

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        // 模型数据错误应先暴露，不能被缺凭证的错误盖住。
        val body = AgentJson.encodeToString(JsonObject.serializer(), buildOpenAIChatBody(request, quirks))
        val key = secrets.apiKey(provider.id)?.reveal()
            ?: throw ModelException(ModelFailureKind.Auth, "没有 ${provider.id} 的 API 密钥", beforeFirstByte = true)
        val http = HttpRequest(
            method = "POST",
            url = provider.baseUrl.trimEnd('/') + "/chat/completions",
            headers = headers(key),
            body = body.toByteArray(Charsets.UTF_8),
            timeoutMs = request.timeoutMs,
        )
        val parser = OpenAIChatStreamParser(request.profile, quirks, overflow)
        emitAll(
            transport.stream(http).transformWhile { event ->
                parser.feed(event).forEach { emit(it) }
                // DONE 后立即停止收集，避免传输层继续读尾部时的断开把成功变成失败。
                !parser.finished
            }.catch { e ->
                throw when (e) {
                    is CancellationException -> e
                    is ModelException -> e
                    is HttpStatusException -> classifyHttpFailure(e.status, e.headers, e.body, overflow)
                    is IOException -> networkFailure("网络中断", parser.seen == 0, e)
                    else -> e
                }
            },
        )
        if (!parser.finished) {
            throw ModelException(ModelFailureKind.Network, "流在 [DONE] 之前结束", beforeFirstByte = parser.seen == 0)
        }
    }

    private fun headers(key: String): List<Pair<String, String>> {
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
        return headers.values.toList()
    }

    private companion object {
        // 与 Anthropic 编解码器一致；细粒度超时没有请求接口，由内核取消兜底。
        const val REQUEST_TIMEOUT_MS = 600_000L
    }
}
