package dev.min.agent.kernel

import dev.min.agent.mcp.McpCallResult
import dev.min.agent.mcp.McpContent
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.TextPart
import dev.min.agent.testkit.FakeArtifacts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** MCP 结果里的图片（MCP设计 §7）：只把各家视觉接口认的格式给模型 */
class McpResultsTest {
    @Test
    fun `an image format the vision APIs reject is kept as an attachment, not sent to the model`() = runBlocking {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val artifacts = FakeArtifacts()
        val svg = McpResults.toToolResult("srv", "t", "c1", McpCallResult(listOf(McpContent.Image(data, "image/svg+xml")), null, false), artifacts)
        assertTrue("svg 进了日志，之后每个请求都会被供应商拒绝", svg.content.none { it is ImagePart })
        val text = svg.content.filterIsInstance<TextPart>().single().text
        assertTrue(text, text.contains("image/svg+xml") && text.contains("原图在"))
        val jpg = McpResults.toToolResult("srv", "t", "c2", McpCallResult(listOf(McpContent.Image(data, "image/jpg")), null, false), artifacts)
        assertEquals("非标准的 image/jpg 改成 image/jpeg 再给", "image/jpeg", jpg.content.filterIsInstance<ImagePart>().single().mediaType)
    }
}
