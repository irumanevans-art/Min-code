package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.McpUrlElicitationAsked
import dev.min.agent.spi.QuestionAsked
import dev.min.agent.spi.QuestionOption
import dev.min.agent.spi.UserQuestion
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.UrlRequest
import dev.min.code.ui.session.splitUrlHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP elicitation 的两种形状怎么变成界面的卡：表单题带服务器名，url 题带 URL 与域名且不画选项 */
class MinElicitationTest {
    private val text = FakeText()
    private val root = SessionId("s_root")

    @Test
    fun `a form question from an MCP server carries the server name, the model's own question does not`() {
        val qid = QuestionId("mcpq_1")
        val questions = listOf(UserQuestion("MCP 服务器 db 请求：选一个环境", "env", listOf(QuestionOption("prod", ""), QuestionOption("dev", ""))))
        val state = foldOf(QuestionAsked(TurnId("mcp-idle"), qid, CallId("mcp-idle"), questions, "db", standalone = true))
        val what = HubPending.What.Question(qid, CallId("mcp-idle"), questions)
        val card = minInteractions(listOf(HubPending(root, root, null, null, 5, what)), root, mapOf(root to state), emptyList(), text)
            .single() as PendingInteraction.Question
        assertEquals("db", card.server)
        assertNull(card.url)
        assertEquals(listOf("prod", "dev"), card.questions.single().options.map { it.label })
    }

    @Test
    fun `a url elicitation becomes a url card with the full address and the kernel's domain`() {
        val qid = QuestionId("mcpq_2")
        val asked = McpUrlElicitationAsked(qid, "pay", "https://pay.example/checkout?id=7", "pay.example", "请完成付款", "e1")
        val state = foldOf(asked)
        val what = HubPending.What.Question(qid, CallId("mcp-idle"), listOf(UserQuestion("x", "打开网址", listOf(QuestionOption("y", ""), QuestionOption("n", "")))), asked)
        val card = minInteractions(listOf(HubPending(root, root, null, null, 5, what)), root, mapOf(root to state), emptyList(), text)
            .single() as PendingInteraction.Question
        assertEquals("pay", card.server)
        assertEquals(UrlRequest("https://pay.example/checkout?id=7", "pay.example", "请完成付款"), card.url)
    }

    @Test
    fun `the host is found by URL syntax, not by the first place the domain string appears`() {
        val plain = splitUrlHost("https://pay.example:8443/a?b=c#d", "pay.example")!!
        assertEquals("https://", plain.before)
        assertEquals("pay.example", plain.host)
        assertEquals(":8443/a?b=c#d", plain.after)
        assertFalse(plain.hasUserInfo)

        val trick = splitUrlHost("https://evil.com.attacker.net@evil.com/path", "evil.com")!!
        assertEquals("https://evil.com.attacker.net@", trick.before)
        assertEquals("evil.com", trick.host)
        assertEquals("/path", trick.after)
        assertTrue("主机前面夹带用户名要警告", trick.hasUserInfo)
    }

    @Test
    fun `an IPv6 host and a mismatched domain`() {
        val v6 = splitUrlHost("http://[::1]:8080/x", "[::1]")!!
        assertEquals("[::1]", v6.host)
        assertEquals(":8080/x", v6.after)
        assertNull("内核给的域名与 URL 里的主机对不上就不高亮，整串原样显示", splitUrlHost("https://a.example/", "b.example"))
        assertNull(splitUrlHost("not a url", "a.example"))
    }
}
