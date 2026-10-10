package dev.min.agent.tools

import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.ToolInput
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.args
import dev.min.agent.testkit.call
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EndConversation：只声明结束会话的效果，停下回合与锁住会话由内核做（见内核的 EndConversationSessionTest） */
class EndConversationToolTest {
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    @Test
    fun `name, no input, read-only, and the description keeps the rare-use rule`() {
        assertEquals("EndConversation", EndConversationTool.name)
        assertTrue(EndConversationTool.traits.readOnly)
        // 永远不过权限、不跑 PreToolUse hook（CC：deny 规则对它无效）
        assertTrue(EndConversationTool.traits.exemptFromPolicy)
        assertEquals(emptyList<String>(), (EndConversationTool.inputSchema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList<String>())
        val text = EndConversationTool.description(profile)
        assertTrue(text, text.contains("辱骂"))
        assertTrue(text, text.contains("演示"))
    }

    @Test
    fun `an empty input is valid, and extra fields are ignored`() {
        assertTrue(EndConversationTool.parse(JsonObject(emptyMap())) is ToolInput.Valid<*>)
        assertTrue(EndConversationTool.parse(args("reason" to "x")) is ToolInput.Valid<*>)
    }

    @Test
    fun `run declares the conversation end as a session effect and says so to the model`() = runBlocking {
        val result = EndConversationTool.call(TestToolContext())
        assertFalse(result.isError)
        assertEquals(listOf<SessionEffect>(SessionEffect.ConversationEnd), result.effects)
        assertTrue(result.text, result.text.contains("结束"))
    }
}
