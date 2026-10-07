package dev.min.agent.cli

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/** 桌面 CLI 新参数的解析：--max-turns（含 CLAUDE_CODE_MAX_TURNS 回退与启动校验）、--json-schema、--fork-session、--continue */
class CliArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun parse(vararg argv: String) = parseArgs(arrayOf(*argv), Path.of("/w"))

    // ---- --max-turns ----

    @Test fun `max-turns accepts a positive integer`() {
        assertEquals(3, parse("--model", "m", "--max-turns", "3").maxTurns)
    }

    @Test fun `max-turns rejects zero and garbage with usage`() {
        assertThrows(UsageException::class.java) { parse("--model", "m", "--max-turns", "0") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "--max-turns", "abc") }
    }

    @Test fun `the flag wins over CLAUDE_CODE_MAX_TURNS and a bad env value is only checked when used`() {
        assertEquals(4, resolveMaxTurns(4) { "not-a-number" })
        assertEquals(null, resolveMaxTurns(null) { null })
    }

    @Test fun `CLAUDE_CODE_MAX_TURNS is used when the flag is absent and must be a positive integer`() {
        assertEquals(7, resolveMaxTurns(null) { "7" })
        assertEquals(7, resolveMaxTurns(null) { " 7 " })
        assertThrows(UsageException::class.java) { resolveMaxTurns(null) { "0" } }
        assertThrows(UsageException::class.java) { resolveMaxTurns(null) { "3.5" } }
        assertThrows(UsageException::class.java) { resolveMaxTurns(null) { "" } }
    }

    // ---- --json-schema ----

    @Test fun `json-schema takes inline JSON and at-file`() {
        val schema = """{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}"""
        assertEquals(
            dev.min.agent.protocol.AgentJson.parseToJsonElement(schema).jsonObject,
            parse("--model", "m", "-p", "hi", "--json-schema", schema).jsonSchema,
        )
        val file = tmp.newFile("schema.json").toPath()
        java.nio.file.Files.writeString(file, schema)
        assertEquals(
            parse("--model", "m", "-p", "hi", "--json-schema", "@${file}").jsonSchema,
            parse("--model", "m", "-p", "hi", "--json-schema", schema).jsonSchema,
        )
    }

    @Test fun `json-schema only works in print mode and must be a JSON object`() {
        assertThrows(UsageException::class.java) { parse("--model", "m", "--json-schema", "{}") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--json-schema", "[1]") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--json-schema", "{oops") }
    }

    // ---- --fork-session / --continue ----

    @Test fun `fork-session needs resume or continue and cannot combine both`() {
        assertTrue(parse("--model", "m", "--resume", "s1", "--fork-session").forkSession)
        assertTrue(parse("--model", "m", "--continue", "--fork-session").forkSession)
        assertTrue(parse("--model", "m", "--continue").continueMostRecent)
        assertThrows(UsageException::class.java) { parse("--model", "m", "--fork-session") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "--resume", "s1", "--continue") }
        assertFalse(parse("--model", "m").forkSession)
        assertFalse(parse("--model", "m").continueMostRecent)
    }

    @Test fun `the structured-output instruction is appended after the user prompt, not into a system prompt`() {
        val prompt = structuredOutputPrompt("提取名字")
        assertTrue(prompt.startsWith("提取名字"))
        assertTrue("要求里要点名工具与一次交卷", prompt.contains("StructuredOutput") && prompt.contains("只调用一次"))
    }

    // ---- --name / -n ----

    @Test fun `name accepts a display name via both spellings and normalizes it`() {
        assertEquals("auth refactor", parse("--model", "m", "--name", " auth\u0000refactor ").name)
        assertEquals("auth", parse("--model", "m", "-n", "auth").name)
        assertEquals(null, parse("--model", "m").name)
    }

    @Test fun `name rejects empty-after-normalize values`() {
        assertThrows(UsageException::class.java) { parse("--model", "m", "--name", "  \n") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-n", "\u0000") }
    }

    @Test fun `name cannot ride along with resume or continue`() {
        assertThrows(UsageException::class.java) { parse("--model", "m", "--name", "x", "--resume", "s1") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-n", "x", "--continue") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "--name", "x", "-n", "y") }
    }

    // ---- --output-format / --json / --print（CC 的无头参数面）----

    @Test fun `print is the long form of -p and both given is an error`() {
        assertEquals("CC", parse("--model", "m", "--print", "CC").prompt)
        assertEquals("CC", parse("--model", "m", "-p", "CC").prompt)
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "a", "--print", "b") }
    }

    @Test fun `output-format is a first-class flag and json is its alias`() {
        assertEquals(OutputFormat.Json, parse("--model", "m", "-p", "hi", "--output-format", "json").outputFormat)
        assertEquals(OutputFormat.StreamJson, parse("--model", "m", "-p", "hi", "--output-format", "stream-json").outputFormat)
        assertEquals(OutputFormat.Text, parse("--model", "m", "-p", "hi", "--output-format", "text").outputFormat)
        assertEquals(OutputFormat.Text, parse("--model", "m", "-p", "hi").outputFormat)
        // --json 是 --output-format json 的别名；冲突才报错
        assertEquals(OutputFormat.Json, parse("--model", "m", "-p", "hi", "--json").outputFormat)
        assertTrue(parse("--model", "m", "-p", "hi", "--json").json)
        assertEquals(OutputFormat.Json, parse("--model", "m", "-p", "hi", "--json", "--output-format", "json").outputFormat)
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--json", "--output-format", "text") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--output-format", "xml") }
        // 输出格式是给 -p 的（CC :110「for print mode」）
        assertThrows(UsageException::class.java) { parse("--model", "m", "--json") }
    }

    @Test fun `unknown arguments are rejected instead of being silently swallowed`() {
        // 带值的未知参数此前会被静默吞掉（样本 01/03 暴露的问题根源）
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--output-format") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--nope", "value") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--nope") }
    }

    @Test fun `partial-messages and forward-subagent-text need print mode and stream-json`() {
        val base = arrayOf("--model", "m", "-p", "hi", "--output-format", "stream-json")
        assertTrue(parse(*base, "--include-partial-messages").includePartialMessages)
        assertTrue(parse(*base, "--forward-subagent-text").forwardSubagentText)
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--include-partial-messages") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "-p", "hi", "--output-format", "json", "--forward-subagent-text") }
        assertThrows(UsageException::class.java) { parse("--model", "m", "--include-partial-messages") }
    }
}
