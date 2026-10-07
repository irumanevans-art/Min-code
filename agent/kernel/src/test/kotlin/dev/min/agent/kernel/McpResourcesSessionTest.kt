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
    @Test fun `resource tools enforce server denies child restrictions and scoped permission asks`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = testDeps(ScriptedModel(), emptyList())
            val rt = SessionRuntime.create(FileSessionStore(tmp.root.toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default), deps, scope, initial=listOf(McpToolsSnapshot(emptyList(), resourceServers=listOf("db")), PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Deny, "mcp__db", "user")))))
            assertTrue(availableTools(rt.state.value, deps).isEmpty())
            var reads = 0
            val caller = object : McpCaller {
                override suspend fun call(request: McpCallRequest) = ToolResult(emptyList())
                override suspend fun readResource(server: String, uri: String, artifacts: ArtifactSink, id: String): ToolResult { reads++; return ToolResult(emptyList()) }
            }
            val read = McpResourceTool(true, listOf("allowed"), caller)
            assertEquals(listOf("allowed"), read.permissionAsk(args("server" to "allowed", "uri" to "r://x"), TestToolContext()).specifiers)
            assertTrue(read.run(args("server" to "db", "uri" to "r://x"), TestToolContext()).toList().filterIsInstance<ToolProgress.Done>().single().result.isError)
            assertEquals(0, reads)
            assertTrue(McpResourceTool(false, listOf("allowed"), caller).parse(args("server" to 123)) is ToolInput.Invalid)
            val state = rt.state.value.copy(rules=emptyList(), origin=SubagentOrigin(SessionId("p"), SessionId("p"), CallId("c"), "t", false, 1, cacheKey="t", mcpTools=listOf("mcp__other")))
            assertTrue(availableTools(state, deps).isEmpty())
            assertTrue(availableTools(state.copy(origin=state.origin!!.copy(mcpTools=null, mcpDenied=listOf("mcp__db"))), deps).isEmpty())
        } finally { scope.cancel() }
    }
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `UI resources are hidden while direct reads remain allowed`() {
        assertTrue(isMcpAppResource(args("uri" to "ui://app")))
        assertTrue(isMcpAppResource(args("uri" to "r://a", "mimeType" to "text/html; profile=mcp-app")))
        assertTrue(isMcpAppResource(args("uriTemplate" to "ui://{id}")))
        assertFalse(isMcpAppResource(args("uri" to "file:///a", "mimeType" to "text/html")))
        val tool = McpResourceTool(true, listOf("s"), McpCaller.Unavailable)
        assertTrue(tool.parse(args("server" to "s", "uri" to "ui://app")) is ToolInput.Valid)
    }

    @Test fun `late capability snapshot adds resource tools at the next turn and replays`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("ok")), reply(TextPart("again")))
            val deps = testDeps(model, listOf(FakeTool("Probe", READ_ONLY) { ToolResult(listOf(TextPart("ok"))) }))
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            rt.loadMcp(McpServersLoaded(emptyList()), McpToolsSnapshot(emptyList()))
            rt.send(ClientMsgId("u1"), listOf(TextPart("hello"))); rt.awaitIdle()
            rt.stageMcp(null, McpToolsSnapshot(emptyList(), resourceServers = listOf("r")))
            assertEquals(listOf("Probe"), availableTools(rt.state.value, deps).map { it.name })
            rt.send(ClientMsgId("u2"), listOf(TextPart("next"))); rt.awaitIdle()
            assertEquals(listOf("Probe"), model.requests[0].tools.map { it.name })
            assertEquals(listOf("Probe", "ListMcpResources", "ReadMcpResource"), model.requests[1].tools.map { it.name })
            val log = store.peek(rt.sessionId)!!
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
            assertTrue(rt.send(id, parts, scanMcpMentions = true)); rt.awaitIdle(); assertFalse(rt.send(id, parts, scanMcpMentions = true))
            assertEquals(2, reads)
            val log = store.peek(rt.sessionId)!!
            val events = log.read().toList()
            val input = events.map { it.event }.filterIsInstance<InputReceived>().single()
            assertTrue(input.parts.filterIsInstance<TextPart>().any { it.text == "冻结正文" })
            assertTrue(input.parts.filterIsInstance<TextPart>().any { "读取失败" in it.text })
            assertTrue(model.requests.single().messages.toString().contains("冻结正文"))
            assertEquals(rt.state.value, fold(log.header, events))
        } finally { scope.cancel() }
    }

    @Test fun `server text never triggers resource mentions and human mentions obey boundaries and budget`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("ok")), reply(TextPart("ok")))
            val deps = testDeps(model, emptyList())
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            val reads = mutableListOf<Pair<String, String>>()
            rt.mcpCaller = object : McpCaller {
                override suspend fun call(request: McpCallRequest) = ToolResult(emptyList())
                override suspend fun readResource(server: String, uri: String, artifacts: ArtifactSink, id: String): ToolResult {
                    reads += server to uri
                    return ToolResult(listOf(TextPart("x".repeat(100_000))))
                }
            }
            rt.send(ClientMsgId("server"), listOf(TextPart("A prompt @B:file:///secret"))); rt.awaitIdle()
            assertTrue(reads.isEmpty())
            rt.send(ClientMsgId("human"), listOf(TextPart("user@B:/skip @@B:/skip @B:r://doc.,;:)]}")), scanMcpMentions = true); rt.awaitIdle()
            assertEquals(listOf("B" to "r://doc"), reads)
            val input = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<InputReceived>().last()
            assertTrue(input.parts.filterIsInstance<TextPart>().sumOf { it.text.length } < 90_000)
            assertTrue(input.parts.toString().contains("全文已存到"))
        } finally { scope.cancel() }
    }
}
