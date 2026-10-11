package dev.min.agent.llm.openai

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 手写请求与流夹具；不能用客户端自己生成期望，否则丢 phase/推理也会看似通过。 */
class OpenAIResponsesTest {
    @Test fun `patch custom tool definition and transcript roundtrip`() {
        val patch = "*** Begin Patch\n*** Add File: a\n+x\n*** End Patch"
        val tool = ToolSpec("apply_patch", "patch", obj("{}"))
        val body = buildResponsesBody(request().copy(tools = listOf(tool), toolChoice = ToolChoice.Named("apply_patch")))
        val definition = (body["tools"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("custom"), definition["type"])
        assertEquals(JsonPrimitive("grammar"), definition["format"]!!.jsonObject["type"])
        assertTrue(definition["format"].toString().contains("*** Move to:"))
        assertEquals(obj("""{"type":"custom","name":"apply_patch"}"""), body["tool_choice"])
        val raw = buildJsonObject { put("type", "custom_tool_call"); put("id", "ct_1"); put("call_id", "call_p"); put("name", "apply_patch"); put("input", patch) }
        val terminal = event(buildJsonObject { put("type", "response.completed"); putJsonObject("response") { put("output", JsonArray(listOf(raw))) } }.toString())
        val done = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(terminal)))).filterIsInstance<ModelStreamEvent.Completed>().single()
        assertEquals(StopReason.ToolUse, done.stop)
        assertEquals(JsonPrimitive(patch), done.message.parts.filterIsInstance<ToolCallPart>().single().input["input"])
        val next = request(listOf(done.message, toolResults(ToolResultPart(CallId("call_p"), listOf(TextPart("ok")))))).copy(tools = listOf(tool))
        val input = buildResponsesBody(next)["input"]!!.jsonArray
        assertEquals(raw, input[1])
        assertEquals(obj("""{"type":"custom_tool_call_output","call_id":"call_p","output":"ok"}"""), input[2])
        assertEquals(3, input.size)
        val foreign = next.copy(profile = profile.copy(ref = ModelRef("foreign", "m")))
        assertEquals(JsonPrimitive(patch), buildResponsesBody(foreign)["input"]!!.jsonArray[1].jsonObject["input"])
    }
    private val ref = ModelRef("responses", "test-model")
    private val profile = ModelProfile(ref, family = ModelFamily.Other, contextWindow = 20000, maxOutputTokens = 2000)
    private val provider = ProviderDescriptor("responses", "https://example.invalid/v1/", Wire.OpenAIResponses, AuthScheme.Bearer)
    private fun obj(text: String) = AgentJson.parseToJsonElement(text) as JsonObject
    private fun request(messages: List<ModelMessage> = listOf(userText("hello"))) = ModelRequest(profile, listOf("fixed"), messages, emptyList())
    private fun toolResults(vararg results: ToolResultPart) = ModelMessage(Role.User, results.toList())
    private fun event(text: String) = SseEvent(null, text)
    private val terminal = event("""{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"OK"}]}],"usage":{"input_tokens":160,"input_tokens_details":{"cached_tokens":100,"cache_write_tokens":10},"output_tokens":8}}}""")
    private fun collect(transport: FakeHttpTransport, request: ModelRequest = request()) = runBlocking {
        OpenAIResponsesClient(provider, transport, FakeSecrets()).stream(request).toList()
    }
    private fun failure(vararg events: SseEvent) = assertThrows(ModelException::class.java) {
        collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(events.toList())))
    }

    @Test fun `recorded Luna production stream replays text phase and cache accounting`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/openai-responses/lunora-gpt-5.6-luna-ok.sse")).use { it.readBytes().toString(Charsets.UTF_8) }
        val done = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(parseSse(raw)))).filterIsInstance<ModelStreamEvent.Completed>().single()
        assertEquals(listOf(TextPart("OK")), done.message.parts.filterIsInstance<TextPart>())
        assertEquals(Usage(551,5,3840,0),done.usage)
        assertEquals(JsonPrimitive("final_answer"),(done.message.parts.filterIsInstance<OpaquePart>().single().json as JsonObject)["phase"])
        assertEquals(StopReason.EndTurn,done.stop)
    }

    @Test fun `plain request snapshot uses stateless Responses and stable developer input`() {
        assertEquals(obj("""{"model":"test-model","stream":true,"store":false,"include":["reasoning.encrypted_content"],"max_output_tokens":2000,"input":[{"role":"developer","content":"fixed"},{"role":"user","content":"hello"}]}"""), buildResponsesBody(request()))
        val transport = FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(terminal)))
        collect(transport)
        assertEquals("https://example.invalid/v1/responses", transport.requests.single().url)
        assertEquals("Bearer sk-test", transport.header("authorization"))
        assertEquals("min-agent", transport.header("user-agent"))
        assertFalse(transport.requests.single().toString().contains("sk-test"))
    }

    @Test fun `tool schema stays nonstrict and named choice is flat`() {
        val tool = ToolSpec("Read", "read", obj("""{"type":"object","properties":{"path":{"type":"string"}}}"""))
        val body = buildResponsesBody(request().copy(tools = listOf(tool), toolChoice = ToolChoice.Named("Read"), effort = Effort.High, cache = CacheHints("session")))
        assertEquals(obj("""{"type":"function","name":"Read"}"""), body["tool_choice"])
        assertEquals(obj("""{"type":"function","name":"Read","description":"read","parameters":{"type":"object","properties":{"path":{"type":"string"}}},"strict":false}"""), (body["tools"] as JsonArray).single())
        assertEquals(obj("""{"effort":"high"}"""), body["reasoning"])
        assertEquals(JsonPrimitive("session"), body["prompt_cache_key"])
        assertEquals(JsonPrimitive("required"), buildResponsesBody(request().copy(tools=listOf(tool), toolChoice=ToolChoice.Required))["tool_choice"])
        assertEquals(JsonPrimitive("none"), buildResponsesBody(request().copy(tools=listOf(tool), toolChoice=ToolChoice.None))["tool_choice"])
    }

    @Test fun `terminal usage excludes cache and terminal stops upstream immediately`() {
        val events = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(
            event("""{"type":"response.created","response":{"id":"resp_1"}}"""),
            event("""{"type":"response.output_text.delta","delta":"OK"}"""), terminal,
        ), IOException("must not read past terminal"))))
        assertEquals(ModelStreamEvent.Started("resp_1"), events.first())
        assertTrue(events.contains(ModelStreamEvent.TextDelta("OK")))
        val done = events.filterIsInstance<ModelStreamEvent.Completed>().single()
        assertEquals(Usage(50, 8, 100, 10), done.usage)
        assertEquals(StopReason.EndTurn, done.stop)
        assertEquals("final_answer", (done.message.parts.filterIsInstance<OpaquePart>().single().json as JsonObject)["phase"]?.jsonPrimitive?.content)
    }

    @Test fun `multiple tool rounds replay raw reasoning phase calls and results without duplicating text`() {
        val raw = listOf(
            obj("""{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"encrypted-fixture"}"""),
            obj("""{"type":"message","id":"msg_1","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"checking"}]}"""),
            obj("""{"type":"function_call","id":"fc_1","call_id":"call_1","name":"Read","arguments":"{\"path\":\"a\"}"}"""),
        )
        val doneEvent = event(buildJsonObject { put("type", "response.completed"); putJsonObject("response") { put("output", JsonArray(raw)) } }.toString())
        val done = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(doneEvent)))).filterIsInstance<ModelStreamEvent.Completed>().single()
        assertEquals(StopReason.ToolUse, done.stop)
        val next = request(listOf(userText("hello"), done.message, toolResults(ToolResultPart(CallId("call_1"), listOf(TextPart("file")))), done.message, toolResults(ToolResultPart(CallId("call_1"), listOf(TextPart("again"))))))
        val input = buildResponsesBody(next)["input"] as JsonArray
        assertEquals(raw, input.subList(2, 5))
        assertEquals(obj("""{"type":"function_call_output","call_id":"call_1","output":"file"}"""), input[5])
        assertEquals(raw, input.subList(6, 9))
        assertEquals(10, input.size)
        val foreign = next.copy(profile=profile.copy(ref=ModelRef("foreign","m")))
        assertFalse(buildResponsesBody(foreign).toString().contains("encrypted-fixture"))
    }

    @Test fun `user words and reminders after tool results are separate from the output and from each other`() {
        val next = request(listOf(
            userText("hello"),
            ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("call_1"), "Ask", obj("{}")))),
            ModelMessage(Role.User, listOf(
                ToolResultPart(CallId("call_1"), listOf(TextPart("用户已回答。"))),
                TextPart("用户回答了你的问题：\n- 用哪个库？ → Room"),
                TextPart("<system-reminder>\n提示\n</system-reminder>"),
            )),
        ))
        val tail = (buildResponsesBody(next)["input"] as JsonArray).takeLast(2)
        assertEquals(obj("""{"type":"function_call_output","call_id":"call_1","output":"用户已回答。"}"""), tail[0])
        assertEquals(obj("""{"role":"user","content":"用户回答了你的问题：\n- 用哪个库？ → Room\n\n<system-reminder>\n提示\n</system-reminder>"}"""), tail[1])
    }

    @Test fun `tool streaming uses call ordinal rather than output index and preserves invalid arguments`() {
        val events = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(
            event("""{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"c","name":"Read"}}"""),
            event("""{"type":"response.function_call_arguments.delta","output_index":2,"delta":"[1]"}"""),
            event("""{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c","name":"Read","arguments":"[1]"}]}}"""),
        ))))
        assertTrue(events.contains(ModelStreamEvent.ToolCallStarted(0,"c","Read")))
        assertTrue(events.contains(ModelStreamEvent.ToolInputDelta(0,"[1]")))
        assertEquals(obj("""{"__invalid_json":"[1]"}"""), events.filterIsInstance<ModelStreamEvent.Completed>().single().message.parts.filterIsInstance<ToolCallPart>().single().input)
    }

    @Test fun `max output is truncated and unsupported incomplete cannot succeed`() {
        val limited = event("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"output":[]}}""")
        val done = collect(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(limited)))).filterIsInstance<ModelStreamEvent.Completed>().single()
        assertTrue(done.truncated); assertEquals(StopReason.MaxTokens,done.stop)
        assertEquals(ModelFailureKind.Other, failure(event("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"unexpected"},"output":[]}}""")).kind)
    }

    @Test fun `EOF malformed identity unsupported items and failed response are failures`() {
        assertTrue(failure().beforeFirstByte)
        assertFalse(failure(event("""{"type":"response.created","response":{"id":"r"}}""")).beforeFirstByte)
        assertEquals(ModelFailureKind.Other, failure(event("not json")).kind)
        assertEquals(ModelFailureKind.Other, failure(event("""{"type":"response.completed","response":{"output":[{"type":"function_call","name":"Read"}]}}""")).kind)
        assertEquals(ModelFailureKind.Other, failure(event("""{"type":"response.completed","response":{"output":[{"type":"web_search_call"}]}}""")).kind)
        assertEquals(ModelFailureKind.Server, failure(event("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"upstream"}}}""")).kind)
        assertEquals(ModelFailureKind.RateLimited, failure(event("""{"type":"error","code":"rate_limit_error"}""")).kind)
    }

    @Test fun `HTTP status network and cancellation keep their semantics`() {
        listOf(401 to ModelFailureKind.Auth, 429 to ModelFailureKind.RateLimited, 503 to ModelFailureKind.Server, 400 to ModelFailureKind.ContextOverflow, 200 to ModelFailureKind.Other).forEach { (status, kind) ->
            val error = assertThrows(ModelException::class.java) { collect(FakeHttpTransport(FakeHttpTransport.Reply.Status(status,"context_length_exceeded",listOf("retry-after" to "2")))) }
            assertEquals(kind,error.kind); assertTrue(error.beforeFirstByte)
            if(status==429) assertEquals(2000L,error.retryAfterMs)
        }
        val error = assertThrows(ModelException::class.java) { collect(FakeHttpTransport(FakeHttpTransport.Reply.NetworkError(IOException("offline")))) }
        assertEquals(ModelFailureKind.Network,error.kind)
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
            override fun stream(request: HttpRequest) = kotlinx.coroutines.flow.flow<SseEvent> { throw CancellationException("cancel") }
        }
        assertThrows(CancellationException::class.java) { runBlocking { OpenAIResponsesClient(provider,transport,FakeSecrets()).stream(request()).toList() } }
    }

    @Test fun `pdf documents use input_file with a data url and must be inline`() {
        val pdf = DocumentPart("application/pdf", ImageSource.Base64("JVBERi0="))
        val body = buildResponsesBody(request(listOf(ModelMessage(Role.User, listOf(TextPart("看"), pdf)))))
        assertEquals(AgentJson.parseToJsonElement("""[{"role":"developer","content":"fixed"},{"role":"user","content":[{"type":"input_text","text":"看"},{"type":"input_file","filename":"document.pdf","file_data":"data:application/pdf;base64,JVBERi0="}]}]"""),
            body["input"])
        assertThrows(IllegalArgumentException::class.java) { buildResponsesBody(request(listOf(ModelMessage(Role.User, listOf(pdf.copy(source = ImageSource.Artifact("a.pdf")))))))}
    }

    @Test fun `images must be inline and use Responses input types`() {
        val image = ImagePart("image/png", ImageSource.Base64("QUJD"))
        val body = buildResponsesBody(request(listOf(ModelMessage(Role.User,listOf(TextPart("pic"),image)))))
        assertTrue(body.toString().contains("input_image"))
        assertThrows(IllegalArgumentException::class.java) { buildResponsesBody(request(listOf(ModelMessage(Role.User,listOf(image.copy(source=ImageSource.Artifact("a"))))))) }
    }
}
