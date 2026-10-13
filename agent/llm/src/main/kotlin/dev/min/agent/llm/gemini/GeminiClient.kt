package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.protocol.AgentJson
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** 原生 Gemini 的 EOF 是流尾，仍须确认 finishReason；重试与工具执行属于内核。 */
class GeminiClient(
    private val provider: ProviderDescriptor,
    private val transport: HttpTransport,
    private val secrets: Secrets,
) : ModelClient {
    private val overflow = geminiOverflowMatcher(provider.quirks.overflowPatterns)

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        val body = AgentJson.encodeToString(JsonObject.serializer(), buildGeminiBody(request))
        val url = geminiUrl(provider.baseUrl, request.profile.wireModelId)
        val key = secrets.apiKey(provider.id)?.reveal()
            ?: throw ModelException(ModelFailureKind.Auth, "没有 ${provider.id} 的 API 密钥", beforeFirstByte = true)
        val parser = GeminiStreamParser(request.profile, overflow, fingerprint(body))
        val http = HttpRequest("POST", url, headers(key), body.toByteArray(Charsets.UTF_8), request.timeoutMs)
        transport.stream(http).catch { error ->
            throw when (error) {
                is CancellationException -> error
                is ModelException -> error
                is HttpStatusException -> geminiHttpError(error.status, error.headers, error.body, overflow)
                is IOException -> networkFailure("网络中断", parser.seen == 0, error)
                else -> error
            }
        }.collect { event -> parser.feed(event).forEach { emit(it) } }
        // catch 仅围住上游：消费者抛 IOException 或取消时不能误分类成供应商失败。
        emit(parser.complete())
    }

    private fun headers(key: String): List<Pair<String, String>> {
        val values = linkedMapOf<String, Pair<String, String>>()
        fun put(name: String, value: String) { values[name.lowercase()] = name to value }
        when (provider.auth) {
            AuthScheme.XApiKey -> put(provider.quirks.gemini.apiKeyHeader, key)
            AuthScheme.Bearer -> put("authorization", "Bearer $key")
        }
        put("content-type", "application/json"); put("accept", "text/event-stream")
        provider.extraHeaders.forEach { (name, value) -> put(name, value) }
        put("user-agent", "min-agent")
        return values.values.toList()
    }
}

internal fun geminiUrl(baseUrl: String, modelId: String): String {
    val id = modelId.removePrefix("models/")
    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Gemini wireModelId 必须是单个模型路径段" }
    val base = baseUrl.trimEnd('/')
    val versioned = if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
    return "$versioned/models/$id:streamGenerateContent?alt=sse"
}

internal fun fingerprint(body: String): String = MessageDigest.getInstance("SHA-256")
    .digest(body.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
