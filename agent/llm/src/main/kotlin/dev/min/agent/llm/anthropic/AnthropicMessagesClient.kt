package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ResponseMeta
import dev.min.agent.llm.isAnthropicFirstParty
import dev.min.agent.llm.networkFailure
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
    private val overflow = anthropicOverflowMatcher(provider.quirks.overflowPatterns)
    private val firstParty = provider.isAnthropicFirstParty()

    // `x-client-request-id` 与 `traceparent` 只发给 Anthropic 本家：第三方 /anthropic 兼容中转可能拒绝不认识的头，
    // 也不会回显它们；自定义端点要 trace 须用户显式同意（CLAUDE_CODE_PROPAGATE_TRACEPARENT=1，原文）
    override val sendsClientRequestId: Boolean get() = firstParty

    override fun propagatesTrace(allowCustomAnthropicEndpoint: Boolean): Boolean = firstParty || allowCustomAnthropicEndpoint

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        // 请求体先于凭证构造：模型数据写错（IllegalArgumentException）不该被凭证问题盖住
        val body = AgentJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildAnthropicBody(request, quirks))
        val key = secrets.apiKey(provider.id)?.reveal()
            ?: throw ModelException(ModelFailureKind.Auth, "没有 ${provider.id} 的 API 密钥", beforeFirstByte = true)
        val meta = ResponseMeta("request-id")
        val http = HttpRequest(
            method = "POST",
            url = provider.baseUrl.trimEnd('/') + "/v1/messages",
            headers = headers(key, request),
            body = body.toByteArray(Charsets.UTF_8),
            timeoutMs = request.timeoutMs,
            onResponseHeaders = meta::accept,
        )
        val parser = AnthropicStreamParser(request.profile, overflow)
        emitAll(
            transport.stream(http)
                .transform { event ->
                    parser.feed(event).forEach {
                        // 响应头比第一个 SSE 事件早到：Started 带上供应商请求 id 与 traceresponse（只给遥测）
                        emit(if (it is ModelStreamEvent.Started) it.copy(responseId = meta.responseId, traceResponse = meta.traceResponse) else it)
                    }
                }
                .catch { e -> throw meta.annotate(classify(e, parser, meta)) },
        )
        if (!parser.finished) {
            // 流正常结束却没有 message_stop：等同断流，内核据 beforeFirstByte 决定整条重发还是从安全点重开
            throw meta.annotate(ModelException(
                ModelFailureKind.Network,
                "流在 message_stop 之前结束",
                beforeFirstByte = parser.seen == 0,
            ))
        }
    }

    /** 顺序按规格：内容类型、接受类型、协议版本与 beta、供应商额外头、user-agent；同名后者覆盖前者 */
    private fun headers(key: String, request: ModelRequest): List<Pair<String, String>> {
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
        if (sendsClientRequestId) request.clientRequestId?.let { put("x-client-request-id", it) }
        request.trace?.takeIf { propagatesTrace(it.allowCustomAnthropicEndpoint) }?.let { put("traceparent", it.traceparent) }
        return headers.values.toList()
    }

    private fun classify(e: Throwable, parser: AnthropicStreamParser, meta: ResponseMeta): Throwable = when (e) {
        is CancellationException -> e
        is ModelException -> e
        is HttpStatusException -> {
            // 失败响应的头也带 request-id（供应商排错要它）
            meta.accept(e.headers)
            classifyHttpFailure(e.status, e.headers, e.body, overflow)
        }
        is IOException -> networkFailure("网络中断：${e.message ?: e::class.simpleName}", parser.seen == 0, e)
        else -> e
    }
}
