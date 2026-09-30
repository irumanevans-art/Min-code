package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AnthropicQuirks
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.FakeHttpTransport
import dev.min.agent.llm.FakeSecrets
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ProviderQuirks
import dev.min.agent.llm.Wire
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** 两边都解析成 JsonElement 再比：不受键顺序与空白影响 */
internal fun assertJson(expected: String, actual: String) {
    assertEquals(AgentJson.parseToJsonElement(expected), AgentJson.parseToJsonElement(actual))
}

internal fun json(text: String): JsonElement = AgentJson.parseToJsonElement(text)

internal val claudeRef = ModelRef("anthropic", "claude-sonnet-5-5")

internal fun profile(
    ref: ModelRef = claudeRef,
    maxOutputTokens: Int = 8_000,
    effortPatches: Map<dev.min.agent.protocol.Effort, kotlinx.serialization.json.JsonObject> = emptyMap(),
    reasoningEcho: dev.min.agent.llm.ReasoningEcho = dev.min.agent.llm.ReasoningEcho.Always,
) = ModelProfile(
    ref = ref, family = ModelFamily.Claude, contextWindow = 200_000, maxOutputTokens = maxOutputTokens,
    effortPatches = effortPatches, reasoningEcho = reasoningEcho,
)

internal fun provider(
    quirks: AnthropicQuirks = AnthropicQuirks(),
    baseUrl: String = "https://api.example.com/",
    auth: AuthScheme = AuthScheme.XApiKey,
    extraHeaders: Map<String, String> = emptyMap(),
    overflowPatterns: List<String> = emptyList(),
) = ProviderDescriptor(
    id = "anthropic", baseUrl = baseUrl, wire = Wire.AnthropicMessages, auth = auth, extraHeaders = extraHeaders,
    quirks = ProviderQuirks(anthropic = quirks, overflowPatterns = overflowPatterns),
)

internal fun userText(text: String) = ModelMessage(Role.User, listOf(TextPart(text)))

internal fun request(
    profile: ModelProfile = profile(),
    messages: List<ModelMessage> = listOf(userText("你好")),
    system: List<String> = emptyList(),
    tools: List<dev.min.agent.llm.ToolSpec> = emptyList(),
    toolChoice: dev.min.agent.llm.ToolChoice = dev.min.agent.llm.ToolChoice.Auto,
    effort: dev.min.agent.protocol.Effort? = null,
    maxOutputTokens: Int? = null,
    cache: dev.min.agent.llm.CacheHints = dev.min.agent.llm.CacheHints(),
) = ModelRequest(profile, system, messages, tools, toolChoice, effort, maxOutputTokens, cache)

/** 一次最小的成功流，供只关心请求体的测试用 */
internal val okStream = FakeHttpTransport.Reply.Sse(
    dev.min.agent.llm.parseSse(
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":1,"output_tokens":1}}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

        event: message_stop
        data: {"type":"message_stop"}
        """.trimIndent(),
    ),
)

/** 发一次请求并收集事件；失败时返回已收到的事件与异常 */
internal class Outcome(val events: List<ModelStreamEvent>, val error: Throwable?) {
    val completed get() = events.filterIsInstance<ModelStreamEvent.Completed>().single()
    fun modelError(): ModelException = error as? ModelException ?: run {
        fail("期望 ModelException，实际 $error")
        throw IllegalStateException()
    }
}

internal fun run(
    transport: FakeHttpTransport,
    request: ModelRequest = request(),
    provider: ProviderDescriptor = provider(),
): Outcome = runBlocking {
    val events = mutableListOf<ModelStreamEvent>()
    var error: Throwable? = null
    try {
        AnthropicMessagesClient(provider, transport, FakeSecrets()).stream(request).toList(events)
    } catch (e: Throwable) {
        error = e
    }
    Outcome(events, error)
}

/** 只关心请求体：发一次 okStream 并返回请求体文本；请求构造阶段的异常直接抛出 */
internal fun sentBody(
    request: ModelRequest,
    provider: ProviderDescriptor = provider(),
): String {
    val transport = FakeHttpTransport(okStream)
    val outcome = run(transport, request, provider)
    outcome.error?.let { throw it }
    return transport.lastBody
}
