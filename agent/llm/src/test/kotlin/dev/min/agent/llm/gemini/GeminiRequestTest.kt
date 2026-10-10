package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiRequestTest {
    @Test fun `plain request snapshot`() {
        assertEquals(obj("""{"contents":[{"role":"user","parts":[{"text":"你好"}]}],"generationConfig":{"maxOutputTokens":8000}}"""), sent())
    }
    @Test fun `system tools and named choice snapshot`() {
        val req = request().copy(system = listOf("稳定", "", "动态"), tools = listOf(read), toolChoice = ToolChoice.Named("Read"), maxOutputTokens = 4000)
        assertEquals(obj("""{"contents":[{"role":"user","parts":[{"text":"你好"}]}],"systemInstruction":{"parts":[{"text":"稳定\n\n动态"}]},"generationConfig":{"maxOutputTokens":4000},"tools":[{"functionDeclarations":[{"name":"Read","description":"读文件","parameters":{"type":"OBJECT","properties":{"path":{"type":"STRING"}},"required":["path"]}}]}],"toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["Read"]}}}"""), sent(req))
    }
    @Test fun `tool choice mappings and omission without tools`() {
        for ((choice, expected) in listOf(ToolChoice.Auto to "AUTO", ToolChoice.Required to "ANY", ToolChoice.None to "NONE")) {
            assertEquals(obj("""{"functionCallingConfig":{"mode":"$expected"}}"""), sent(request().copy(tools = listOf(read), toolChoice = choice))["toolConfig"])
            assertFalse(sent(request().copy(toolChoice = choice)).containsKey("toolConfig"))
        }
    }
    @Test fun `user words and reminders after tool results are independent text parts`() {
        val call = ToolCallPart(CallId("c1"), "Ask", obj("{}"))
        val body = sent(request(listOf(
            user(TextPart("问")), assistant(call),
            user(ToolResultPart(CallId("c1"), listOf(TextPart("用户已回答。"))), TextPart("用户回答了你的问题：Room"), TextPart("<system-reminder>\n提示\n</system-reminder>")),
        )))
        val parts = wireParts(body, 2)
        assertEquals(3, parts.size)
        assertTrue(parts[0].toString(), (parts[0] as JsonObject).containsKey("functionResponse"))
        assertEquals(obj("""{"text":"用户回答了你的问题：Room"}"""), parts[1])
        assertEquals(obj("""{"text":"<system-reminder>\n提示\n</system-reminder>"}"""), parts[2])
    }

    @Test fun `endpoint handles root versions and models prefix`() {
        for ((base, expected) in listOf("https://e/" to "https://e/v1beta", "https://e/v1beta///" to "https://e/v1beta", "https://e/v1/" to "https://e/v1")) {
            assertEquals("$expected/models/gemini-2.5-flash:streamGenerateContent?alt=sse", geminiUrl(base, "models/gemini-2.5-flash"))
        }
    }
    @Test fun `unsafe model paths are rejected before credentials`() {
        for (id in listOf("../m", "m?key=x", "m/x", "", "m#frag")) {
            val transport = FakeHttpTransport()
            assertTrue(run(transport, request().copy(profile = profile().copy(wireModelId = id)), secrets = Secrets { null }).error is IllegalArgumentException)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun `native authentication and extra headers are deduplicated`() {
        val transport = FakeHttpTransport(stream(stopFrame))
        val p = provider().copy(extraHeaders = mapOf("Content-Type" to "application/json; charset=utf-8", "USER-AGENT" to "fake", "X-Org" to "min"))
        assertNull(run(transport, provider = p).error)
        assertEquals("sk-test", transport.header("x-goog-api-key"))
        assertEquals("min-agent", transport.header("user-agent"))
        assertEquals("min", transport.header("x-org"))
        assertEquals("text/event-stream", transport.header("accept"))
        assertEquals("application/json; charset=utf-8", transport.header("content-type"))
        assertEquals(1, transport.requests.single().headers.count { it.first.equals("content-type", true) })
        assertEquals(600000L, transport.requests.single().timeoutMs)
        assertFalse(transport.requests.single().toString().contains("sk-test"))
        assertFalse(transport.requests.single().url.contains("key="))
    }
    @Test fun `bearer and proxy api key header`() {
        for ((p, name, value) in listOf(Triple(provider().copy(auth = AuthScheme.Bearer), "authorization", "Bearer sk-test"),
            Triple(provider().copy(quirks = ProviderQuirks(gemini = GeminiQuirks("x-api-key"))), "x-api-key", "sk-test"))) {
            val t = FakeHttpTransport(stream(stopFrame)); assertNull(run(t, provider = p).error)
            assertEquals(value, t.header(name)); assertNull(t.header("x-goog-api-key"))
        }
    }
    @Test fun `profile patches set generation and thinking without guessing effort`() {
        val p = profile(patches = mapOf(Effort.Medium to obj("""{"generationConfig":{"temperature":0.2,"topP":0.8,"topK":10,"stopSequences":["END"],"thinkingConfig":{"thinkingBudget":512,"includeThoughts":true}}}""")))
        val req = request().copy(profile = p, effort = Effort.High, maxOutputTokens = 4000)
        assertEquals(obj("""{"maxOutputTokens":4000,"temperature":0.2,"topP":0.8,"topK":10,"stopSequences":["END"],"thinkingConfig":{"thinkingBudget":512,"includeThoughts":true}}"""), sent(req)["generationConfig"])
        assertEquals(obj("""{"maxOutputTokens":4000}"""), sent(req.copy(effort = null))["generationConfig"])
        assertEquals(obj("""{"maxOutputTokens":8000}"""), sent(request().copy(effort = Effort.Max))["generationConfig"])
    }
    @Test fun `multiple candidates fail locally`() {
        assertThrows(IllegalArgumentException::class.java) { buildGeminiBody(request().copy(profile = profile(patches = mapOf(Effort.High to obj("""{"generationConfig":{"candidateCount":2}}"""))), effort = Effort.High)) }
    }
    @Test fun `implicit cache hints leave prefix unchanged`() {
        assertEquals(sent(), sent(request().copy(cache = CacheHints("session", true))))
        assertEquals(fingerprint(sent().toString()), fingerprint(sent().toString()))
        assertNotEquals(fingerprint("a"), fingerprint("b"))
    }
    @Test fun `user image uses inline data snapshot`() {
        assertEquals(json("""[{"text":"看"},{"inlineData":{"mimeType":"image/png","data":"QUJD"}}]"""),
            wireParts(sent(request(listOf(user(TextPart(""), TextPart("看"), ImagePart("image/png", ImageSource.Base64("QUJD")))))), 0))
    }
    @Test fun `user pdf uses inline data with application pdf mime type`() {
        assertEquals(json("""[{"text":"看"},{"inlineData":{"mimeType":"application/pdf","data":"JVBERi0="}}]"""),
            wireParts(sent(request(listOf(user(TextPart("看"), DocumentPart("application/pdf", ImageSource.Base64("JVBERi0=")))))), 0))
    }
    @Test fun `artifact pdf in user turn is rejected`() {
        val t = FakeHttpTransport(); assertTrue(run(t, request(listOf(user(DocumentPart("application/pdf", ImageSource.Artifact("a.pdf")))))).error is IllegalArgumentException); assertTrue(t.requests.isEmpty())
    }
    @Test fun `artifact in user or tool result is rejected`() {
        val image = ImagePart("image/png", ImageSource.Artifact("a.png"))
        val call = ToolCallPart(CallId("c"), "Read", obj("{}"))
        for (messages in listOf(listOf(user(image)), listOf(assistant(call), user(ToolResultPart(call.callId, listOf(image)))))) {
            val t = FakeHttpTransport(); assertTrue(run(t, request(messages)).error is IllegalArgumentException); assertTrue(t.requests.isEmpty())
        }
    }
    @Test fun `multiround fallback calls results images and errors snapshot`() {
        val a = ToolCallPart(CallId("a:1"), "Read", obj("""{"path":"a"}"""))
        val b = ToolCallPart(CallId("b:2"), "Read", obj("""{"path":"b"}"""))
        val req = request(listOf(user(TextPart("问")), assistant(ThinkingPart("想", ref, "sig"), TextPart("好"), a, b),
            user(TextPart("插话"), ToolResultPart(b.callId, listOf(TextPart("bad")), true)),
            user(ToolResultPart(a.callId, listOf(TextPart("ok"), ImagePart("image/png", ImageSource.Base64("QUJD"))))), assistant(TextPart("完成")), user(TextPart("谢谢"))))
        assertEquals(json("""[{"role":"user","parts":[{"text":"问"}]},{"role":"model","parts":[{"text":"想","thought":true,"thoughtSignature":"sig"},{"text":"好"},{"functionCall":{"id":"a:1","name":"Read","args":{"path":"a"}}},{"functionCall":{"id":"b:2","name":"Read","args":{"path":"b"}}}]},{"role":"user","parts":[{"functionResponse":{"name":"Read","id":"a:1","response":{"output":"ok"},"parts":[{"inlineData":{"mimeType":"image/png","data":"QUJD"}}]}},{"functionResponse":{"name":"Read","id":"b:2","response":{"error":"bad"}}},{"text":"插话"}]},{"role":"model","parts":[{"text":"完成"}]},{"role":"user","parts":[{"text":"谢谢"}]}]"""), wireContents(sent(req)))
    }
    @Test fun `raw signed parts replay without duplicates and missing ids remain absent`() {
        val raw = """[{"text":"考虑","thought":true,"thoughtSignature":"s0"},{"text":"好","thoughtSignature":"s1"},{"functionCall":{"name":"Read","args":{"path":"a"}},"thoughtSignature":"s2"},{"functionCall":{"name":"Read","args":{"path":"b"}}},{"thoughtSignature":"s3"}]"""
        val done = parsed(content(raw, "STOP")).completed
        val calls = done.message.parts.filterIsInstance<ToolCallPart>()
        val req = request(listOf(user(TextPart("问")), done.message, user(ToolResultPart(calls[1].callId, listOf(TextPart("B")))), user(ToolResultPart(calls[0].callId, listOf(TextPart("A"))))))
        val body = sent(req)
        assertEquals(json(raw), wireParts(body, 1))
        assertEquals(json("""[{"functionResponse":{"name":"Read","response":{"output":"A"}}},{"functionResponse":{"name":"Read","response":{"output":"B"}}}]"""), wireParts(body, 2))
        assertEquals(body, sent(req))
    }
    @Test fun `wire id is echoed on native response`() {
        val done = parsed(content("""[{"functionCall":{"id":"wire:1","name":"Read","args":{}}}]""", "STOP")).completed
        assertEquals(json("""[{"functionResponse":{"name":"Read","id":"wire:1","response":{"output":"R"}}}]"""),
            wireParts(sent(request(listOf(done.message, user(ToolResultPart(CallId("wire:1"), listOf(TextPart("R"))))))), 1))
    }
    @Test fun `echo rules filter unsigned thoughts but preserve signed state`() {
        val done = parsed(content("""[{"text":"unsigned","thought":true},{"text":"signed","thought":true,"thoughtSignature":"S"},{"text":"answer","thoughtSignature":"T"}]""", "STOP")).completed
        val turns = listOf(user(TextPart("q")), done.message, user(TextPart("next")))
        val never = sent(request(turns).copy(profile = profile(ReasoningEcho.Never)))
        assertEquals(json("""[{"text":"signed","thought":true,"thoughtSignature":"S"},{"text":"answer","thoughtSignature":"T"}]"""), wireParts(never, 1))
        assertEquals(wireParts(never, 1), wireParts(sent(request(turns).copy(profile = profile(ReasoningEcho.WithinToolLoop))), 1))
        assertEquals(3, wireParts(sent(request(turns)), 1).size)
    }
    @Test fun `foreign opaque and thoughts are removed while canonical text and calls survive`() {
        val done = parsed(content("""[{"text":"secret","thought":true,"thoughtSignature":"S"},{"text":"answer"},{"functionCall":{"id":"c","name":"Read","args":{}}}]""", "STOP")).completed
        val body = sent(request(listOf(done.message, user(ToolResultPart(CallId("c"), listOf(TextPart("ok")))))).copy(profile = profile().copy(ref = ModelRef("foreign", "m"))))
        assertEquals(json("""[{"text":"answer"},{"functionCall":{"id":"c","name":"Read","args":{}}}]"""), wireParts(body, 0))
        assertFalse(body.toString().contains("secret"))
    }
    @Test fun `bad result association fails instead of guessing names`() {
        val call = ToolCallPart(CallId("c"), "Read", obj("{}")); val result = ToolResultPart(call.callId, emptyList())
        for (turns in listOf(listOf(user(result)), listOf(assistant(call), user(result, result)), listOf(assistant(call), assistant(TextPart("next"))))) {
            assertThrows(IllegalArgumentException::class.java) { buildGeminiBody(request(turns)) }
        }
    }
    @Test fun `missing parallel result is rejected for idless calls`() {
        val done = parsed(content("""[{"functionCall":{"name":"Read","args":{}}},{"functionCall":{"name":"Read","args":{}}}]""", "STOP")).completed
        val first = done.message.parts.filterIsInstance<ToolCallPart>().first()
        assertThrows(IllegalArgumentException::class.java) { buildGeminiBody(request(listOf(done.message, user(ToolResultPart(first.callId, emptyList()))))) }
    }
    @Test fun `factory dispatches all four protocols`() {
        val types = mapOf(Wire.Gemini to GeminiClient::class.java, Wire.AnthropicMessages to AnthropicMessagesClient::class.java,
            Wire.OpenAIChat to OpenAIChatClient::class.java, Wire.OpenAIResponses to OpenAIResponsesClient::class.java)
        types.forEach { (wire, type) -> assertEquals(type, createModelClient(provider().copy(wire = wire), FakeHttpTransport(), FakeSecrets()).javaClass) }
    }
    @Test fun `failed native calls are diagnostic opaque and do not poison next request`() {
        for (reason in listOf("MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL")) {
            val done = parsed(content("""[{"text":"partial"},{"functionCall":{"name":"Read","args":{}}}]""", reason)).completed
            val body = sent(request(listOf(done.message, user(TextPart("继续")))))
            assertEquals(json("""[{"text":"partial"}]"""), wireParts(body, 0))
            assertFalse(body.toString().contains("functionCall"))
            assertTrue((done.message.parts.filterIsInstance<OpaquePart>().single().json as JsonObject).containsKey(REJECTED_PARTS))
        }
    }
    @Test fun `empty projected assistant is omitted before adjacent user merge`() {
        val body = sent(request(listOf(user(TextPart("a")), assistant(TextPart(""), ThinkingPart("foreign", ModelRef("other", "m"))), user(TextPart("b")))))
        assertEquals(json("""[{"role":"user","parts":[{"text":"a"},{"text":"b"}]}]"""), wireContents(body))
    }
}
