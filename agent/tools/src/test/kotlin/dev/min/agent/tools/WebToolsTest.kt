package dev.min.agent.tools

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.policy.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebToolsTest {
    private val ctx = TestToolContext()
    private val url = "https://example.com/page"
    private fun page(text: String) {
        ctx.web = WebPort { WebResponse.Page(url, PageContent("Title", text)) }
        ctx.extraction = { _, _ -> ToolResult(listOf(TextPart("提取回答"))) }
    }

    @Test fun `reject local names and invalid urls before fetching`() {
        for (value in listOf("http://localhost:80/a", "https://intranet/a", "https://localhost./", "https://foo.localhost/", "file:///a", "https://u:p@example.com/", "garbage"))
            assertTrue(WebFetchTool.parse(args("url" to value, "prompt" to "x")) is ToolInput.Invalid)
        assertTrue(WebFetchTool.reject("url" to "http://localhost", "prompt" to "x").contains("curl"))
    }

    @Test fun `upgrade http and preserve encoded path query`() {
        assertEquals("https://EXAMPLE.com/a%2Fb?q=%25", WebFetchTool.parsed("url" to "http://EXAMPLE.com/a%2Fb?q=%25#frag", "prompt" to "x").url)
        assertTrue(WebFetchTool.parse(args("url" to url, "prompt" to " ")) is ToolInput.Invalid)
    }

    @Test fun `fetch permissions match existing domain rules and suggestion`() {
        val ask = WebFetchTool.permissionAsk(WebFetchTool.parsed("url" to url, "prompt" to "x"), ctx)
        assertEquals(listOf("example.com"), ask.domains)
        assertFalse(WebFetchTool.traits.readOnly)
        val policy = PolicyContext(PermissionMode.Default, emptyList(), ctx.cwd, GuestPath("/root"), ctx.fs::scopeOf)
        assertEquals(Verdict.Ask, PolicyEngine.evaluate(ask, false, policy))
        assertEquals("WebFetch(domain:example.com)", PolicyEngine.suggestRule(ask, policy))
        val rule = PermissionRule.from(RuleEntry(RuleKindData.Allow, "WebFetch(domain:example.com)", "test", ctx.cwd))!!
        assertTrue(PolicyEngine.evaluate(ask, false, policy.copy(rules = listOf(rule))) is Verdict.Allow)
    }

    @Test fun `default ports report unavailable`() = runBlocking {
        assertTrue(WebFetchTool.call(ctx, "url" to url, "prompt" to "x").isError)
        assertTrue(WebSearchTool.call(ctx, "query" to "x").isError)
    }

    @Test fun `redirect does not save or extract`() = runBlocking {
        ctx.web = WebPort { WebResponse.Redirect(url, "https://other.com/next") }
        val result = WebFetchTool.call(ctx, "url" to url, "prompt" to "x")
        assertFalse(result.isError)
        assertTrue(result.text.contains("$url → https://other.com/next"))
        assertTrue(ctx.extractions.isEmpty())
        assertTrue(ctx.artifacts.files.isEmpty())
    }

    @Test fun `binary reports type and size without extraction`() = runBlocking {
        ctx.web = WebPort { WebResponse.Unsupported(url, "application/pdf", 512) }
        val result = WebFetchTool.call(ctx, "url" to url, "prompt" to "x")
        assertTrue(result.text.contains("application/pdf（512 字节）"))
        assertTrue(ctx.artifacts.files.isEmpty())
        assertTrue(ctx.extractions.isEmpty())
    }

    @Test fun `save full page before truncating extraction and return readable path`() = runBlocking {
        val full = "中".repeat(WebFetchTool.EXTRACTION_MAX_CHARS + 20)
        page(full)
        ctx.extraction = { content, prompt ->
            assertEquals(100_000, content.length)
            assertEquals("question", prompt)
            assertEquals(full, ctx.artifacts.files.values.single().decodeToString())
            ToolResult(listOf(TextPart("提取回答")))
        }
        val result = WebFetchTool.call(ctx, "url" to url, "prompt" to "question")
        assertFalse(result.isError)
        assertEquals(full.length.toLong(), result.fullChars)
        assertTrue(result.artifact!!.matches(Regex("artifacts/web/example.com-[a-f0-9]{16}\\.md")))
        assertTrue(result.text.contains("提取回答"))
        assertTrue(result.text.contains(result.artifact!!))
        assertTrue(result.text.contains("100020 字符"))
    }

    @Test fun `extraction failure stays error and keeps attachment`() = runBlocking {
        page("full page")
        ctx.extraction = { _, _ -> ToolResult(listOf(TextPart("提取失败")), isError = true) }
        val result = WebFetchTool.call(ctx, "url" to url, "prompt" to "x")
        assertTrue(result.isError)
        assertFalse(result.text.contains("按你的提问提取"))
        assertNotNull(result.artifact)
        assertEquals("full page", ctx.artifacts.files.values.single().decodeToString())
    }

    @Test fun `new page does not overwrite prior attachment`() = runBlocking {
        page("first")
        val first = WebFetchTool.call(ctx, "url" to url, "prompt" to "x")
        page("second")
        val second = WebFetchTool.call(ctx, "url" to url, "prompt" to "x")
        assertNotEquals(first.artifact, second.artifact)
        assertEquals(2, ctx.artifacts.files.size)
    }

    @Test fun `search rejects conflicting lists including empty lists and wrong types`() {
        assertTrue(WebSearchTool.parse(args("query" to "x", "allowed_domains" to JsonArray(emptyList()), "blocked_domains" to JsonArray(emptyList()))) is ToolInput.Invalid)
        for (value in listOf(JsonPrimitive("example.com"), JsonArray(listOf(JsonPrimitive(1))), JsonArray(listOf(JsonPrimitive("https://example.com")))))
            assertTrue(WebSearchTool.parse(args("query" to "x", "allowed_domains" to value)) is ToolInput.Invalid)
        assertTrue(WebSearchTool.parse(args("query" to " ")) is ToolInput.Invalid)
    }

    @Test fun `search forwards domains formats numbered results and is read only`() = runBlocking {
        ctx.webSearch = WebSearchPort { query, allowed, blocked, limit ->
            assertEquals("kotlin", query)
            assertEquals(listOf("example.com"), allowed)
            assertTrue(blocked.isEmpty())
            assertEquals(10, limit)
            SearchResponse.Hits(listOf(SearchHit("Title", url, "Snippet"), SearchHit("Next", "https://example.com/2", "More")))
        }
        val result = WebSearchTool.call(ctx, "query" to "kotlin", "allowed_domains" to JsonArray(listOf(JsonPrimitive("EXAMPLE.COM"))))
        assertEquals("1. Title — $url — Snippet\n2. Next — https://example.com/2 — More", result.text)
        assertTrue(WebSearchTool.traits.readOnly)
        assertTrue(WebSearchTool.permissionAsk(WebSearchTool.parsed("query" to "x"), ctx).domains.isEmpty())
        assertTrue(ctx.artifacts.files.isEmpty())
        assertTrue(ctx.extractions.isEmpty())
    }

    @Test fun `search cap notice is not error`() = runBlocking {
        ctx.webSearch = WebSearchPort { _, _, _, _ -> SearchResponse.LimitReached }
        val result = WebSearchTool.call(ctx, "query" to "x")
        assertFalse(result.isError)
        assertEquals(WebSearchTool.LIMIT_NOTICE, result.text)
    }

    @Test fun `search bare deny works qualified rules do not and default is allowed`() {
        val ask = WebSearchTool.permissionAsk(WebSearchTool.parsed("query" to "x"), ctx)
        val policy = PolicyContext(PermissionMode.Default, emptyList(), ctx.cwd, GuestPath("/root"), ctx.fs::scopeOf)
        assertTrue(PolicyEngine.evaluate(ask, true, policy) is Verdict.Allow)
        val deny = PermissionRule.from(RuleEntry(RuleKindData.Deny, "WebSearch", "test", ctx.cwd))!!
        assertTrue(PolicyEngine.evaluate(ask, true, policy.copy(rules = listOf(deny))) is Verdict.Deny)
        val qualified = PermissionRule.from(RuleEntry(RuleKindData.Deny, "WebSearch(domain:example.com)", "test", ctx.cwd))!!
        assertTrue(PolicyEngine.evaluate(ask, true, policy.copy(rules = listOf(qualified))) is Verdict.Allow)
    }

    @Test fun `empty search has useful result`() = runBlocking {
        ctx.webSearch = WebSearchPort { _, _, _, _ -> SearchResponse.Hits(emptyList()) }
        assertEquals("没有找到搜索结果。", WebSearchTool.call(ctx, "query" to "x").text)
    }
}
