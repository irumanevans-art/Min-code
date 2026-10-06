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
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `idle server input is announced and skip declines without swallowing a model input`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val id = QuestionId("idle")
            val rt = SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default), testDeps(ScriptedModel(), emptyList()), scope,
                initial=listOf(QuestionAsked(TurnId("mcp-idle"), id, CallId("mcp-idle"), listOf(UserQuestion("message\u001b[2J\rspoof", "name", emptyList(), inputType="string")), "server", true)))
            val bytes = ByteArrayOutputStream()
            val ui = ConsoleUi(rt, ApprovalAnswer.Ask, StringReader("").buffered(), PrintStream(bytes)).apply { mainOwnsInput=true }
            val job = ui.attach(scope)
            withTimeout(1_000) { while (bytes.size() == 0) delay(5) }
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
