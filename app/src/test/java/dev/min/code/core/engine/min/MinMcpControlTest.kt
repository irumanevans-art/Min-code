package dev.min.code.core.engine.min

import dev.min.code.core.engine.McpLoginPrompt
import dev.min.code.core.engine.McpServerCommandLine
import dev.min.code.core.engine.McpServerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MinMcpControlTest {
    @Test
    fun `kernel state words map to the view enum, unknown ones show as failed`() {
        assertEquals(McpServerState.NeedsAuth, McpServerState.of("needs-auth"))
        assertEquals(McpServerState.NotApproved, McpServerState.of("not-approved"))
        assertEquals(McpServerState.Untrusted, McpServerState.of("untrusted"))
        assertEquals(McpServerState.Failed, McpServerState.of("something-new"))
    }

    @Test
    fun `describe reads command and args or the url, without expanding variables`() {
        val json = """{"mcpServers":{"gh":{"command":"npx","args":["-y","gh-mcp","--token=${'$'}{GH_TOKEN}"]},"db":{"url":"https://db.example/mcp"},"bad":{"args":[]}}}"""
        assertEquals(McpServerCommandLine("npx -y gh-mcp --token=${'$'}{GH_TOKEN}", null), commandLineOf(json, "gh"))
        assertEquals(McpServerCommandLine(null, "https://db.example/mcp"), commandLineOf(json, "db"))
        assertNull(commandLineOf(json, "bad"))
        assertNull(commandLineOf(json, "missing"))
        assertNull(commandLineOf("not json", "gh"))
    }

    @Test
    fun `the login gate shows the prompt and holds the browser back until the person agrees`() = runBlocking {
        val gate = McpLoginGate()
        gate.begin("db")
        gate.preview("https://as.example", "as.example", "read write")
        val opened = CompletableDeferred<Unit>()
        val flow = async { gate.confirm(); opened.complete(Unit) }
        withTimeout(5_000) { while (gate.prompt.value == null) kotlinx.coroutines.delay(10) }
        assertEquals(McpLoginPrompt("db", "https://as.example", "as.example", "read write"), gate.prompt.value)
        assertTrue("没点继续之前不能放行", !opened.isCompleted)
        gate.answer(true)
        flow.await()
        assertTrue(opened.isCompleted)
        assertNull("答完卡片收起", gate.prompt.value)
    }

    @Test
    fun `declining the prompt aborts with LoginDeclined and never lets the browser open`() = runBlocking {
        val gate = McpLoginGate()
        gate.begin("db")
        gate.preview("https://as.example", "as.example", null)
        val flow = async { runCatching { gate.confirm() } }
        withTimeout(5_000) { while (gate.prompt.value == null) kotlinx.coroutines.delay(10) }
        gate.answer(false)
        val result = flow.await()
        assertTrue(result.exceptionOrNull() is LoginDeclined)
        assertNull(gate.prompt.value)
    }

    @Test
    fun `without a preview the gate refuses instead of opening a page nobody described`() = runBlocking {
        val gate = McpLoginGate()
        gate.begin("db")
        try {
            gate.confirm()
            fail("没有 preview 不该放行")
        } catch (e: LoginDeclined) {
            assertNull(gate.prompt.value)
        }
    }

    @Test
    fun `finishing a login drops a pending prompt so the next login starts clean`() = runBlocking {
        val gate = McpLoginGate()
        gate.begin("a")
        gate.preview("i", "d", null)
        val flow = async { runCatching { gate.confirm() } }
        withTimeout(5_000) { while (gate.prompt.value == null) kotlinx.coroutines.delay(10) }
        gate.finish()
        assertTrue(flow.await().exceptionOrNull() is LoginDeclined)
        assertNull(gate.prompt.value)
    }
}
