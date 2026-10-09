package dev.min.code.core.engine.min

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.llm.ModelRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStatusException
import dev.min.code.core.settings.SearchBackendKind
import dev.min.code.core.settings.WebSearchConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 的 Min 引擎有 WebSearch：工具随设置动态进出（不重建内核）、三种后端的接线、key 不外漏。
 */
class MinWebSearchTest {
    private val rigs = mutableListOf<WebRig>()
    private fun rig(http: ScriptedHttp, vararg steps: ModelStep, tools: List<dev.min.agent.spi.Tool<*>> = minTools(), config: (() -> WebSearchConfig)? = null) =
        WebRig(null, http, *steps, tools = tools, searchBackend = config?.let { ConfiguredSearchBackend(it, http) }).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    /** 设备工具只看「在不在表里」，不会被调用 */
    private object NoDevice : dev.min.code.core.device.DeviceActions {
        private val none = dev.min.code.core.device.DeviceController.Result.success("")
        override fun uiTree() = none
        override fun screenshot() = dev.min.code.core.device.DeviceController.ImageResult.success("", "")
        override fun tap(index: Int) = none
        override fun input(index: Int, text: String) = none
        override fun swipe(direction: String, index: Int?) = none
        override fun openApp(packageName: String) = none
        override fun back() = none
    }
    @Test fun `WebSearch follows the setting on every read and sits after the device tools`() {
        var searchOn = false
        var deviceOn = false
        val device = NoDevice
        val base = minTools()
        val list = minToolsWithDevice(device, { deviceOn }, base, webSearchEnabled = { searchOn })
        assertEquals(base.map { it.name }, list.map { it.name })
        searchOn = true
        assertEquals(base.map { it.name } + "WebSearch", list.map { it.name })
        deviceOn = true
        val full = list.map { it.name }
        assertEquals("WebSearch", full.last())
        assertEquals(base.size + 7 + 1, full.size)
        assertEquals("设备工具紧跟基础表，WebSearch 排在最末", base.size, full.indexOf("DeviceUiTree"))
        searchOn = false
        assertTrue("WebSearch" !in list.map { it.name })
        deviceOn = false
        assertEquals(base.map { it.name }, list.map { it.name })
    }

    @Test fun `without a device the search gate still works and without a gate the plain list comes back`() {
        var on = false
        val gated = minToolsWithDevice(null, { true }, webSearchEnabled = { on })
        assertEquals(minTools().map { it.name }, gated.map { it.name })
        on = true
        assertEquals(minTools().map { it.name } + "WebSearch", gated.map { it.name })
        assertEquals(minTools().map { it.name }, minToolsWithDevice(null, { true }).map { it.name })
    }


    // ---- 随设置进出、后端接线、key 不外漏 ----

    private fun braveReply() = HttpResponse(
        200, listOf("Content-Type" to "application/json"),
        """{"web":{"results":[{"title":"Kotlin","url":"https://kotlinlang.org/","description":"A language"}]}}""".toByteArray(),
    )

    private fun toolNamesOf(request: ModelRequest) = request.tools.map { it.name }

    @Test fun `WebSearch appears on the next request once configured and disappears when cleared without rebuilding the kernel`() = runBlocking {
        var config = WebSearchConfig()
        val http = ScriptedHttp { braveReply() }
        val rig = rig(
            http, answer("a1"), answer("a2"), answer("a3"), answer("a4"),
            tools = minToolsWithDevice(null, { false }, webSearchEnabled = { config.configured }),
            config = { config },
        )
        val rt = rig.newRoot(PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), listOf(TextPart("one"))); rig.settle(rt)
        assertTrue("没配置：模型看不到 WebSearch", "WebSearch" !in toolNamesOf(rig.model.requests[0]))
        // 选了 Brave 但没填 key 也算没配好
        config = WebSearchConfig(backend = SearchBackendKind.BRAVE)
        rt.send(ClientMsgId("u2"), listOf(TextPart("two"))); rig.settle(rt)
        assertTrue("WebSearch" !in toolNamesOf(rig.model.requests[1]))
        config = WebSearchConfig(backend = SearchBackendKind.BRAVE, braveKey = "BRAVE-KEY-1")
        rt.send(ClientMsgId("u3"), listOf(TextPart("three"))); rig.settle(rt)
        // 内核会在工具表后面再追加自己的工具（Workflow 等），所以看的是「基础表之后紧跟 WebSearch」
        assertEquals(minTools().map { it.name } + "WebSearch", toolNamesOf(rig.model.requests[2]).take(minTools().size + 1))
        config = WebSearchConfig()
        rt.send(ClientMsgId("u4"), listOf(TextPart("four"))); rig.settle(rt)
        assertTrue("清掉配置后工具消失", "WebSearch" !in toolNamesOf(rig.model.requests[3]))
    }

    @Test fun `a configured search runs and the key goes only into the request header`() = runBlocking {
        val key = "BRAVE-SECRET-KEY-8472"
        val config = WebSearchConfig(backend = SearchBackendKind.BRAVE, braveKey = key)
        val http = ScriptedHttp { braveReply() }
        val rig = rig(
            http, searchCall(), answer("done"),
            tools = minToolsWithDevice(null, { false }, webSearchEnabled = { config.configured }),
            config = { config },
        )
        val rt = rig.newRoot(PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), listOf(TextPart("go"))); rig.settle(rt)
        val request = http.requests.single()
        assertEquals(key, request.headers.single { it.first == "X-Subscription-Token" }.second)
        assertFalse("key 不进 URL", key in request.url)
        assertTrue(rig.toolText(rt, "c1"), "https://kotlinlang.org/" in rig.toolText(rt, "c1"))
        assertFalse("会话日志与附件里没有 key 原文", key in rig.everythingOnDisk())
        assertFalse("发给模型的请求里没有 key", rig.model.requests.any { key in it.allText() })
        assertFalse("设置快照的字符串形式不泄露 key", key in config.toString())
    }

    @Test fun `a failing search backend echoing the key does not leak it into the result the log or the exception`() = runBlocking {
        val key = "TAVILY-SECRET-KEY-5519"
        val config = WebSearchConfig(backend = SearchBackendKind.TAVILY, tavilyKey = key)
        // 有些后端会把收到的凭证原样写进错误正文
        val http = ScriptedHttp { throw HttpStatusException(401, emptyList(), "invalid api key: $key") }
        val rig = rig(
            http, searchCall(), answer("done"),
            tools = minToolsWithDevice(null, { false }, webSearchEnabled = { config.configured }),
            config = { config },
        )
        val rt = rig.newRoot(PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), listOf(TextPart("go"))); rig.settle(rt)
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
        assertFalse(key in rig.toolText(rt, "c1"))
        assertFalse("日志里没有 key", key in rig.everythingOnDisk())
        assertFalse("下一次发给模型的请求里没有 key", rig.model.requests.any { key in it.allText() })
        // 直接调后端：异常信息里也没有
        val backend = ConfiguredSearchBackend({ config }, http)
        val thrown = try { backend.search("q", emptyList(), emptyList(), 3); null } catch (e: Exception) { e }
        assertNotNull(thrown)
        assertFalse(generateSequence<Throwable>(thrown) { it.cause }.any { key in it.message.orEmpty() })
    }

    @Test fun `the SearXNG address follows the setting and a cleared config gives a plain error without sending anything`() = runBlocking {
        var config = WebSearchConfig(backend = SearchBackendKind.SEARXNG, searxngUrl = "http://10.0.2.2:8080")
        val http = ScriptedHttp {
            HttpResponse(200, listOf("Content-Type" to "application/json"), """{"results":[{"title":"T","url":"https://example.org/","content":"C"}]}""".toByteArray())
        }
        val backend = ConfiguredSearchBackend({ config }, http)
        assertEquals("https://example.org/", backend.search("q", emptyList(), emptyList(), 5).single().url)
        assertTrue(http.requests.single().url.startsWith("http://10.0.2.2:8080/search?"))
        config = WebSearchConfig(backend = SearchBackendKind.SEARXNG, searxngUrl = "http://192.168.1.9:8888/")
        backend.search("q", emptyList(), emptyList(), 5)
        assertTrue("换了地址，下一次搜索就发往新地址", http.requests.last().url.startsWith("http://192.168.1.9:8888/search?"))
        config = WebSearchConfig()
        val thrown = try { backend.search("q", emptyList(), emptyList(), 5); null } catch (e: IllegalStateException) { e }
        assertNotNull(thrown)
        assertEquals("清掉配置后没有多发请求", 2, http.requests.size)
    }

    @Test fun `a key changed in settings is used by the very next search and a cleared key sends nothing`() = runBlocking {
        var config = WebSearchConfig(backend = SearchBackendKind.BRAVE, braveKey = "KEY-ONE")
        val http = ScriptedHttp { braveReply() }
        val backend = ConfiguredSearchBackend({ config }, http)
        backend.search("q", emptyList(), emptyList(), 3)
        config = config.copy(braveKey = "KEY-TWO")
        backend.search("q", emptyList(), emptyList(), 3)
        assertEquals(listOf("KEY-ONE", "KEY-TWO"), http.requests.map { r -> r.headers.single { it.first == "X-Subscription-Token" }.second })
        config = config.copy(braveKey = "")
        val thrown = try { backend.search("q", emptyList(), emptyList(), 3); null } catch (e: IllegalStateException) { e }
        assertNotNull(thrown)
        assertEquals("key 清掉后不再发请求", 2, http.requests.size)
    }
}
