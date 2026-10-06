package dev.min.agent.ext.mcp

import dev.min.agent.ext.skills.*
import dev.min.agent.ext.config.CommandDef
import dev.min.agent.spi.GuestPath
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class McpPromptCommandsTest {
    private val args = listOf(McpPromptArgument("title", true), McpPromptArgument("priority", true), McpPromptArgument("extra"))
    @Test fun `positional named and quoted arguments retain declared order`() {
        val positional = McpPromptCommands.arguments("\"two words\" high", args) as McpPromptArgs.Valid
        assertEquals("two words", positional.arguments["title"]!!.jsonPrimitive.content)
        assertEquals("high", positional.arguments["priority"]!!.jsonPrimitive.content)
        val mixed = McpPromptCommands.arguments("--priority=\"very high\" 'two words' --extra=", args) as McpPromptArgs.Valid
        assertEquals("two words", mixed.arguments["title"]!!.jsonPrimitive.content)
        assertEquals("very high", mixed.arguments["priority"]!!.jsonPrimitive.content)
        assertEquals("", mixed.arguments["extra"]!!.jsonPrimitive.content)
    }
    @Test fun `missing required duplicate unknown excess and unclosed quotes reject before send`() {
        for (input in listOf("title", "--title=a --title=b high", "--unknown=x", "a b c d", "\"unfinished")) {
            assertTrue(input, McpPromptCommands.arguments(input, args) is McpPromptArgs.Invalid)
        }
        val command = McpPromptCommand("mcp__s__p", "s", "p", args)
        assertTrue(SlashCommands.expand("/mcp__s__p title", emptyList(), emptyList(), prompts = listOf(command)) is SlashResult.InvalidArguments)
        assertTrue(SlashCommands.expand("/mcp__s__p title high", emptyList(), emptyList(), prompts = listOf(command)) is SlashResult.McpPrompt)
    }
    @Test fun `custom commands retain precedence over an identically named prompt`() {
        val command = CommandDef("mcp__s__p", "local", GuestPath("/command.md"), "local body", dev.min.agent.ext.config.ConfigSource.Project)
        val prompt = McpPromptCommand("mcp__s__p", "s", "p", emptyList())
        assertTrue(SlashCommands.expand("/mcp__s__p", emptyList(), listOf(command), prompts = listOf(prompt)) is SlashResult.Expanded)
    }
}
