package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultBudgetTest {
    private class MemorySink : ArtifactSink {
        val stored = mutableMapOf<String, ByteArray>()
        override suspend fun put(name: String, bytes: ByteArray): String {
            stored[name] = bytes
            return "/artifacts/$name"
        }
    }

    @Test
    fun `preview cut points never split an emoji and the full text is kept`() = runTest {
        // 全是 emoji：每两个 char 一对，预算取奇数一半时头尾截点都正好落在对子中间
        val full = "😀".repeat(100)
        for (budget in listOf(9, 10, 11, 21)) {
            val sink = MemorySink()
            val out = ResultBudget.apply(ToolResult(listOf(TextPart(full))), budget, sink, CallId("c1"))
            val preview = (out.content.single() as TextPart).text
            assertNoLoneSurrogate(preview)
            assertEquals(full, sink.stored.values.single().toString(Charsets.UTF_8))
            assertEquals(full.length.toLong(), out.fullChars)
            // 省略数等于真正没给出的 char 数：头 + 省略 + 尾 = 全长
            val head = preview.substringBefore("\n\n…（中间省略 ")
            val omitted = Regex("中间省略 (\\d+) 个字符").find(preview)!!.groupValues[1].toInt()
            val tail = preview.substringAfter("）…\n\n").substringBefore("\n\n[输出共")
            assertEquals("budget=$budget", full.length, head.length + omitted + tail.length)
            assertTrue(head.length <= budget / 2 && tail.length <= budget / 2)
        }
    }

    private fun assertNoLoneSurrogate(s: String) {
        s.forEachIndexed { i, c ->
            if (c.isHighSurrogate()) assertTrue("孤立高代理 @$i", i + 1 < s.length && s[i + 1].isLowSurrogate())
            if (c.isLowSurrogate()) assertTrue("孤立低代理 @$i", i > 0 && s[i - 1].isHighSurrogate())
        }
    }
}
