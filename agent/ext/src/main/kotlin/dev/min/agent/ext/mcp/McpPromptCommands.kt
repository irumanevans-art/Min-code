package dev.min.agent.ext.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class McpPromptArgument(val name: String, val required: Boolean = false)
data class McpPromptCommand(val command: String, val server: String, val prompt: String, val arguments: List<McpPromptArgument>)

sealed interface McpPromptArgs {
    data class Valid(val arguments: JsonObject) : McpPromptArgs
    data class Invalid(val message: String) : McpPromptArgs
}

object McpPromptCommands {
    fun arguments(raw: String, declaration: List<McpPromptArgument>): McpPromptArgs {
        val tokens = tokenize(raw) ?: return McpPromptArgs.Invalid("参数引号没有闭合。")
        val values = linkedMapOf<String, JsonPrimitive>()
        val positional = mutableListOf<String>()
        for (token in tokens) {
            if (!token.startsWith("--")) { positional += token; continue }
            val name = token.substring(2).substringBefore('=')
            if ('=' !in token || declaration.none { it.name == name }) return McpPromptArgs.Invalid("未知参数或缺少 =：$name")
            if (name in values) return McpPromptArgs.Invalid("参数重复：$name")
            values[name] = JsonPrimitive(token.substringAfter('='))
        }
        val remaining = declaration.filter { it.name !in values }
        if (positional.size > remaining.size) return McpPromptArgs.Invalid("位置参数太多。")
        remaining.zip(positional).forEach { (arg, value) -> values[arg.name] = JsonPrimitive(value) }
        declaration.firstOrNull { it.required && it.name !in values }?.let { return McpPromptArgs.Invalid("缺少必填参数：${it.name}") }
        return McpPromptArgs.Valid(JsonObject(values))
    }

    private fun tokenize(raw: String): List<String>? {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        var escape = false
        for ((index, char) in raw.withIndex()) {
            when {
                escape -> { token.append(char); escape = false; started = true }
                char == '\\' && raw.getOrNull(index + 1) in listOf('"', '\'', '\\') -> { escape = true; started = true }
                quote != null -> if (char == quote) quote = null else token.append(char)
                char == '"' || char == '\'' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) { tokens += token.toString(); token.clear(); started = false }
                else -> { token.append(char); started = true }
            }
        }
        if (quote != null || escape) return null
        if (started) tokens += token.toString()
        return tokens
    }
}
