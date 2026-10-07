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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader

class McpConsoleEofTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `EOF cancels external form but explicit blank declines it without starting a turn`() = runBlocking {
        for (input in listOf("", "\n")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
                val id = QuestionId("q")
                val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0),
                    SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), testDeps(ScriptedModel(), emptyList()), scope,
                    initial=listOf(QuestionAsked(TurnId("mcp-idle"), id, CallId("mcp-idle"), listOf(UserQuestion("MCP server s", "name", emptyList(), inputType="string")), "s", true)))
                ConsoleUi(rt, ApprovalAnswer.Ask, StringReader(input).buffered(), PrintStream(ByteArrayOutputStream())).attach(scope)
                withTimeout(2_000) { rt.state.first { it.mcpQuestions[id]?.outcome != null } }
                assertEquals(input.isEmpty(), rt.state.value.mcpQuestions[id]!!.cancelled)
                assertNull(rt.state.value.turn)
                val log = store.open(rt.sessionId)!!
                assertEquals(rt.state.value, fold(log.header, log.read().toList()))
            } finally { scope.cancel() }
        }
    }
}
