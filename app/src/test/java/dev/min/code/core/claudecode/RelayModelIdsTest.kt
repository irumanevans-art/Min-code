package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v1/models` 是个只读端点，认得宽一点没有代价：认错最多列表是空的，
 * 认不出来却会让一家能用的中转看上去坏了。
 */
class RelayModelIdsTest {
    @Test
    fun `reads the openai shape`() {
        val body = """{"object":"list","data":[{"id":"gpt-5","object":"model"},{"id":"gpt-5-mini"}]}"""
        assertEquals(listOf("gpt-5", "gpt-5-mini"), parseRelayModelIds(body))
    }

    @Test
    fun `reads the models key some relays use`() {
        assertEquals(listOf("glm-5"), parseRelayModelIds("""{"models":[{"id":"glm-5"}]}"""))
    }

    @Test
    fun `reads a bare array of strings`() {
        assertEquals(listOf("a", "b"), parseRelayModelIds("""["a","b"]"""))
    }

    @Test
    fun `falls back to name or model when there is no id`() {
        assertEquals(listOf("kimi-k2"), parseRelayModelIds("""{"data":[{"name":"kimi-k2"}]}"""))
        assertEquals(listOf("deepseek-v4"), parseRelayModelIds("""{"data":[{"model":"deepseek-v4"}]}"""))
    }

    @Test
    fun `drops duplicates and blanks`() {
        assertEquals(listOf("x"), parseRelayModelIds("""{"data":[{"id":"x"},{"id":"x"},{"id":""}]}"""))
    }

    @Test
    fun `garbage gives an empty list rather than throwing`() {
        assertTrue(parseRelayModelIds("not json").isEmpty())
        assertTrue(parseRelayModelIds("""{"data":{"id":"x"}}""").isEmpty())
        assertTrue(parseRelayModelIds("").isEmpty())
    }
}
