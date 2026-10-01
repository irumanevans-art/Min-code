package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeminiStreamTest {
    @Test fun `text frames emit started deltas and one completed at EOF`() {
        val outcome = parsed("""{"responseId":"r","candidates":[{"content":{"parts":[{"text":"你"}]}}]}""", content("""[{"text":"好"}]""", "STOP"))
        assertNull(outcome.error)
        assertEquals(listOf(ModelStreamEvent.Started("r"), ModelStreamEvent.TextDelta("你"), ModelStreamEvent.TextDelta("好")), outcome.events.dropLast(1))
        assertEquals(listOf(TextPart("你好")), outcome.completed.message.parts.filterIsInstance<TextPart>())
        assertEquals(Role.Assistant, outcome.completed.message.role)
        assertEquals(StopReason.EndTurn, outcome.completed.stop)
        assertFalse(outcome.completed.truncated)
    }
    @Test fun `text chunk boundaries preserve canonical text and raw signatures`() {
        val one = parsed(content("""[{"text":"abc","thoughtSignature":"S"}]""", "STOP")).completed
        val split = parsed(content("""[{"text":"a"}]"""), content("""[{"text":"bc","thoughtSignature":"S"}]""", "STOP")).completed
        assertEquals(one.message.parts.filterIsInstance<TextPart>(), split.message.parts.filterIsInstance<TextPart>())
        val raw = (split.message.parts.filterIsInstance<OpaquePart>().single().json as JsonObject)[RAW_PARTS]
        assertEquals(json("""[{"text":"a"},{"text":"bc","thoughtSignature":"S"}]"""), raw)
    }
    @Test fun `thoughts text calls and signatures retain their wire order`() {
        val raw = """[{"text":"t","thought":true,"thoughtSignature":"T"},{"text":"x"},{"functionCall":{"id":"c","name":"Read","args":{"path":"p"}},"thoughtSignature":"C"},{"text":"y"},{"thoughtSignature":"END"}]"""
        val out = parsed(content(raw, "STOP"))
        assertEquals(listOf(ThinkingPart("t", ref, "T"), TextPart("x"), ToolCallPart(CallId("c"), "Read", obj("""{"path":"p"}""")), TextPart("y")), out.completed.message.parts.filter { it !is OpaquePart })
        assertEquals(json(raw), (out.completed.message.parts.last() as OpaquePart).json.let { (it as JsonObject)[RAW_PARTS] })
        assertTrue(out.events.contains(ModelStreamEvent.ThinkingDelta("t")))
        assertTrue(out.events.contains(ModelStreamEvent.ToolCallStarted(0, "c", "Read")))
        assertTrue(out.events.contains(ModelStreamEvent.ToolInputDelta(0, """{"path":"p"}""")))
        assertEquals(StopReason.ToolUse, out.completed.stop)
    }
    @Test fun `idless calls have stable independent ordinals unrelated to text frames`() {
        val calls = """[{"functionCall":{"name":"Read","args":{"path":"a"}}},{"functionCall":{"name":"Read","args":{"path":"b"}}}]"""
        val one = parsed(content(calls, "STOP")).completed.message.parts.filterIsInstance<ToolCallPart>()
        val split = parsed(content("""[{"text":"before"}]"""), content(calls, "STOP")).completed.message.parts.filterIsInstance<ToolCallPart>()
        assertEquals(one, split)
        val hash = fingerprint(buildGeminiBody(request()).toString())
        assertEquals(listOf("gemini_${hash}_0", "gemini_${hash}_1"), one.map { it.callId.value })
        assertNotEquals(one[0].callId, one[1].callId)
    }
    @Test fun `same named calls split across frames stay distinct`() {
        val done = parsed(content("""[{"functionCall":{"name":"Read","args":{}}}]"""), content("""[{"functionCall":{"name":"Read","args":{}}}]""", "STOP")).completed
        assertEquals(2, done.message.parts.filterIsInstance<ToolCallPart>().map { it.callId }.distinct().size)
    }
    @Test fun `provider id is authoritative and duplicate ids fail`() {
        val out = parsed(content("""[{"functionCall":{"id":"wire","name":"Read"}}]""", "STOP"))
        assertEquals(CallId("wire"), out.completed.message.parts.filterIsInstance<ToolCallPart>().single().callId)
        val bad = parsed(content("""[{"functionCall":{"id":"wire","name":"Read"}},{"functionCall":{"id":"wire","name":"Read"}}]""", "STOP"))
        assertEquals(ModelFailureKind.Other, bad.modelError().kind)
        assertFalse(bad.events.any { it is ModelStreamEvent.Completed })
    }
    @Test fun `missing args becomes object and invalid args preserves raw`() {
        for ((args, expected) in listOf("" to "{}", ",\"args\":[1]" to """{"__invalid_json":"[1]"}""", ",\"args\":\"{broken\"" to """{"__invalid_json":"\"{broken\""}""")) {
            assertEquals(obj(expected), parsed(content("""[{"functionCall":{"name":"Read"$args}}]""", "STOP")).completed.message.parts.filterIsInstance<ToolCallPart>().single().input)
        }
    }
    @Test fun `missing name is a protocol error`() {
        for (call in listOf("{}", """{"id":"c","args":{}}""", """{"name":""}""", """{"name":123}""")) {
            assertEquals(ModelFailureKind.Other, parsed(content("[{\"functionCall\":$call}]", "STOP")).modelError().kind)
        }
    }
    @Test fun `empty parts create no deltas and signature only parts survive`() {
        val out = parsed(content("""[{}, {"text":""}, {"text":"","thought":true}, {"thoughtSignature":"S"}]""", "STOP"))
        assertEquals(2, out.events.size)
        val raw = (out.completed.message.parts.filterIsInstance<OpaquePart>().single().json as JsonObject)[RAW_PARTS]
        assertEquals(json("""[{"text":""},{"text":"","thought":true},{"thoughtSignature":"S"}]"""), raw)
    }
    @Test fun `empty thought with signature is canonical thinking`() {
        assertEquals(listOf(ThinkingPart("", ref, "S")), parsed(content("""[{"text":"","thought":true,"thoughtSignature":"S"}]""", "STOP")).completed.message.parts.filterIsInstance<ThinkingPart>())
    }
    @Test fun `usage only tail is read after finish and thoughts count toward output`() {
        val out = parsed(content("""[{"text":"ok"}]""", "STOP"), """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40,"candidatesTokenCount":8,"thoughtsTokenCount":12,"totalTokenCount":120,"toolUsePromptTokenCount":50}}""")
        assertEquals(Usage(60, 20, 40, 0), out.completed.usage)
    }
    @Test fun `usage snapshots overwrite and absent fields retain prior values`() {
        val out = parsed("""{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":30,"thoughtsTokenCount":4,"candidatesTokenCount":2}}""",
            """{"usageMetadata":{"candidatesTokenCount":8}}""", stopFrame)
        assertEquals(Usage(70, 12, 30, 0), out.completed.usage)
        assertEquals(ModelStreamEvent.Started(null), out.events.first())
    }
    @Test fun `missing usage stays zero and explicit zero resets snapshot`() {
        assertEquals(Usage(), parsed(stopFrame).completed.usage)
        assertEquals(Usage(), parsed("""{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":10}}""", """{"usageMetadata":{"promptTokenCount":0,"candidatesTokenCount":0}}""", stopFrame).completed.usage)
    }
    @Test fun `cache exceeding prompt never makes negative input`() {
        assertEquals(Usage(0, 0, 20, 0), parsed("""{"usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":20}}""", stopFrame).completed.usage)
    }
    @Test fun `max tokens marks truncation and removes executable calls`() {
        val done = parsed(content("""[{"functionCall":{"name":"Read","args":{}}}]""", "MAX_TOKENS")).completed
        assertEquals(StopReason.MaxTokens, done.stop); assertTrue(done.truncated)
        assertTrue(done.message.parts.none { it is ToolCallPart })
    }
    @Test fun `refusal reasons map explicitly and remove executable calls`() {
        for (reason in listOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "ESCALATION", "PUP_LIMITED_DISABLED")) {
            val done = parsed(content("""[{"functionCall":{"name":"Read"}}]""", reason)).completed
            assertEquals(reason, StopReason.Refusal, done.stop); assertFalse(done.truncated)
            assertTrue(done.message.parts.none { it is ToolCallPart })
        }
    }
    @Test fun `unknown and malformed stop reasons retain raw without executable calls`() {
        for (reason in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "LANGUAGE", "OTHER", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE", "FUTURE_REASON")) {
            val done = parsed(content("""[{"functionCall":{"name":"Read"}}]""", reason)).completed
            assertEquals(StopReason.Other(reason), done.stop); assertFalse(done.truncated)
            assertTrue(done.message.parts.none { it is ToolCallPart })
        }
    }
    @Test fun `prompt blocked without candidates completes as refusal`() {
        val done = parsed("""{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":7}}""").completed
        assertEquals(StopReason.Refusal, done.stop); assertEquals(Usage(7), done.usage)
    }
    @Test fun `unspecified stop and block markers are not terminal`() {
        assertEquals(ModelFailureKind.Network, parsed(content("[]", "FINISH_REASON_UNSPECIFIED")).modelError().kind)
        assertEquals(ModelFailureKind.Network, parsed("""{"promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"}}""").modelError().kind)
    }
    @Test fun `normal EOF before finish fails with accurate first byte`() {
        assertTrue(parsed().modelError().beforeFirstByte)
        val out = parsed(content("""[{"text":"partial"}]"""))
        assertFalse(out.modelError().beforeFirstByte); assertEquals(ModelFailureKind.Network, out.modelError().kind)
        assertTrue(out.events.none { it is ModelStreamEvent.Completed })
    }
    @Test fun `IO after finish still fails because tail may contain usage or error`() {
        val out = run(FakeHttpTransport(stream(stopFrame, failure = IOException("reset"))))
        assertEquals(ModelFailureKind.Network, out.modelError().kind); assertFalse(out.modelError().beforeFirstByte)
        assertTrue(out.events.none { it is ModelStreamEvent.Completed })
    }
    @Test fun `first candidate index zero only and others are ignored`() {
        val done = parsed("""{"candidates":[{"index":1,"content":{"parts":[{"text":"bad"}]}},{"index":0,"content":{"parts":[{"text":"good"}]},"finishReason":"STOP"}]}""").completed
        assertEquals(listOf(TextPart("good")), done.message.parts.filterIsInstance<TextPart>())
    }
    @Test fun `unknown native content is rejected`() {
        for (part in listOf("""{"executableCode":{"code":"x"}}""", """{"fileData":{"fileUri":"x"}}""", """{"text":"x","functionCall":{"name":"Read"}}""")) {
            assertEquals(ModelFailureKind.Other, parsed(content("[$part]", "STOP")).modelError().kind)
        }
    }
    @Test fun `inline output images are preserved`() {
        assertEquals(listOf(ImagePart("image/png", ImageSource.Base64("QUJD"))), parsed(content("""[{"inlineData":{"mimeType":"image/png","data":"QUJD"}}]""", "STOP")).completed.message.parts.filterIsInstance<ImagePart>())
    }
}
