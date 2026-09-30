package dev.min.agent.llm

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionTest {
    private val ds = ModelRef("deepseek", "deepseek-v4")
    private val claude = ModelRef("anthropic", "claude-sonnet-5-5")
    private val call = ToolCallPart(CallId("c1"), "Read", JsonObject(emptyMap()))

    private val mixed = listOf(
        ThinkingPart("自己想的", origin = ds),
        RedactedThinkingPart("xx", origin = claude),
        ThinkingPart("别人想的", origin = claude, signature = "sig"),
        OpaquePart(claude, JsonPrimitive(1)),
        TextPart("答案"),
        call,
    )

    @Test
    fun `foreign reasoning and opaque blocks are always dropped`() {
        val sent = partsToSend(mixed, target = ds, echo = ReasoningEcho.Always, inToolLoop = false)
        assertEquals(listOf(ThinkingPart("自己想的", origin = ds), TextPart("答案"), call), sent)
    }

    @Test
    fun `own reasoning follows the echo rule`() {
        val own = listOf(ThinkingPart("想", origin = ds), TextPart("答"))
        assertEquals(listOf(TextPart("答")), partsToSend(own, ds, ReasoningEcho.Never, inToolLoop = true))
        assertEquals(listOf(TextPart("答")), partsToSend(own, ds, ReasoningEcho.WithinToolLoop, inToolLoop = false))
        assertEquals(own, partsToSend(own, ds, ReasoningEcho.WithinToolLoop, inToolLoop = true))
        assertEquals(own, partsToSend(own, ds, ReasoningEcho.AlwaysIncludingEmpty, inToolLoop = false))
    }

    @Test
    fun `the tool loop starts after the last thing a person said`() {
        val messages = listOf(
            ModelMessage(Role.User, listOf(TextPart("问题一"))),
            ModelMessage(Role.Assistant, listOf(call)),
            ModelMessage(Role.User, listOf(ToolResultPart(CallId("c1"), listOf(TextPart("结果"))))),
            ModelMessage(Role.Assistant, listOf(TextPart("答一"))),
            ModelMessage(Role.User, listOf(TextPart("问题二"))),
            ModelMessage(Role.Assistant, listOf(call)),
            ModelMessage(Role.User, listOf(ToolResultPart(CallId("c1"), listOf(TextPart("结果"))))),
        )
        assertEquals(4, lastUserInputIndex(messages))
        assertEquals(-1, lastUserInputIndex(messages.drop(5)))
    }

    @Test
    fun `effort patches pick the nearest defined level, ties going lower`() {
        fun patch(level: String) = buildJsonObject { putJsonObject("thinking") { put("level", level) } }
        val profile = ModelProfile(
            ref = ds, family = ModelFamily.DeepSeek, contextWindow = 128_000, maxOutputTokens = 8_000,
            effortPatches = mapOf(Effort.Off to patch("off"), Effort.Medium to patch("medium"), Effort.Max to patch("max")),
        )
        assertEquals(patch("medium"), profile.patchFor(Effort.Medium))
        assertEquals(patch("off"), profile.patchFor(Effort.Minimal)) // 距 Off 1、距 Medium 2
        assertEquals(patch("medium"), profile.patchFor(Effort.Low)) // 距 Off 2、距 Medium 1
        assertEquals(patch("medium"), profile.patchFor(Effort.High)) // 距 Medium 1、距 Max 3
        assertEquals(patch("max"), profile.patchFor(Effort.XHigh))
        assertNull(profile.patchFor(null))
        assertNull(profile.copy(effortPatches = emptyMap()).patchFor(Effort.High))
    }

    @Test
    fun `deep merge recurses into objects and overwrites everything else`() {
        val base = buildJsonObject {
            put("model", "m")
            putJsonObject("thinking") { put("type", "disabled"); put("keep", 1) }
        }
        val patch = buildJsonObject {
            putJsonObject("thinking") { put("type", "enabled") }
            put("temperature", 1)
        }
        val merged = base.deepMerge(patch)
        assertEquals(
            buildJsonObject {
                put("model", "m")
                putJsonObject("thinking") { put("type", "enabled"); put("keep", 1) }
                put("temperature", 1)
            },
            merged,
        )
    }
}
