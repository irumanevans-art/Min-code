package dev.min.agent.context

import dev.min.agent.llm.ModelProfile
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDegradeTest {
    private val blind = ModelProfile(ref = ModelRef("test", "m"), family = ModelFamily.Other, contextWindow = 100_000, maxOutputTokens = 4_000)
    private val seeing = blind.copy(supportsImages = true)
    private val env = Environment(platform = "test", cwd = "/workspace", date = "2026-10-06")
    private val image = ImagePart("image/png", ImageSource.Artifact("a/1.png"))

    private val messages = listOf(
        ModelMessage(Role.User, listOf(TextPart("看这张图"), image)),
        ModelMessage(Role.User, listOf(ToolResultPart(CallId("c1"), listOf(TextPart("截图如下"), image)))),
        ModelMessage(Role.Assistant, listOf(TextPart("好的"))),
    )

    private fun assemble(profile: ModelProfile) =
        RequestAssembler.assemble(profile, env, messages, emptyList(), effort = null, cacheKey = "k").messages

    @Test
    fun `a model without image support gets a placeholder in place of every image`() {
        val sent = assemble(blind)
        val parts = sent.flatMap { m -> m.parts.flatMap { (it as? ToolResultPart)?.content ?: listOf(it) } }
        assertTrue(parts.none { it is ImagePart })
        assertEquals(2, parts.count { it is TextPart && it.text.contains("image/png") && it.text.contains("不支持看图") })
        // 周围的文字与结构原样：工具结果仍是同一个 callId 的工具结果
        assertEquals(TextPart("看这张图"), sent[0].parts[0])
        assertEquals(CallId("c1"), (sent[1].parts.single() as ToolResultPart).callId)
    }

    @Test
    fun `messages without images are passed through untouched`() {
        assertSame(messages[2], assemble(blind)[2])
    }

    @Test
    fun `a model with image support gets the images`() {
        assertEquals(messages, assemble(seeing))
    }
}
