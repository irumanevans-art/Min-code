package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

internal val ref = ModelRef("gemini", "test-model")
internal fun json(raw: String) = AgentJson.parseToJsonElement(raw)
internal fun obj(raw: String) = json(raw) as JsonObject
internal fun profile(echo: ReasoningEcho = ReasoningEcho.Always, patches: Map<Effort, JsonObject> = emptyMap()) =
    ModelProfile(ref, family = ModelFamily.Gemini, contextWindow = 100000, maxOutputTokens = 8000, reasoningEcho = echo, effortPatches = patches)
internal fun provider() = ProviderDescriptor("gemini", "https://example.invalid///", Wire.Gemini, AuthScheme.XApiKey)
internal fun user(vararg parts: Part) = ModelMessage(Role.User, parts.toList())
internal fun assistant(vararg parts: Part) = ModelMessage(Role.Assistant, parts.toList())
internal fun request(messages: List<ModelMessage> = listOf(user(TextPart("你好")))) = ModelRequest(profile(), emptyList(), messages, emptyList())
internal val read = ToolSpec("Read", "读文件", obj("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""))
internal fun stream(vararg frames: String, failure: java.io.IOException? = null) = FakeHttpTransport.Reply.Sse(
    parseSse(frames.joinToString("\n\n") { "data: $it" }), failure,
)
internal val stopFrame = """{"responseId":"r1","candidates":[{"index":0,"finishReason":"STOP"}]}"""
internal fun content(parts: String, finish: String? = null) =
    """{"candidates":[{"index":0,"content":{"role":"model","parts":$parts}${finish?.let { ",\"finishReason\":\"$it\"" }.orEmpty()}}]}"""
internal class Outcome(val events: List<ModelStreamEvent>, val error: Throwable?) {
    val completed get() = events.filterIsInstance<ModelStreamEvent.Completed>().single()
    fun modelError() = error as? ModelException ?: error("期望 ModelException，实际 $error")
}
internal fun run(transport: HttpTransport, request: ModelRequest = request(), provider: ProviderDescriptor = provider(), secrets: Secrets = FakeSecrets()): Outcome = runBlocking {
    val events = mutableListOf<ModelStreamEvent>()
    var failure: Throwable? = null
    try { GeminiClient(provider, transport, secrets).stream(request).toList(events) } catch (e: Throwable) { failure = e }
    Outcome(events, failure)
}
internal fun sent(request: ModelRequest = request(), provider: ProviderDescriptor = provider()): JsonObject {
    val transport = FakeHttpTransport(stream(stopFrame))
    run(transport, request, provider).error?.let { throw it }
    return obj(transport.lastBody)
}
internal fun parsed(vararg frames: String) = run(FakeHttpTransport(stream(*frames)))
internal fun wireContents(body: JsonObject) = body["contents"] as JsonArray
internal fun wireParts(body: JsonObject, index: Int) = (wireContents(body)[index] as JsonObject)["parts"] as JsonArray
