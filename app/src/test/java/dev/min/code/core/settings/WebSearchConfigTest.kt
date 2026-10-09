package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchConfigTest {
    @Test fun `by default nothing is configured`() {
        assertFalse(WebSearchConfig().configured)
        assertEquals(SearchBackendKind.NONE, WebSearchConfig().backend)
    }

    @Test fun `a backend only counts as configured with its own key or a valid address`() {
        assertFalse("选了 Brave 没填 key", WebSearchConfig(SearchBackendKind.BRAVE).configured)
        assertFalse("填的是 Tavily 的 key，选的是 Brave", WebSearchConfig(SearchBackendKind.BRAVE, tavilyKey = "t").configured)
        assertTrue(WebSearchConfig(SearchBackendKind.BRAVE, braveKey = "b").configured)
        assertFalse(WebSearchConfig(SearchBackendKind.TAVILY, braveKey = "b").configured)
        assertTrue(WebSearchConfig(SearchBackendKind.TAVILY, tavilyKey = "t").configured)
        assertFalse(WebSearchConfig(SearchBackendKind.BRAVE, braveKey = "   ").configured)
        assertFalse("选了 SearXNG 没填地址", WebSearchConfig(SearchBackendKind.SEARXNG).configured)
        assertFalse(WebSearchConfig(SearchBackendKind.SEARXNG, searxngUrl = "ftp://x.example.com").configured)
        assertTrue(WebSearchConfig(SearchBackendKind.SEARXNG, searxngUrl = "http://10.0.2.2:8080").configured)
        assertFalse("没选后端，哪怕 key 都填着", WebSearchConfig(SearchBackendKind.NONE, "b", "t", "https://s.example.com").configured)
    }

    @Test fun `searxng addresses accept only http or https without credentials query or fragment`() {
        assertEquals("http://10.0.2.2:8080", parseSearxngUrl("  http://10.0.2.2:8080  "))
        assertEquals("https://search.example.com/base/", parseSearxngUrl("https://search.example.com/base/"))
        for (bad in listOf("", "  ", "ftp://example.com", "file:///sdcard/x", "javascript:alert(1)", "https://user:pw@example.com", "https://example.com?token=x", "https://example.com#f", "example.com", "http://", "not a url")) {
            assertNull("应拒绝：$bad", parseSearxngUrl(bad))
        }
    }

    @Test fun `toString never prints a key or an address`() {
        val text = WebSearchConfig(SearchBackendKind.BRAVE, "BRAVE-SECRET", "TAVILY-SECRET", "https://private.example.com/token123").toString()
        assertFalse(text, "BRAVE-SECRET" in text || "TAVILY-SECRET" in text || "private.example.com" in text || "token123" in text)
        assertTrue(text, "BRAVE" in text)
        assertTrue("没填的标成 unset", "unset" in WebSearchConfig().toString())
        // 整份设置的字符串形式也经过它
        assertFalse("BRAVE-SECRET" in AppSettings(webSearch = WebSearchConfig(SearchBackendKind.BRAVE, "BRAVE-SECRET")).toString())
    }

    @Test fun `json round trips and bad data reads as not configured`() {
        val config = WebSearchConfig(SearchBackendKind.TAVILY, "b", "t", "https://s.example.com")
        assertEquals(config, decodeWebSearchJson(encodeWebSearchJson(config)))
        assertEquals(WebSearchConfig(), decodeWebSearchJson(""))
        assertEquals(WebSearchConfig(), decodeWebSearchJson("{not json"))
        // 未知的后端名（将来版本写的）退回「不设」，已填的 key 原样留着：用户回到旧版本再回来不必重填
        val future = decodeWebSearchJson("""{"backend":"BING","braveKey":"x"}""")
        assertEquals(WebSearchConfig(SearchBackendKind.NONE, braveKey = "x"), future)
        assertFalse(future.configured)
        assertEquals("多出来的字段忽略", WebSearchConfig(SearchBackendKind.BRAVE, "x"), decodeWebSearchJson("""{"backend":"BRAVE","braveKey":"x","future":1}"""))
    }

    @Test fun `an empty key box keeps the saved key and only clear removes it`() {
        val saved = WebSearchConfig(SearchBackendKind.BRAVE, braveKey = "OLD-B", tavilyKey = "OLD-T", searxngUrl = "https://s.example.com")
        // 只换后端：两家的 key 与地址都还在，来回切不丢
        val switched = saved.applying(WebSearchEdit(SearchBackendKind.TAVILY, searxngUrl = "https://s.example.com"))
        assertEquals(WebSearchConfig(SearchBackendKind.TAVILY, "OLD-B", "OLD-T", "https://s.example.com"), switched)
        // 输入框里是新 key：替换，并去掉首尾空白
        assertEquals("NEW", saved.applying(WebSearchEdit(SearchBackendKind.BRAVE, newBraveKey = "  NEW \n", searxngUrl = "https://s.example.com")).braveKey)
        // 点了「清除」：清掉
        assertEquals("", saved.applying(WebSearchEdit(SearchBackendKind.BRAVE, clearBraveKey = true, searxngUrl = "https://s.example.com")).braveKey)
        // 清除又输了新的：以新的为准
        assertEquals("NEW", saved.applying(WebSearchEdit(SearchBackendKind.BRAVE, newBraveKey = "NEW", clearBraveKey = true, searxngUrl = "https://s.example.com")).braveKey)
        // 清除只动自己那家
        assertEquals("OLD-T", saved.applying(WebSearchEdit(SearchBackendKind.BRAVE, clearBraveKey = true, searxngUrl = "https://s.example.com")).tavilyKey)
    }

    @Test fun `the address is normalised cleared or kept when invalid`() {
        val saved = WebSearchConfig(SearchBackendKind.SEARXNG, searxngUrl = "https://old.example.com")
        assertEquals("http://10.0.2.2:8080", saved.applying(WebSearchEdit(SearchBackendKind.SEARXNG, searxngUrl = " http://10.0.2.2:8080 ")).searxngUrl)
        assertEquals("", saved.applying(WebSearchEdit(SearchBackendKind.SEARXNG, searxngUrl = "  ")).searxngUrl)
        assertEquals("不合法的不覆盖原值", "https://old.example.com", saved.applying(WebSearchEdit(SearchBackendKind.SEARXNG, searxngUrl = "ftp://x")).searxngUrl)
    }
}
