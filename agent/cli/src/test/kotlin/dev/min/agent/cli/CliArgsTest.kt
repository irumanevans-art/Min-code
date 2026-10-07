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
}
