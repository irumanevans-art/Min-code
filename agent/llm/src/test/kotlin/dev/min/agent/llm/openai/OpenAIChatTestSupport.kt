package dev.min.agent.llm.openai

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.CacheHints
import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.testkit.FakeSecrets
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.OpenAiChatQuirks
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ProviderQuirks
import dev.min.agent.llm.ReasoningEcho
import dev.min.agent.llm.ToolChoice
import dev.min.agent.llm.ToolSpec
import dev.min.agent.llm.Wire
import dev.min.agent.testkit.parseSse
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.Secrets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import java.io.IOException

internal val modelRef = ModelRef("openai", "test-model")
internal fun json(raw: String) = AgentJson.parseToJsonElement(raw)
internal fun obj(raw: String) = json(raw) as JsonObject
internal fun assertJson(expected: String, actual: String) = assertEquals(json(expected), json(actual))
internal fun userText(text: String) = ModelMessage(Role.User, listOf(TextPart(text)))
internal fun profile(
    echo: ReasoningEcho = ReasoningEcho.Always,
    patches: Map<Effort, JsonObject> = emptyMap(),
    wireModelId: String = modelRef.modelId,
) = ModelProfile(modelRef, wireModelId, ModelFamily.Gpt, 128_000, 8_000, reasoningEcho = echo, effortPatches = patches)
internal fun provider(
    quirks: OpenAiChatQuirks = OpenAiChatQuirks(),
    auth: AuthScheme = AuthScheme.Bearer,
    extraHeaders: Map<String, String> = emptyMap(),
    overflowPatterns: List<String> = emptyList(),
) = ProviderDescriptor("openai", "https://api.example.com/v1///", Wire.OpenAIChat, auth, extraHeaders,
    ProviderQuirks(openAiChat = quirks, overflowPatterns = overflowPatterns))
internal fun request(
    profile: ModelProfile = profile(),
    messages: List<ModelMessage> = listOf(userText("你好")),
    system: List<String> = emptyList(),
    tools: List<ToolSpec> = emptyList(),
    choice: ToolChoice = ToolChoice.Auto,
    effort: Effort? = null,
    max: Int? = null,
    cache: CacheHints = CacheHints(),
) = ModelRequest(profile, system, messages, tools, choice, effort, max, cache)
internal fun stream(raw: String, failAfter: IOException? = null) =
    FakeHttpTransport.Reply.Sse(parseSse(raw.lines().joinToString("\n") { it.trim() }), failAfter)
internal val okStream = stream("""
    data: {"id":"msg_1","choices":[{"delta":{},"finish_reason":"stop"}]}

    data: [DONE]
""")
internal class Outcome(val events: List<ModelStreamEvent>, val error: Throwable?) {
    val completed get() = events.filterIsInstance<ModelStreamEvent.Completed>().single()
    fun modelError() = error as? ModelException ?: error("期望 ModelException，实际 $error")
}
internal fun run(
    transport: FakeHttpTransport,
    request: ModelRequest = request(),
    provider: ProviderDescriptor = provider(),
    secrets: Secrets = FakeSecrets(),
): Outcome = runBlocking {
    val events = mutableListOf<ModelStreamEvent>()
    var failure: Throwable? = null
    try {
        OpenAIChatClient(provider, transport, secrets).stream(request).toList(events)
    } catch (e: Throwable) {
        failure = e
    }
    Outcome(events, failure)
}
internal fun sentBody(request: ModelRequest = request(), provider: ProviderDescriptor = provider()): String {
    val transport = FakeHttpTransport(okStream)
    run(transport, request, provider).error?.let { throw it }
    return transport.lastBody
}
