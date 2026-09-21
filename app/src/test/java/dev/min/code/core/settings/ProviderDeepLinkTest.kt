package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一条链接带进来的是会被立刻拿去发请求的地址和 key，格式认错的代价直接落在用户身上。
 * cc-switch 那套（`ccswitch://v1/import?…`）要原样认得下来——别人分享的链接都是那个形状。
 */
class ProviderDeepLinkTest {
    @Test
    fun `parses a cc-switch provider link`() {
        val link = "ccswitch://v1/import?resource=provider&app=claude&name=My%20Provider" +
            "&endpoint=https%3A%2F%2Fapi.example.com&apiKey=sk-xxx"
        val parsed = parseProviderDeepLink(link) as DeepLinkParse.Claude
        assertEquals("My Provider", parsed.profile.label)
        assertEquals("https://api.example.com", parsed.profile.baseUrl)
        assertEquals("sk-xxx", parsed.profile.token)
    }

    @Test
    fun `parses our own scheme and chinese names`() {
        val link = "minc://v1/import?resource=provider&name=%E6%9F%90%E6%9F%90%E4%B8%AD%E8%BD%AC" +
            "&endpoint=https%3A%2F%2Fa.cn&apiKey=sk-1&notes=%E5%85%AC%E5%8F%B8%E5%8F%B7"
        val parsed = parseProviderDeepLink(link) as DeepLinkParse.Claude
        assertEquals("某某中转", parsed.profile.label)
        assertEquals("公司号", parsed.profile.note)
    }

    /** 多端点是 cc-switch 的测速用法。取第一个，其余留在备注里——丢掉等于吃掉用户的备用线路 */
    @Test
    fun `keeps the spare endpoints in the note`() {
        val link = "ccswitch://v1/import?resource=provider&name=x&endpoint=https://a.com,https://b.com"
        val parsed = parseProviderDeepLink(link) as DeepLinkParse.Claude
        assertEquals("https://a.com", parsed.profile.baseUrl)
        assertTrue(parsed.profile.note.contains("https://b.com"))
    }

    @Test
    fun `model parameters land in env as the three aliases`() {
        val link = "ccswitch://v1/import?resource=provider&name=x&endpoint=https://a.com" +
            "&model=glm-5&haikuModel=glm-5-air&sonnetModel=glm-5&opusModel=glm-5"
        val parsed = parseProviderDeepLink(link) as DeepLinkParse.Claude
        assertEquals("glm-5", parsed.profile.env["ANTHROPIC_MODEL"])
        assertEquals("glm-5-air", parsed.profile.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("glm-5", parsed.profile.env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
    }

    @Test
    fun `codex links become a relay entry`() {
        val link = "ccswitch://v1/import?resource=provider&app=codex&name=x" +
            "&endpoint=https%3A%2F%2Fa.com%2Fv1&apiKey=sk-2&model=deepseek-v4"
        val parsed = parseProviderDeepLink(link) as DeepLinkParse.Codex
        assertEquals(CodexAuthMode.RELAY, parsed.profile.authMode)
        assertEquals("https://a.com/v1", parsed.profile.baseUrl)
        assertEquals("deepseek-v4", parsed.profile.model)
    }

    /** 做不到的要如实说出来：静默忽略会让人以为链接坏了，回去反复重新生成 */
    @Test
    fun `says what it cannot do instead of swallowing`() {
        val mcp = parseProviderDeepLink("ccswitch://v1/import?resource=mcp&name=x&config=%7B%7D")
        assertEquals(DeepLinkParse.Unsupported("mcp"), mcp)
        val gemini = parseProviderDeepLink("ccswitch://v1/import?resource=provider&app=gemini&name=x")
        assertEquals(DeepLinkParse.Unsupported("gemini"), gemini)
    }

    @Test
    fun `other links are not import links`() {
        assertEquals(DeepLinkParse.NotImport, parseProviderDeepLink("https://example.com"))
        assertEquals(DeepLinkParse.NotImport, parseProviderDeepLink("ccswitch://v1/open"))
        assertTrue(isProviderDeepLink("MINC://v1/import?resource=provider"))
    }

    /** 一个坏掉的转义不该让整条链接作废 */
    @Test
    fun `survives a broken escape`() {
        val parsed = parseProviderDeepLink("minc://v1/import?resource=provider&name=%E4%B8&endpoint=https://a.com")
        assertTrue(parsed is DeepLinkParse.Claude)
    }
}
