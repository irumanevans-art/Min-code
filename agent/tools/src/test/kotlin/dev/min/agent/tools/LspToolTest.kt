package dev.min.agent.tools

import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LspToolTest {
    @Test fun `official schema requires all coordinates and accepts relative file path`() {
        assertTrue(LspTool.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"operation":"hover","filePath":"a.t","line":0,"character":1}""").jsonObject) is ToolInput.Invalid)
        val ctx = TestToolContext()
        val input = LspTool.parsed("operation" to "workspaceSymbol", "filePath" to "a.t", "line" to 1, "character" to 1, "query" to "name")
        val ask = LspTool.permissionAsk(input, ctx)
        assertEquals("LSP", ask.tool)
        assertEquals(GuestPath("${ctx.cwd.value}/a.t"), ask.paths.single().path)
        assertEquals(AccessKind.Read, ask.paths.single().kind)
    }
    @Test fun `unavailable port returns error`() = runTest {
        assertTrue(LspTool.call(TestToolContext(), "operation" to "hover", "filePath" to "/work/a.t", "line" to 1, "character" to 1).isError)
    }
}
