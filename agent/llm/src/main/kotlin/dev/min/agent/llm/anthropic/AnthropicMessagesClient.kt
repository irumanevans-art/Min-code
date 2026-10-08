package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
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
import kotlinx.coroutines.flow.transform
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Anthropic Messages 协议的编解码器：Anthropic 本家与各家 `/anthropic` 兼容端点共用（编解码器规范 §3）。
 * 只做「规范请求 → 线格式」与「SSE → 流式事件 / [ModelException]」；重试、记账、落盘都是内核的事。
 * 凭证只进请求头：本类不保存它，也不出现在任何异常信息里。
 */
class AnthropicMessagesClient(
    private val provider: ProviderDescriptor,
    private val transport: HttpTransport,
    private val secrets: Secrets,
) : ModelClient {
    private val quirks = provider.quirks.anthropic
    private val overflow = OverflowMatcher(provider.quirks.overflowPatterns)

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        // 请求体先于凭证构造：模型数据写错（IllegalArgumentException）不该被凭证问题盖住
        val body = AgentJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildAnthropicBody(request, quirks))
        val key = secrets.apiKey(provider.id)?.reveal()
            ?: throw ModelException(ModelFailureKind.Auth, "没有 ${provider.id} 的 API 密钥", beforeFirstByte = true)
        val http = HttpRequest(
            method = "POST",
            url = provider.baseUrl.trimEnd('/') + "/v1/messages",
            headers = headers(key),
            body = body.toByteArray(Charsets.UTF_8),
            timeoutMs = request.timeoutMs,
        )
        val parser = AnthropicStreamParser(request.profile, overflow)
        emitAll(
            transport.stream(http)
                .transform { event -> parser.feed(event).forEach { emit(it) } }
                .catch { e -> throw classify(e, parser) },
        )
        if (!parser.finished) {
            // 流正常结束却没有 message_stop：等同断流，内核据 beforeFirstByte 决定整条重发还是从安全点重开
            throw ModelException(
                ModelFailureKind.Network,
                "流在 message_stop 之前结束",
                beforeFirstByte = parser.seen == 0,
            )
        }
    }

    /** 顺序按规格：内容类型、接受类型、协议版本与 beta、供应商额外头、user-agent；同名后者覆盖前者 */
    private fun headers(key: String): List<Pair<String, String>> {
        val headers = LinkedHashMap<String, Pair<String, String>>()
        fun put(name: String, value: String) {
            headers[name.lowercase()] = name to value
        }
        when (provider.auth) {
            AuthScheme.XApiKey -> put("x-api-key", key)
            AuthScheme.Bearer -> put("authorization", "Bearer $key")
        }
        put("content-type", "application/json")
        put("accept", "text/event-stream")
        put("anthropic-version", quirks.anthropicVersion)
        if (quirks.betaHeaders.isNotEmpty()) put("anthropic-beta", quirks.betaHeaders.joinToString(","))
        provider.extraHeaders.forEach { (name, value) -> put(name, value) }
        put("user-agent", "min-agent")
        return headers.values.toList()
    }

    private fun classify(e: Throwable, parser: AnthropicStreamParser): Throwable = when (e) {
        is CancellationException -> e
        is ModelException -> e
        is HttpStatusException -> classifyHttpFailure(e.status, e.headers, e.body, overflow)
        is IOException -> ModelException(
            ModelFailureKind.Network,
            "网络中断：${e.message ?: e::class.simpleName}",
            beforeFirstByte = parser.seen == 0,
            cause = e,
        )
        else -> e
    }

    private companion object {
        /** 流式请求的整体超时：长思考可能几分钟才出第一个 token，取宽松值，真卡死由内核的取消兜底 */
        const val REQUEST_TIMEOUT_MS = 600_000L
    }
}
