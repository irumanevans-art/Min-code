package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AnthropicQuirks
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.CacheHints
import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.llm.ReasoningEcho
import dev.min.agent.llm.ToolChoice
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicRequestTest {
    private val other = ModelRef("deepseek", "deepseek-v4")
    private val noCache = AnthropicQuirks(cacheControl = false)
    private val readSchema = json("""{"type":"object","properties":{"path":{"type":"string"}}}""") as JsonObject

    @Test
    fun `plain text request`() {
        val body = sentBody(request())
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[{"role":"user","content":[
               {"type":"text","text":"你好","cache_control":{"type":"ephemeral"}}]}]}
            """,
            body,
        )
    }

    @Test
    fun `url and headers follow the provider data`() {
        val transport = FakeHttpTransport(okStream)
        run(
            transport,
            request(),
            provider(
                quirks = AnthropicQuirks(anthropicVersion = "2024-01-01", betaHeaders = listOf("b1", "b2")),
                extraHeaders = mapOf("X-Org" to "min"),
            ),
        )
        val sent = transport.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("https://api.example.com/v1/messages", sent.url)
        assertEquals("sk-test", transport.header("x-api-key"))
        assertNull(transport.header("authorization"))
        assertEquals("application/json", transport.header("content-type"))
        assertEquals("text/event-stream", transport.header("accept"))
        assertEquals("2024-01-01", transport.header("anthropic-version"))
        assertEquals("b1,b2", transport.header("anthropic-beta"))
        assertEquals("min", transport.header("x-org"))
        assertEquals("min-agent", transport.header("user-agent"))
    }

    @Test
    fun `bearer auth and no beta header by default`() {
        val transport = FakeHttpTransport(okStream)
        run(transport, request(), provider(auth = AuthScheme.Bearer))
        assertEquals("Bearer sk-test", transport.header("authorization"))
        assertNull(transport.header("x-api-key"))
        assertNull(transport.header("anthropic-beta"))
        assertEquals("2023-06-01", transport.header("anthropic-version"))
    }

    @Test
    fun `several system blocks and tools with cache breakpoints`() {
        val body = sentBody(
            request(
                system = listOf("稳定前缀", "", "易变部分"),
                tools = listOf(
                    ToolSpec("Read", "读文件", readSchema),
                    ToolSpec("Bash", "跑命令", readSchema),
                ),
                toolChoice = ToolChoice.Named("Read"),
                messages = listOf(userText("一"), ModelMessage(Role.Assistant, listOf(TextPart("答"))), userText("二")),
                cache = CacheHints(longTtl = true),
            ),
        )
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "system":[{"type":"text","text":"稳定前缀"},
                       {"type":"text","text":"易变部分","cache_control":{"type":"ephemeral","ttl":"1h"}}],
             "tools":[{"name":"Read","description":"读文件","input_schema":{"type":"object","properties":{"path":{"type":"string"}}}},
                      {"name":"Bash","description":"跑命令","input_schema":{"type":"object","properties":{"path":{"type":"string"}}},
                       "cache_control":{"type":"ephemeral","ttl":"1h"}}],
             "tool_choice":{"type":"tool","name":"Read"},
             "messages":[{"role":"user","content":[{"type":"text","text":"一"}]},
                         {"role":"assistant","content":[{"type":"text","text":"答"}]},
                         {"role":"user","content":[{"type":"text","text":"二","cache_control":{"type":"ephemeral","ttl":"1h"}}]}]}
            """,
            body,
        )
    }

    @Test
    fun `tool choice mapping and omission without tools`() {
        val tools = listOf(ToolSpec("Read", "读", readSchema))
        fun choice(c: ToolChoice, withTools: Boolean = true): Any? {
            val body = json(sentBody(request(tools = if (withTools) tools else emptyList(), toolChoice = c), provider(noCache))) as JsonObject
            return body["tool_choice"]
        }
        assertEquals(json("""{"type":"auto"}"""), choice(ToolChoice.Auto))
        assertEquals(json("""{"type":"any"}"""), choice(ToolChoice.Required))
        assertEquals(json("""{"type":"none"}"""), choice(ToolChoice.None))
        assertEquals(json("""{"type":"tool","name":"Read"}"""), choice(ToolChoice.Named("Read")))
        assertNull(choice(ToolChoice.Required, withTools = false))
        val noTools = json(sentBody(request(toolChoice = ToolChoice.Required), provider(noCache))) as JsonObject
        assertFalse("tools" in noTools)
    }

    @Test
    fun `multi-turn tool loop drops foreign thinking, sanitizes ids and merges neighbours`() {
        val messages = listOf(
            userText("读一下 a.txt"),
            ModelMessage(
                Role.Assistant,
                listOf(
                    ThinkingPart("先读", claudeRef, "sig1"),
                    ThinkingPart("别人想的", other, "sigX"),
                    RedactedThinkingPart("enc", other),
                    OpaquePart(other, JsonPrimitive(1)),
                    TextPart(""),
                    TextPart("好的"),
                    ToolCallPart(CallId("call:1"), "Read", json("""{"path":"a.txt"}""") as JsonObject),
                    ToolCallPart(CallId("toolu_2"), "Read", json("""{"path":"b.txt"}""") as JsonObject),
                ),
            ),
            ModelMessage(
                Role.User,
                listOf(
                    ToolResultPart(
                        CallId("call:1"),
                        listOf(TextPart("内容"), ImagePart("image/png", ImageSource.Base64("QUJD"))),
                    ),
                    ToolResultPart(CallId("toolu_2"), listOf(TextPart("boom")), isError = true),
                ),
            ),
            ModelMessage(Role.Assistant, listOf(TextPart("完成"))),
            userText("谢谢"),
            userText("再问一个"),
        )
        val body = sentBody(request(messages = messages), provider(noCache))
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[
              {"role":"user","content":[{"type":"text","text":"读一下 a.txt"}]},
              {"role":"assistant","content":[
                {"type":"thinking","thinking":"先读","signature":"sig1"},
                {"type":"text","text":"好的"},
                {"type":"tool_use","id":"call_1","name":"Read","input":{"path":"a.txt"}},
                {"type":"tool_use","id":"toolu_2","name":"Read","input":{"path":"b.txt"}}]},
              {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call_1","content":[
                  {"type":"text","text":"内容"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"QUJD"}}]},
                {"type":"tool_result","tool_use_id":"toolu_2","content":[{"type":"text","text":"boom"}],"is_error":true}]},
              {"role":"assistant","content":[{"type":"text","text":"完成"}]},
              {"role":"user","content":[{"type":"text","text":"谢谢"},{"type":"text","text":"再问一个"}]}]}
            """,
            body,
        )
    }

    @Test
    fun `tool results come first when a user message also carries text`() {
        val messages = listOf(
            userText("问"),
            ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("c1"), "Read", JsonObject(emptyMap())))),
            userText("顺便说一句"),
            ModelMessage(Role.User, listOf(ToolResultPart(CallId("c1"), listOf(TextPart("r"))))),
        )
        val body = sentBody(request(messages = messages), provider(noCache))
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[
              {"role":"user","content":[{"type":"text","text":"问"}]},
              {"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"Read","input":{}}]},
              {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"c1","content":[{"type":"text","text":"r"}]},
                {"type":"text","text":"顺便说一句"}]}]}
            """,
            body,
        )
    }

    @Test
    fun `user words and reminders after tool results are independent text blocks`() {
        val messages = listOf(
            userText("问"),
            ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("c1"), "Ask", JsonObject(emptyMap())))),
            ModelMessage(Role.User, listOf(
                ToolResultPart(CallId("c1"), listOf(TextPart("用户已回答。"))),
                TextPart("用户回答了你的问题：Room"),
                TextPart("<system-reminder>\n提示\n</system-reminder>"),
            )),
        )
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[
              {"role":"user","content":[{"type":"text","text":"问"}]},
              {"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"Ask","input":{}}]},
              {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"c1","content":[{"type":"text","text":"用户已回答。"}]},
                {"type":"text","text":"用户回答了你的问题：Room"},
                {"type":"text","text":"<system-reminder>\n提示\n</system-reminder>"}]}]}
            """,
            sentBody(request(messages = messages), provider(noCache)),
        )
    }

    @Test
    fun `assistant messages that project to nothing are dropped and neighbours merged`() {
        val messages = listOf(
            userText("一"),
            ModelMessage(Role.Assistant, listOf(TextPart(""), ThinkingPart("别人的", other, "s"))),
            userText("二"),
        )
        val body = sentBody(request(messages = messages), provider(noCache))
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[{"role":"user","content":[{"type":"text","text":"一"},{"type":"text","text":"二"}]}]}
            """,
            body,
        )
    }

    @Test
    fun `own thinking follows the echo rule of the profile`() {
        val messages = listOf(
            userText("问"),
            ModelMessage(Role.Assistant, listOf(ThinkingPart("想", claudeRef, "s"), TextPart("答"))),
            userText("再问"),
        )
        val p = profile(reasoningEcho = ReasoningEcho.WithinToolLoop)
        val body = sentBody(request(profile = p, messages = messages), provider(noCache))
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,
             "messages":[{"role":"user","content":[{"type":"text","text":"问"}]},
                         {"role":"assistant","content":[{"type":"text","text":"答"}]},
                         {"role":"user","content":[{"type":"text","text":"再问"}]}]}
            """,
            body,
        )
    }

    @Test
    fun `effort patch is deep merged and max tokens follows the request`() {
        val patches = mapOf(
            Effort.High to (json("""{"thinking":{"type":"adaptive"},"output_config":{"effort":"high"}}""") as JsonObject),
        )
        val body = sentBody(
            request(profile = profile(effortPatches = patches), effort = Effort.High, maxOutputTokens = 4_000),
            provider(noCache),
        )
        assertJson(
            """
            {"model":"claude-sonnet-5-5","max_tokens":4000,"stream":true,
             "thinking":{"type":"adaptive"},"output_config":{"effort":"high"},
             "messages":[{"role":"user","content":[{"type":"text","text":"你好"}]}]}
            """,
            body,
        )
    }

    @Test
    fun `thinking budget at or above max tokens raises the cap within the model limit`() {
        val patches = mapOf(Effort.Medium to (json("""{"thinking":{"type":"enabled","budget_tokens":4000}}""") as JsonObject))
        val body = sentBody(
            request(profile = profile(maxOutputTokens = 8_000, effortPatches = patches), effort = Effort.Medium, maxOutputTokens = 3_000),
            provider(noCache),
        )
        val parsed = json(body) as JsonObject
        assertEquals(JsonPrimitive(5_024), parsed["max_tokens"])
    }

    @Test
    fun `thinking budget that cannot fit fails loudly`() {
        val patches = mapOf(Effort.Medium to (json("""{"thinking":{"type":"enabled","budget_tokens":8000}}""") as JsonObject))
        try {
            sentBody(request(profile = profile(maxOutputTokens = 8_000, effortPatches = patches), effort = Effort.Medium), provider(noCache))
            fail("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("budget_tokens=8000"))
        }
    }

    @Test
    fun `budget below max tokens leaves the cap alone`() {
        val patches = mapOf(Effort.Low to (json("""{"thinking":{"type":"enabled","budget_tokens":2000}}""") as JsonObject))
        val body = sentBody(request(profile = profile(effortPatches = patches), effort = Effort.Low), provider(noCache))
        assertEquals(JsonPrimitive(8_000), (json(body) as JsonObject)["max_tokens"])
    }

    @Test
    fun `quirk cacheControl off removes every cache_control`() {
        val body = sentBody(
            request(
                system = listOf("s"),
                tools = listOf(ToolSpec("Read", "读", readSchema)),
                cache = CacheHints(longTtl = true),
            ),
            provider(noCache),
        )
        assertFalse(body.contains("cache_control"))
    }

    @Test
    fun `quirk thinkingSignature off drops the signature field`() {
        val messages = listOf(
            userText("问"),
            ModelMessage(Role.Assistant, listOf(ThinkingPart("想", claudeRef, "sig"), TextPart("答"))),
            userText("再问"),
        )
        val body = sentBody(request(messages = messages), provider(AnthropicQuirks(cacheControl = false, thinkingSignature = false)))
        assertFalse(body.contains("signature"))
        assertTrue(body.contains("\"thinking\":\"想\""))
    }

    @Test
    fun `quirk redactedThinking off drops redacted blocks but default sends them`() {
        val messages = listOf(
            userText("问"),
            ModelMessage(Role.Assistant, listOf(RedactedThinkingPart("enc", claudeRef), TextPart("答"))),
            userText("再问"),
        )
        val dropped = sentBody(request(messages = messages), provider(AnthropicQuirks(cacheControl = false, redactedThinking = false)))
        assertFalse(dropped.contains("redacted_thinking"))
        val kept = sentBody(request(messages = messages), provider(noCache))
        assertTrue(kept.contains("""{"type":"redacted_thinking","data":"enc"}"""))
    }

    @Test
    fun `pdf documents become base64 document blocks in user turns and tool results`() {
        val pdf = DocumentPart("application/pdf", ImageSource.Base64("JVBERi0="))
        val messages = listOf(
            ModelMessage(Role.User, listOf(TextPart("看这份"), pdf)),
            ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("toolu_1"), "Read", json("""{"file_path":"/a.pdf"}""") as JsonObject))),
            ModelMessage(Role.User, listOf(ToolResultPart(CallId("toolu_1"), listOf(TextPart("共 1 页"), pdf)))),
        )
        assertJson(
            """{"model":"claude-sonnet-5-5","max_tokens":8000,"stream":true,"messages":[
              {"role":"user","content":[{"type":"text","text":"看这份"},
                {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0="}}]},
              {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/a.pdf"}}]},
              {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[
                {"type":"text","text":"共 1 页"},
                {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0="}}],"cache_control":{"type":"ephemeral"}}]}]}""",
            sentBody(request(messages = messages)),
        )
    }

    @Test
    fun `artifact pdf must be inlined before reaching the codec`() {
        val messages = listOf(ModelMessage(Role.User, listOf(DocumentPart("application/pdf", ImageSource.Artifact("a/b.pdf")))))
        try {
            sentBody(request(messages = messages))
            fail("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("a/b.pdf"))
        }
    }

    @Test
    fun `artifact images must be inlined before reaching the codec`() {
        val messages = listOf(ModelMessage(Role.User, listOf(ImagePart("image/png", ImageSource.Artifact("a/b.png")))))
        try {
            sentBody(request(messages = messages))
            fail("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("a/b.png"))
        }
    }

    @Test
    fun `wire model id is used instead of the ref`() {
        val p = profile().copy(wireModelId = "claude-sonnet-5-5-20260101")
        val parsed = json(sentBody(request(profile = p), provider(noCache))) as JsonObject
        assertEquals(JsonPrimitive("claude-sonnet-5-5-20260101"), parsed["model"])
    }
}
