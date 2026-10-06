package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class McpResourcesSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `UI resources are hidden while direct reads remain allowed`() {
        assertTrue(isMcpAppResource(args("uri" to "ui://app")))
        assertTrue(isMcpAppResource(args("uri" to "r://a", "mimeType" to "text/html; profile=mcp-app")))
        assertTrue(isMcpAppResource(args("uriTemplate" to "ui://{id}")))
        assertFalse(isMcpAppResource(args("uri" to "file:///a", "mimeType" to "text/html")))
        val tool = McpResourceTool(true, listOf("s"), McpCaller.Unavailable)
        assertTrue(tool.parse(args("server" to "s", "uri" to "ui://app")) is ToolInput.Valid)
    }

    @Test fun `first capability snapshot freezes resource prefix across changes and reopening`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("ok")), reply(TextPart("again")))
            val deps = testDeps(model, listOf(FakeTool("Probe", READ_ONLY) { ToolResult(listOf(TextPart("ok"))) }))
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            rt.loadMcp(McpServersLoaded(emptyList()), McpToolsSnapshot(emptyList(), resourceServers = listOf("r")))
            rt.send(ClientMsgId("u1"), listOf(TextPart("hello"))); rt.awaitIdle()
            rt.stageMcp(null, McpToolsSnapshot(emptyList()))
            rt.send(ClientMsgId("u2"), listOf(TextPart("next"))); rt.awaitIdle()
            assertEquals(listOf("Probe", "ListMcpResources", "ReadMcpResource"), model.requests[0].tools.map { it.name })
            assertEquals(model.requests[0].tools, model.requests[1].tools)
            val log = store.open(rt.sessionId)!!
            val restored = fold(log.header, log.read().toList())
            assertEquals(rt.state.value, restored)
            assertEquals(availableTools(rt.state.value, deps).map { it.name }, availableTools(restored, deps).map { it.name })
        } finally { scope.cancel() }
    }

    @Test fun `mentions persist fetched content once and failures still send the message`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("ok")))
            val deps = testDeps(model, emptyList())
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            var reads = 0
            rt.mcpCaller = object : McpCaller {
                override suspend fun call(request: McpCallRequest) = ToolResult(emptyList())
                override suspend fun readResource(server: String, uri: String, artifacts: ArtifactSink, id: String): ToolResult {
                    reads++
                    return ToolResult(listOf(TextPart(if (server == "ok") "冻结正文" else "失败说明")), isError = server != "ok")
                }
            }
            val id = ClientMsgId("input")
            val parts = listOf(TextPart("读取 @ok:r://1 @bad:r://2 @ok:r://1"))
            assertTrue(rt.send(id, parts)); rt.awaitIdle(); assertFalse(rt.send(id, parts))
            assertEquals(2, reads)
            val log = store.open(rt.sessionId)!!
            val events = log.read().toList()
            val input = events.map { it.event }.filterIsInstance<InputReceived>().single()
            assertTrue(input.parts.filterIsInstance<TextPart>().any { it.text == "冻结正文" })
            assertTrue(input.parts.filterIsInstance<TextPart>().any { "读取失败" in it.text })
            assertTrue(model.requests.single().messages.toString().contains("冻结正文"))
            assertEquals(rt.state.value, fold(log.header, events))
        } finally { scope.cancel() }
    }
}
