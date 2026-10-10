package dev.min.code.core.engine.min

import dev.min.agent.host.CachedWebPort
import dev.min.agent.kernel.HubPending
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.Clock
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.WebPort
import dev.min.agent.spi.WebResponse
import dev.min.agent.tools.web.HtmlPageConverter
import dev.min.code.core.engine.PendingInteraction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 的 Min 引擎有 WebFetch：对着 [MinKernelDeps.build] 的组合根跑真内核，
 * 看工具表里的位置、`WebFetch(domain:…)` 规则的 allow / ask / deny、宿主侧 HTTP 与桌面一致的处理（重定向、非 HTML、超时）。
 */
class MinWebFetchTest {
    private val rigs = mutableListOf<WebRig>()
    private fun rig(settingsJson: String?, http: ScriptedHttp, vararg steps: ModelStep, web: WebPort = dev.min.agent.host.CachedWebPort(HtmlPageConverter, http)) =
        WebRig(settingsJson, http, *steps, web = web).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    private fun html(body: String) = htmlResponse(body)
    private fun ask(rt: dev.min.agent.kernel.SessionRuntime, rig: WebRig) = rig.awaitApproval(rt)

    @Test fun `WebFetch is appended after the base tool list, nothing before it moved, and the background-task tools follow it`() {
        val names = minTools().map { it.name }
        assertEquals(listOf("WebFetch", "Monitor", "TaskOutput", "EndConversation", "PushNotification", "ListAgents"), names.takeLast(6))
        assertEquals(
            listOf(
                "Read", "Write", "Edit", "NotebookEdit", "Glob", "Grep", "Bash", "Agent", "SendMessage", "TaskStop",
                "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
                "Skill", "EnterWorktree", "ExitWorktree", "TeamCreate", "TeamDisband", "CronCreate", "CronDelete", "CronList", "ScheduleWakeup",
            ),
            names.dropLast(6),
        )
        assertTrue("WebSearch 不在基础表里：它随设置进出", "WebSearch" !in names)
    }

    @Test fun `the Android composition root gives the kernel a real web port`() {
        val rig = rig(null, ScriptedHttp { error("not used") }, web = CachedWebPort(HtmlPageConverter))
        assertTrue(rig.deps.host.web is CachedWebPort)
        assertTrue(rig.deps.host.web !== WebPort.Unavailable)
        // 不传 web 时用的默认值也不能是 Unavailable：这一行钉的是 build 的默认，不是上面注入的那个
        val defaults = MinKernelDeps.build(models = { error("not used") }, ports = MinHostPorts({ error("not used") }, MinBackgroundTasks(), Clock { 0L }))
        assertTrue(defaults.host.web is CachedWebPort)
        assertNull("没配后端时内核看到的是 null", defaults.host.searchBackend)
        assertEquals("搜索次数上限照桌面默认", 200, defaults.maxWebSearches)
    }

    // ---- WebFetch：权限规则 ----

    private val rules = """{"permissions":{"allow":["WebFetch(domain:allow.example.com)"],"deny":["WebFetch(domain:deny.example.com)"]}}"""

    @Test fun `an allow rule runs WebFetch without a card and the page goes through the host side http`() = runBlocking {
        val http = ScriptedHttp { html("<html><head><title>Hello</title></head><body><main><h1>Title</h1><p>Body text</p></main></body></html>") }
        val rig = rig(rules, http, fetchCall("https://allow.example.com/a"), answer("EXTRACTED"), answer("done"))
        val rt = rig.newRoot()
        rt.send(ClientMsgId("u1"), listOf(TextPart("go"))); rig.settle(rt)
        assertTrue("规则放行，不该出现审批卡", rig.hub.pending.value.isEmpty())
        assertEquals(listOf("https://allow.example.com/a"), http.requests.map { it.url })
        val text = rig.toolText(rt, "c1")
        assertTrue(text, "EXTRACTED" in text)
        // 抽取请求里是转换后的 Markdown，不是原始 HTML
        val extraction = rig.model.requests[1].allText()
        assertTrue(extraction, "# Title" in extraction && "Body text" in extraction && "<main>" !in extraction)
    }

    @Test fun `a deny rule blocks WebFetch before any request leaves the phone`() = runBlocking {
        val http = ScriptedHttp { error("must not be called") }
        val rig = rig(rules, http, fetchCall("https://deny.example.com/x"), answer("ok"))
        val rt = rig.newRoot()
        rt.send(ClientMsgId("u1"), listOf(TextPart("go"))); rig.settle(rt)
        assertTrue(http.requests.isEmpty())
        assertTrue(rig.hub.pending.value.isEmpty())
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    @Test fun `no rule asks with the domain on the card and allowing then fetches`() = runBlocking {
        val http = ScriptedHttp { html("<html><body><p>hi</p></body></html>") }
        val rig = rig(rules, http, fetchCall("https://ask.example.com/page?q=1"), answer("EXTRACTED"), answer("done"))
        val rt = rig.newRoot()
        rt.send(ClientMsgId("u1"), listOf(TextPart("go")))
        val pending = ask(rt, rig)
        val what = pending.what as dev.min.agent.kernel.HubPending.What.Approval
        assertEquals("WebFetch", what.tool)
        assertEquals("卡片上要列得出域名", listOf("ask.example.com"), what.ask.domains)
        assertTrue(what.ask.summary, "ask.example.com" in what.ask.summary)
        assertEquals("总是允许写的是域名规则，不是整个工具", "WebFetch(domain:ask.example.com)", what.suggestedRule)
        assertTrue("批准之前一个请求都没发", http.requests.isEmpty())
        // 同一张卡，经过 Android 的映射后界面拿到的域名
        val card = minInteractions(listOf(pending), rt.sessionId, mapOf(rt.sessionId to rt.state.value), emptyList(), FakeText())
            .single() as PendingInteraction.Approval
        assertEquals(listOf("ask.example.com"), card.domains)
        assertTrue(rt.answerApproval(what.approvalId, ApprovalChoice.Once))
        rig.settle(rt)
        assertEquals(1, http.requests.size)
        assertFalse(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    @Test fun `denying the card never sends the request`() = runBlocking {
        val http = ScriptedHttp { error("must not be called") }
        val rig = rig(rules, http, fetchCall("https://ask.example.com/"), answer("ok"))
        val rt = rig.newRoot()
        rt.send(ClientMsgId("u1"), listOf(TextPart("go")))
        val what = ask(rt, rig).what as HubPending.What.Approval
        assertTrue(rt.answerApproval(what.approvalId, ApprovalChoice.Deny(null)))
        rig.settle(rt)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `the always option writes a domain rule and the next fetch of that domain skips the card`() = runBlocking {
        val http = ScriptedHttp { html("<html><body><p>hi</p></body></html>") }
        val rig = rig(
            rules, http,
            fetchCall("https://ask.example.com/1", "c1"), answer("E1"), fetchCall("https://ask.example.com/2", "c2"), answer("E2"), answer("done"),
        )
        val rt = rig.newRoot()
        rt.send(ClientMsgId("u1"), listOf(TextPart("go")))
        val what = ask(rt, rig).what as HubPending.What.Approval
        assertTrue(rt.answerApproval(what.approvalId, ApprovalChoice.Always(requireNotNull(what.suggestedRule))))
        rig.settle(rt)
        assertEquals(listOf("https://ask.example.com/1", "https://ask.example.com/2"), http.requests.map { it.url })
        assertTrue("第二次同域名不该再问", rig.hub.pending.value.isEmpty())
    }

    // ---- WebFetch：超时、重定向、非 HTML（与桌面同一个 CachedWebPort） ----

    @Test fun `redirects to another host are reported not followed and same host redirects are followed`() = runBlocking {
        val http = ScriptedHttp { req ->
            when (req.url) {
                "https://a.example.com/old" -> HttpResponse(301, listOf("Location" to "https://a.example.com/new"), ByteArray(0))
                "https://a.example.com/new" -> html("<html><body><p>moved here</p></body></html>")
                else -> HttpResponse(302, listOf("Location" to "https://elsewhere.example.org/x"), ByteArray(0))
            }
        }
        val rig = rig(null, http, web = CachedWebPort(HtmlPageConverter, http))
        val same = rig.deps.host.web.fetch("https://a.example.com/old")
        assertTrue(same is WebResponse.Page && "moved here" in same.content.markdown)
        val cross = rig.deps.host.web.fetch("https://b.example.com/start")
        assertEquals(WebResponse.Redirect("https://b.example.com/start", "https://elsewhere.example.org/x"), cross)
    }

    @Test fun `non html content is reported as unsupported and a slow server hits the deadline`() = runBlocking {
        val http = ScriptedHttp { req ->
            if (req.url.endsWith("/slow")) { delay(5_000); html("late") }
            else HttpResponse(200, listOf("Content-Type" to "application/pdf"), ByteArray(1234))
        }
        val rig = rig(null, http, web = CachedWebPort(HtmlPageConverter, http, deadlineMs = 150))
        val pdf = rig.deps.host.web.fetch("https://a.example.com/doc.pdf")
        assertEquals(WebResponse.Unsupported("https://a.example.com/doc.pdf", "application/pdf", 1234), pdf)
        val error = try { rig.deps.host.web.fetch("https://a.example.com/slow"); null } catch (e: IllegalStateException) { e }
        assertNotNull("超过总截止时间要报错，不是一直挂着", error)
    }
}
