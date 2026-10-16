package dev.min.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 400 原文 → 被拒工具。原文取自调研：cc-errors.md「Tool input schema is invalid」、
 * koog 集成测试注释、hermes-agent 对 Codex 端点的断言。
 */
class ToolSchemaRejectionTest {
    @Test
    fun `Anthropic names the tool by index in tools-N-custom-input_schema`() {
        val text = "tools.3.custom.input_schema: JSON schema is invalid. It must match JSON Schema draft 2020-12"
        assertEquals(listOf(RejectedToolRef.Index(3)), anthropicRejectedTools(text))
        val keys = "tools.1.custom.input_schema.properties: Property keys should match pattern '^[a-zA-Z0-9_.-]{1,64}\$'"
        assertEquals(listOf(RejectedToolRef.Index(1)), anthropicRejectedTools(keys))
    }

    @Test
    fun `Anthropic keeps first-seen order and drops duplicate indexes`() {
        val text = "tools.0.custom.input_schema: JSON schema is invalid; tools.2.custom.input_schema: JSON schema is invalid; tools.0.custom.input_schema: again"
        assertEquals(listOf(RejectedToolRef.Index(0), RejectedToolRef.Index(2)), anthropicRejectedTools(text))
    }

    @Test
    fun `Anthropic does not treat tools-N-custom-name as a schema rejection`() {
        assertTrue(anthropicRejectedTools("tools.0.custom.name: String should have at most 64 characters").isEmpty())
        assertTrue(anthropicRejectedTools("messages.0.content: Input should be a valid list").isEmpty())
    }

    @Test
    fun `OpenAI names the function in Invalid schema for function`() {
        val text = "Invalid schema for function 'memory': schema must have type 'object' and not have 'oneOf'/'anyOf'/'allOf'/'enum'/'not' at the top level"
        assertEquals(listOf(RejectedToolRef.Name("memory")), openAiRejectedTools(text))
    }

    @Test
    fun `OpenAI keeps first-seen order and drops duplicate names`() {
        val text = "Invalid schema for function 'mcp__a__x': bad; Invalid schema for function 'mcp__b__y': also; Invalid schema for function 'mcp__a__x': again"
        assertEquals(listOf(RejectedToolRef.Name("mcp__a__x"), RejectedToolRef.Name("mcp__b__y")), openAiRejectedTools(text))
    }

    @Test
    fun `OpenAI ignores other 400 wording`() {
        assertTrue(openAiRejectedTools("Invalid request: missing model").isEmpty())
        assertTrue(openAiRejectedTools("""Invalid schema for function "quoted": not the confirmed form""").isEmpty())
    }
}
