package dev.min.agent.llm.openai

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.CacheHints
import dev.min.agent.llm.EmptyContent
import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.llm.OpenAiChatQuirks
import dev.min.agent.llm.ReasoningEcho
import dev.min.agent.llm.StreamUsage
import dev.min.agent.llm.ToolChoice
import dev.min.agent.llm.ToolResultImages
import dev.min.agent.llm.ToolSpec
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIChatRequestTest {
    private val foreign = ModelRef("other", "other-model")
    private val read = ToolSpec("Read", "读文件", obj("""{"type":"object","properties":{"path":{"type":"string"}}}"""))
    private val call = ToolCallPart(CallId("call:1/中文"), "Read", obj("""{"path":"a.txt"}"""))
    private val image = ImagePart("image/png", ImageSource.Base64("QUJD"))
    private val reasoning = OpenAiChatQuirks(reasoningField = "reasoning_content")
    private fun messages(body: String) = (json(body) as JsonObject)["messages"] as JsonArray

    @Test fun `DeepSeek and Kimi differ across two tool rounds and a new user turn`() {
        val catalog = dev.min.agent.llm.catalog.Catalog.bundled()
        val deepseek = requireNotNull(catalog.model(ModelRef("deepseek-openai", "deepseek-flash")))
        val kimi = profile(ReasoningEcho.AlwaysIncludingEmpty).copy(ref = ModelRef("moonshot-openai", "kimi-k3"))
        for (p in listOf(deepseek, kimi)) {
            val q = requireNotNull(catalog.provider(p.ref.providerId))
            val turns = listOf(
                userText("read twice"),
                ModelMessage(Role.Assistant, listOf(ThinkingPart("first", p.ref), call)),
                ModelMessage(Role.User, listOf(ToolResultPart(call.callId, listOf(TextPart("a"))))),
                ModelMessage(Role.Assistant, listOf(ThinkingPart("second", p.ref), call.copy(callId = CallId("second")))),
                ModelMessage(Role.User, listOf(ToolResultPart(CallId("second"), listOf(TextPart("b"))))),
            )
            val loop = messages(sentBody(request(profile = p, messages = turns), q))
            assertEquals(JsonPrimitive("first"), (loop[1] as JsonObject)["reasoning_content"])
            assertEquals(JsonPrimitive("second"), (loop[3] as JsonObject)["reasoning_content"])
            val next = messages(sentBody(request(profile = p, messages = turns + userText("new turn") + ModelMessage(Role.Assistant, listOf(TextPart("ok")))), q))
            if (p.ref.providerId == "deepseek-openai") {
                // 最新 DeepSeek 工具请求也要带旧回合推理；与 Kimi 的区别是无需给无推理消息补空串。
                assertEquals(JsonPrimitive("first"), (next[1] as JsonObject)["reasoning_content"])
                assertEquals(JsonPrimitive("second"), (next[3] as JsonObject)["reasoning_content"])
                assertFalse((next[6] as JsonObject).containsKey("reasoning_content"))
            } else {
                assertEquals(JsonPrimitive("first"), (next[1] as JsonObject)["reasoning_content"])
                assertEquals(JsonPrimitive("second"), (next[3] as JsonObject)["reasoning_content"])
                assertEquals(JsonPrimitive(""), (next[6] as JsonObject)["reasoning_content"])
                assertEquals(JsonPrimitive("Read"), (next[2] as JsonObject)["name"])
            }
        }
    }

    @Test fun `bundled DeepSeek efforts are top level in actual Chat requests`() {
        val catalog = dev.min.agent.llm.catalog.Catalog.bundled()
        val provider = requireNotNull(catalog.provider("deepseek-openai"))
        for (id in listOf("deepseek-flash", "deepseek-v4-pro")) {
            val model = requireNotNull(catalog.model(ModelRef(provider.id, id)))
            assertEquals(1048576, model.contextWindow)
            for ((effort, expected) in listOf(Effort.Low to "low", Effort.High to "high", Effort.Max to "max")) {
                val body = json(sentBody(request(profile = model, effort = effort), provider)) as JsonObject
                assertEquals(JsonPrimitive(expected), body["reasoning_effort"])
                assertEquals(obj("""{"type":"enabled"}"""), body["thinking"])
            }
            val off = json(sentBody(request(profile = model, effort = Effort.Off), provider)) as JsonObject
            assertEquals(obj("""{"type":"disabled"}"""), off["thinking"])
            assertFalse(off.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun `plain text request uses official defaults`() {
        assertJson("""{"model":"test-model","messages":[{"role":"user","content":"你好"}],"stream":true,
            "max_completion_tokens":8000,"stream_options":{"include_usage":true}}""", sentBody())
    }

    @Test
    fun `url auth and deduplicated headers follow provider data`() {
        val transport = FakeHttpTransport(okStream)
        assertNull(run(transport, provider = provider(extraHeaders = mapOf("Content-Type" to "application/custom", "X-Org" to "min", "User-Agent" to "official-cli"))).error)
        val sent = transport.requests.single()
        assertEquals("https://api.example.com/v1/chat/completions", sent.url)
        assertEquals("POST", sent.method)
        assertEquals(600_000L, sent.timeoutMs)
        assertEquals("Bearer sk-test", transport.header("authorization"))
        assertNull(transport.header("x-api-key"))
        assertEquals("application/custom", transport.header("content-type"))
        assertEquals(1, sent.headers.count { it.first.equals("content-type", true) })
        assertEquals("text/event-stream", transport.header("accept"))
        assertEquals("min", transport.header("x-org"))
        assertEquals("min-agent", transport.header("user-agent"))
        assertFalse(sent.toString().contains("sk-test"))
    }

    @Test
    fun `x api key auth has no bearer header`() {
        val transport = FakeHttpTransport(okStream)
        run(transport, provider = provider(auth = AuthScheme.XApiKey))
        assertEquals("sk-test", transport.header("x-api-key"))
        assertNull(transport.header("authorization"))
    }

    @Test
    fun `multiple systems tools and cache routing have a stable prefix`() {
        assertJson("""
            {"model":"test-model","messages":[{"role":"system","content":"稳定\n\n规则"},{"role":"user","content":"你好"}],
             "stream":true,"max_completion_tokens":8000,"stream_options":{"include_usage":true},"prompt_cache_key":"session-1",
             "tools":[{"type":"function","function":{"name":"Read","description":"读文件","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}],
             "tool_choice":{"type":"function","function":{"name":"Read"}},"parallel_tool_calls":true}
        """, sentBody(request(system = listOf("稳定", "", "规则"), tools = listOf(read), choice = ToolChoice.Named("Read"), cache = CacheHints("session-1", true))))
        // Chat 没有 Anthropic 的块级缓存断点或 1h TTL：不能把它们塞进请求。
    }

    @Test
    fun `required none and auto choices map without losing intent`() {
        for ((choice, expected) in listOf(ToolChoice.Required to "required", ToolChoice.None to "none", ToolChoice.Auto to "auto")) {
            assertEquals(JsonPrimitive(expected), (json(sentBody(request(tools = listOf(read), choice = choice))) as JsonObject)["tool_choice"])
        }
    }

    @Test
    fun `tool controls are absent when no tools are defined`() {
        val body = json(sentBody(request(choice = ToolChoice.Required))) as JsonObject
        assertFalse("tools" in body)
        assertFalse("tool_choice" in body)
        assertFalse("parallel_tool_calls" in body)
    }

    @Test
    fun `tool loop retains own reasoning and drops foreign thinking and opaque`() {
        val turns = listOf(userText("问"), ModelMessage(Role.Assistant, listOf(
            ThinkingPart("先", modelRef, "签名不属于 Chat"), ThinkingPart("读", modelRef), ThinkingPart("外来", foreign),
            RedactedThinkingPart("密文", foreign), OpaquePart(foreign, obj("""{"encrypted_content":"外来"}""")),
            TextPart(""), TextPart("好"), TextPart("的"), call,
        )), ModelMessage(Role.User, listOf(ToolResultPart(call.callId, listOf(TextPart("A"), TextPart("B")), true))))
        assertJson("""
            {"model":"test-model","stream":true,"max_completion_tokens":8000,"stream_options":{"include_usage":true},
             "messages":[{"role":"user","content":"问"},{"role":"assistant","content":"好的","reasoning_content":"先读",
                "tool_calls":[{"id":"call:1/中文","type":"function","function":{"name":"Read","arguments":"{\"path\":\"a.txt\"}"}}]},
                {"role":"tool","tool_call_id":"call:1/中文","content":"AB"}]}
        """, sentBody(request(profile = profile(ReasoningEcho.WithinToolLoop), messages = turns), provider(reasoning)))
    }

    @Test
    fun `within tool loop discards reasoning from older turns`() {
        val turns = listOf(userText("旧"), ModelMessage(Role.Assistant, listOf(ThinkingPart("旧想", modelRef), TextPart("旧答"))),
            userText("新"), ModelMessage(Role.Assistant, listOf(ThinkingPart("新想", modelRef), call)),
            ModelMessage(Role.User, listOf(ToolResultPart(call.callId, emptyList()))))
        val sent = messages(sentBody(request(profile = profile(ReasoningEcho.WithinToolLoop), messages = turns), provider(reasoning)))
        assertEquals(obj("""{"role":"assistant","content":"旧答"}"""), sent[1])
        assertEquals(JsonPrimitive("新想"), (sent[3] as JsonObject)["reasoning_content"])
    }

    @Test
    fun `never echo and null reasoning field omit reasoning`() {
        val turns = listOf(ModelMessage(Role.Assistant, listOf(ThinkingPart("想", modelRef), TextPart("答"))))
        for ((p, q) in listOf(profile(ReasoningEcho.Never) to reasoning, profile() to OpenAiChatQuirks())) {
            assertEquals(json("""[{"role":"assistant","content":"答"}]"""), messages(sentBody(request(profile = p, messages = turns), provider(q))))
        }
    }

    @Test
    fun `Kimi always including empty reasoning and tool names`() {
        val turns = listOf(ModelMessage(Role.Assistant, listOf(call)), ModelMessage(Role.User, listOf(ToolResultPart(call.callId, listOf(TextPart("ok"))))))
        assertEquals(json("""[{"role":"assistant","content":null,"reasoning_content":"","tool_calls":[{"id":"call:1/中文","type":"function","function":{"name":"Read","arguments":"{\"path\":\"a.txt\"}"}}]},
            {"role":"tool","tool_call_id":"call:1/中文","name":"Read","content":"ok"}]"""),
            messages(sentBody(request(profile = profile(ReasoningEcho.AlwaysIncludingEmpty), messages = turns), provider(reasoning.copy(toolResultName = true)))))
    }

    @Test
    fun `Kimi missing tool name fails before sending`() {
        val transport = FakeHttpTransport()
        val outcome = run(transport, request(messages = listOf(ModelMessage(Role.User, listOf(ToolResultPart(CallId("orphan"), emptyList()))))), provider(reasoning.copy(toolResultName = true)))
        assertTrue(outcome.error is IllegalArgumentException)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `empty assistant content has all three wire shapes`() {
        for ((shape, expected) in listOf(EmptyContent.Null to JsonNull, EmptyContent.EmptyString to JsonPrimitive(""), EmptyContent.Omit to null)) {
            val turn = messages(sentBody(request(messages = listOf(ModelMessage(Role.Assistant, listOf(call)))), provider(OpenAiChatQuirks(emptyContentWithToolCalls = shape)))).single() as JsonObject
            assertEquals(expected, turn["content"])
            assertEquals(expected != null, "content" in turn)
        }
    }

    @Test
    fun `empty text and fully projected away messages are omitted`() {
        val turns = listOf(userText(""), ModelMessage(Role.Assistant, listOf(TextPart(""), ThinkingPart("外来", foreign))), userText("非空"))
        assertEquals(json("""[{"role":"user","content":"非空"}]"""), messages(sentBody(request(messages = turns), provider(reasoning))))
    }

    @Test
    fun `user images preserve block order and skip empty text`() {
        val turns = listOf(ModelMessage(Role.User, listOf(TextPart("前"), TextPart(""), image, TextPart("后"))))
        assertEquals(json("""[{"role":"user","content":[{"type":"text","text":"前"},{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}},{"type":"text","text":"后"}]}]"""), messages(sentBody(request(messages = turns))))
    }

    @Test
    fun `parallel tool images become one user message after all results`() {
        val turns = listOf(ModelMessage(Role.User, listOf(TextPart("追问"), ToolResultPart(CallId("a"), listOf(TextPart("A"), image)), ToolResultPart(CallId("b"), listOf(image, TextPart("B"))))))
        assertEquals(json("""[{"role":"tool","tool_call_id":"a","content":"A"},{"role":"tool","tool_call_id":"b","content":"B"},
            {"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}},{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]},
            {"role":"user","content":"追问"}]"""), messages(sentBody(request(messages = turns))))
    }

    @Test
    fun `text blocks after tool results stay apart with a blank line between them`() {
        // 内核的一条工具结果消息：tool_result、用户的话、hook 提醒各自是独立块；字符串形态的 content 不能把它们粘在一起
        val turns = listOf(ModelMessage(Role.User, listOf(
            ToolResultPart(CallId("a"), listOf(TextPart("用户已回答。"))),
            TextPart("用户回答了你的问题：\n- 用哪个库？ → Room"),
            TextPart("<system-reminder>\n构建提示\n</system-reminder>"),
        )))
        assertEquals(json("""[{"role":"tool","tool_call_id":"a","content":"用户已回答。"},
            {"role":"user","content":"用户回答了你的问题：\n- 用哪个库？ → Room\n\n<system-reminder>\n构建提示\n</system-reminder>"}]"""),
            messages(sentBody(request(messages = turns))))
    }

    @Test
    fun `tool images may be inline or replaced by a counted notice`() {
        val turns = listOf(ModelMessage(Role.User, listOf(ToolResultPart(CallId("a"), listOf(TextPart("前"), image, TextPart("后"), image)))))
        assertEquals(json("""[{"role":"tool","tool_call_id":"a","content":[{"type":"text","text":"前"},{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}},
            {"type":"text","text":"后"},{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]}]"""),
            messages(sentBody(request(messages = turns), provider(OpenAiChatQuirks(toolResultImages = ToolResultImages.Inline)))))
        assertEquals(json("""[{"role":"tool","tool_call_id":"a","content":"前后\n此处有 2 张图片未发送"}]"""),
            messages(sentBody(request(messages = turns), provider(OpenAiChatQuirks(toolResultImages = ToolResultImages.Unsupported)))))
    }

    @Test fun `pdf documents become file content parts in user turns and follow each tool-result mode`() {
        val pdf = DocumentPart("application/pdf", ImageSource.Base64("JVBERi0="))
        val file = """{"type":"file","file":{"filename":"document.pdf","file_data":"data:application/pdf;base64,JVBERi0="}}"""
        val user = messages(sentBody(request(messages = listOf(ModelMessage(Role.User, listOf(TextPart("看"), pdf))))))
        assertEquals(json("""[{"role":"user","content":[{"type":"text","text":"看"},$file]}]"""), user)
        val tool = listOf(ModelMessage(Role.User, listOf(ToolResultPart(call.callId, listOf(TextPart("共 1 页"), pdf)))))
        fun toolMessages(mode: ToolResultImages) = messages(sentBody(request(messages = tool), provider(OpenAiChatQuirks(toolResultImages = mode))))
        assertEquals(json("""[{"role":"tool","tool_call_id":"call:1/中文","content":[{"type":"text","text":"共 1 页"},$file]}]"""), toolMessages(ToolResultImages.Inline))
        assertEquals(json("""[{"role":"tool","tool_call_id":"call:1/中文","content":"共 1 页\n此处有 1 份文档未发送"}]"""), toolMessages(ToolResultImages.Unsupported))
        assertEquals(
            json("""[{"role":"tool","tool_call_id":"call:1/中文","content":"共 1 页"},{"role":"user","content":[$file]}]"""),
            toolMessages(ToolResultImages.SeparateUserMessage),
        )
    }

    @Test fun `artifact pdf is rejected before any Chat request is sent`() {
        val pdf = DocumentPart("application/pdf", ImageSource.Artifact("a.pdf"))
        val transport = FakeHttpTransport()
        assertTrue(run(transport, request(messages = listOf(ModelMessage(Role.User, listOf(pdf))))).error is IllegalArgumentException)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `artifact images are rejected in user and every tool image mode`() {
        val artifact = ImagePart("image/png", ImageSource.Artifact("a.png"))
        val user = request(messages = listOf(ModelMessage(Role.User, listOf(artifact))))
        assertTrue(run(FakeHttpTransport(), user).error is IllegalArgumentException)
        for (mode in ToolResultImages.entries) {
            val transport = FakeHttpTransport()
            val r = request(messages = listOf(ModelMessage(Role.User, listOf(ToolResultPart(CallId("c"), listOf(artifact))))))
            assertTrue(run(transport, r, provider(OpenAiChatQuirks(toolResultImages = mode))).error is IllegalArgumentException)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `nondefault max tokens developer role and usage modes snapshot`() {
        for (usage in listOf(StreamUsage.Always, StreamUsage.None)) {
            assertJson("""{"model":"wire-id","messages":[{"role":"developer","content":"规则"},{"role":"user","content":"你好"}],
                "stream":true,"max_tokens":4000,"tools":[{"type":"function","function":{"name":"Read","description":"读文件","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}],"tool_choice":"auto"}""",
                sentBody(request(profile = profile(wireModelId = "wire-id"), system = listOf("规则"), tools = listOf(read), max = 4000, cache = CacheHints("ignored", true)),
                    provider(OpenAiChatQuirks(maxTokensField = "max_tokens", developerRole = true, streamUsage = usage, parallelToolCalls = false, promptCacheKey = false))))
        }
    }

    @Test
    fun `effort patches deeply merge usage options and override scalar limits`() {
        val patches = mapOf(Effort.Medium to obj("""{"stream_options":{"custom":true},"thinking":{"type":"enabled","budget":20},"max_completion_tokens":5000}"""))
        assertJson("""{"model":"test-model","messages":[{"role":"user","content":"你好"}],"stream":true,"max_completion_tokens":5000,
            "stream_options":{"include_usage":true,"custom":true},"thinking":{"type":"enabled","budget":20}}""",
            sentBody(request(profile = profile(patches = patches), effort = Effort.High, max = 3000)))
        assertFalse(sentBody(request(profile = profile(patches = patches))).contains("thinking"))
    }

    @Test
    fun `custom reasoning fields and same model encrypted opaque are echoed`() {
        val turns = listOf(ModelMessage(Role.Assistant, listOf(ThinkingPart("摘要", modelRef), OpaquePart(modelRef, obj("""{"encrypted_content":"ENC"}""")), OpaquePart(foreign, obj("""{"encrypted_content":"BAD"}""")), TextPart("答"))))
        assertEquals(json("""[{"role":"assistant","content":"答","reasoning":"摘要","encrypted_content":"ENC"}]"""),
            messages(sentBody(request(messages = turns), provider(reasoning.copy(reasoningField = "reasoning", encryptedReasoningField = "encrypted_content")))))
        assertFalse(sentBody(request(messages = turns), provider(reasoning)).contains("encrypted_content"))
    }
}
