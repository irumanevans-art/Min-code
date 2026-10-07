package dev.min.agent.cli

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*

class McpConsoleReviewTest {
    @Test fun `prompt discovery warnings identify the failed server in terminal output`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rt=SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), {0L}), LogHeader(LOG_FORMAT_VERSION, SessionId("s"),0), SessionCreated(GuestPath("/w"),TEST_MODEL,PermissionMode.Default), testDeps(ScriptedModel(),emptyList()),scope, initial=listOf(Note(NoteLevel.Warning,"MCP 服务器 bad 的 prompts/list 失败；其他服务器的 prompts 仍可使用。")))
            val bytes=ByteArrayOutputStream(); val job=ConsoleUi(rt,ApprovalAnswer.Ask,StringReader("").buffered(),PrintStream(bytes)).attach(scope)
            withTimeout(500) { while (!bytes.toString().contains("bad")) delay(5) }
            assertTrue(bytes.toString().contains("prompts/list")); job.cancel()
        } finally { scope.cancel() }
    }
    @Test fun `cancelled idle question releases stdin ownership for the next server`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), testDeps(ScriptedModel(), emptyList()), scope)
            fun question(id: String, server: String) = QuestionAsked(TurnId("mcp-idle"), QuestionId(id), CallId("mcp-idle"), listOf(UserQuestion(server, "name", emptyList(), inputType="string")), server, true)
            val root = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default, initial=listOf(question("one", "first")))
            val child = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default, initial=listOf(SubagentOrigin(root.sessionId, root.sessionId, CallId("delegate"), "test", false, 1, cacheKey="test"), question("two", "second")))
            val bytes=ByteArrayOutputStream()
            val ui=ConsoleUi(root, ApprovalAnswer.Ask, StringReader("").buffered(), PrintStream(bytes), hub).apply { mainOwnsInput=true }
            val job=ui.attach(scope)
            withTimeout(500) { while (!bytes.toString().contains("输入编号")) delay(5) }
            root.cancelMcpQuestion(QuestionId("one"))
            withTimeout(500) { while (!bytes.toString().contains("下一行输入是给服务器 second 的回答")) delay(5) }
            assertTrue(ui.consumeIdleMcpInput("/skip")); assertEquals(QuestionOutcome.Declined, child.state.value.mcpQuestions[QuestionId("two")]!!.outcome)
            job.cancel()
        } finally { scope.cancel() }
    }
    @Test fun `server terminal text strips ANSI OSC and control characters`() {
        for (text in listOf("a\u001b[2Jb", "a\u001b]0;spoof\u0007b", "a\r\u0008\u0000b")) assertEquals("ab", terminalText(text))
    }
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `idle server input is announced and skip declines without swallowing a model input`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val id = QuestionId("idle")
            val rt = SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default), testDeps(ScriptedModel(), emptyList()), scope,
                initial=listOf(QuestionAsked(TurnId("mcp-idle"), id, CallId("mcp-idle"), listOf(UserQuestion("message\u001b[2J\rspoof", "name", emptyList(), inputType="string")), "server", true)))
            val bytes = ByteArrayOutputStream()
            val ui = ConsoleUi(rt, ApprovalAnswer.Ask, StringReader("").buffered(), PrintStream(bytes)).apply { mainOwnsInput=true }
            assertFalse(ui.consumeIdleMcpInput("hello model"))
            val job = ui.attach(scope)
            withTimeout(1_000) { while (!bytes.toString().contains("输入编号")) delay(5) }
            assertTrue(bytes.toString().contains("下一行输入是给服务器 server 的回答"))
            assertFalse(bytes.toString().contains('\u001b')); assertFalse(bytes.toString().replace("\r\n", "\n").contains('\r'))
            assertTrue(ui.consumeIdleMcpInput("/skip")); assertEquals(QuestionOutcome.Declined, rt.state.value.mcpQuestions[id]!!.outcome)
            assertFalse(ui.consumeIdleMcpInput("hello model")); assertNull(rt.state.value.turn)
            job.cancel()
        } finally { scope.cancel() }
    }
    @Test fun `CLI answers child elicitation from the root Hub pending tree`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), testDeps(ScriptedModel(), emptyList()), scope)
            val root = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)
            val id = QuestionId("child-question")
            val child = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.Default, initial=listOf(
                SubagentOrigin(root.sessionId, root.sessionId, CallId("delegate"), "test", false, 1, cacheKey="test"),
                QuestionAsked(TurnId("mcp-idle"), id, CallId("mcp-idle"), listOf(UserQuestion("child server", "name", emptyList(), inputType="string")), "child-server", true)))
            val ui = ConsoleUi(root, ApprovalAnswer.Ask, StringReader("Ada\n").buffered(), PrintStream(ByteArrayOutputStream()), hub)
            val job = ui.attach(scope)
            withTimeout(500) { child.state.first { it.mcpQuestions[id]?.outcome != null } }
            assertEquals(listOf(QuestionAnswer(other="Ada")), (child.state.value.mcpQuestions[id]!!.outcome as QuestionOutcome.Answered).answers)
            job.cancel()
        } finally { scope.cancel() }
    }
}
