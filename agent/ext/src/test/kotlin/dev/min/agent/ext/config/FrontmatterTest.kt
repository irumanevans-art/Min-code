package dev.min.agent.ext.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** frontmatter 的 YAML 子集：每种写法一条，外加「不认识就记 note、不猜」与「YAML 错误整体忽略」 */
class FrontmatterTest {
    private fun parse(vararg yaml: String, body: String = "正文") =
        FrontmatterParser.parse((listOf("---") + yaml + listOf("---", body)).joinToString("\n"), "f.md")

    private fun Frontmatter.scalar(key: String) = (fields.getValue(key) as FmValue.Scalar).text

    private fun Frontmatter.strings(key: String) = (fields.getValue(key) as FmValue.Seq).items.map { (it as FmValue.Scalar).text }

    @Test
    fun `plain key value pairs and the body after the closing marker`() {
        val fm = parse("name: deploy", "description: Deploy the app", body = "\n# Title\nbody")
        assertTrue(fm.present)
        assertEquals("deploy", fm.scalar("name"))
        assertEquals("Deploy the app", fm.scalar("description"))
        assertEquals("# Title\nbody", fm.body)
        assertTrue(fm.notes.isEmpty())
    }

    @Test
    fun `frontmatter only counts when the opening marker is the first line`() {
        val fm = FrontmatterParser.parse("\n---\nname: x\n---\nbody", "f.md")
        assertFalse(fm.present)
        assertTrue(fm.fields.isEmpty())
        assertEquals("\n---\nname: x\n---\nbody", fm.body)
    }

    @Test
    fun `an unclosed frontmatter is treated as body and explained`() {
        val fm = FrontmatterParser.parse("---\nname: x\nbody", "f.md")
        assertFalse(fm.present)
        assertEquals("---\nname: x\nbody", fm.body)
        assertEquals(1, fm.notes.size)
        assertTrue(fm.notes.single().contains("没有闭合"))
    }

    @Test
    fun `BOM and CRLF are tolerated`() {
        val fm = FrontmatterParser.parse("\uFEFF---\r\nname: x\r\n---\r\nbody\r\n", "f.md")
        assertEquals("x", fm.scalar("name"))
        assertEquals("body\n", fm.body)
    }

    @Test
    fun `quoted strings keep colons hashes and process double quote escapes`() {
        val fm = parse(
            """a: "x: y # z"""",
            """b: 'it''s: fine'""",
            """c: "line1\nline2\té\"q\"" """,
            "d: plain # trailing comment",
        )
        assertEquals("x: y # z", fm.scalar("a"))
        assertEquals("it's: fine", fm.scalar("b"))
        assertEquals("line1\nline2\té\"q\"", fm.scalar("c"))
        assertEquals("plain", fm.scalar("d"))
        assertTrue(fm.notes.isEmpty())
    }

    @Test
    fun `a quoted scalar can wrap across lines and folds to one space`() {
        val fm = parse("""description: "first part""", "  second part\"", "name: x")
        assertEquals("first part second part", fm.scalar("description"))
        assertEquals("x", fm.scalar("name"))
    }

    @Test
    fun `a plain scalar continues on more indented lines`() {
        val fm = parse("description: Use this when", "  the user asks", "  for a review", "name: r")
        assertEquals("Use this when the user asks for a review", fm.scalar("description"))
        assertEquals("r", fm.scalar("name"))
    }

    @Test
    fun `flow sequences with quotes and nesting`() {
        val fm = parse("""tools: [Read, "Bash(git *)", 'Grep, x']""", "empty: []")
        assertEquals(listOf("Read", "Bash(git *)", "Grep, x"), fm.strings("tools"))
        assertTrue((fm.fields.getValue("empty") as FmValue.Seq).items.isEmpty())
    }

    @Test
    fun `flow sequences may span lines and carry comments`() {
        val fm = parse("paths: [a, # first", "  b,", "  c]")
        assertEquals(listOf("a", "b", "c"), fm.strings("paths"))
    }

    @Test
    fun `flow mapping`() {
        val fm = parse("""experimental: {cacheTtl: 1h, "x y": [1, 2]}""")
        val json = (fm.fields.getValue("experimental") as FmValue.Mapping).toJson().toString()
        assertEquals("""{"cacheTtl":"1h","x y":[1,2]}""", json)
    }

    @Test
    fun `dash lists work indented and at the key's own indent`() {
        val fm = parse("tools:", "  - Read", "  - Grep", "skills:", "- a", "- b", "name: x")
        assertEquals(listOf("Read", "Grep"), fm.strings("tools"))
        assertEquals(listOf("a", "b"), fm.strings("skills"))
        assertEquals("x", fm.scalar("name"))
    }

    @Test
    fun `nested mappings and lists of mappings keep their shape for hooks`() {
        val fm = parse(
            "hooks:",
            "  PreToolUse:",
            "    - matcher: \"Bash\"",
            "      hooks:",
            "        - type: command",
            "          command: ./check.sh",
            "          timeout: 30",
            "name: x",
        )
        val json = fm.fields.getValue("hooks").toJson()
        assertEquals(
            Json.parseToJsonElement("""{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"./check.sh","timeout":30}]}]}"""),
            json,
        )
        assertEquals("x", fm.scalar("name"))
    }

    @Test
    fun `literal block keeps newlines and clip chomping adds one`() {
        val fm = parse("description: |", "  line one", "    indented", "", "  line three", "name: x")
        assertEquals("line one\n  indented\n\nline three\n", fm.scalar("description"))
        assertEquals("x", fm.scalar("name"))
    }

    @Test
    fun `folded block joins lines and a blank line becomes a newline`() {
        val fm = parse("description: >", "  first", "  second", "", "  third", "name: x")
        assertEquals("first second\nthird\n", fm.scalar("description"))
    }

    @Test
    fun `chomping indicators strip and keep trailing newlines`() {
        val strip = parse("a: |-", "  text", "b: x").scalar("a")
        val keep = parse("a: |+", "  text", "", "b: x").scalar("a")
        assertEquals("text", strip)
        assertEquals("text\n\n", keep)
    }

    @Test
    fun `block scalar content may contain hash and colon`() {
        val fm = parse("description: |", "  # not a comment: really", "name: x")
        assertEquals("# not a comment: really\n", fm.scalar("description"))
    }

    @Test
    fun `empty value and null spellings are null`() {
        val fm = parse("a:", "b: null", "c: ~", "d: x")
        assertEquals(FmValue.Null, fm.fields["a"])
        assertEquals(FmValue.Null, fm.fields["b"])
        assertEquals(FmValue.Null, fm.fields["c"])
    }

    @Test
    fun `full line comments and blank lines are skipped`() {
        val fm = parse("# comment", "", "a: 1", "  # indented comment", "b: 2")
        assertEquals("1", fm.scalar("a"))
        assertEquals("2", fm.scalar("b"))
        assertTrue(fm.notes.isEmpty())
    }

    @Test
    fun `duplicate key keeps the last and says so`() {
        val fm = parse("a: 1", "a: 2")
        assertEquals("2", fm.scalar("a"))
        assertEquals(1, fm.notes.size)
        assertTrue(fm.notes.single().contains("重复"))
    }

    @Test
    fun `anchors aliases and tags skip only that key with a note`() {
        val fm = parse("a: &x one", "b: *x", "c: !!str 3", "d:", "  - &y z", "  - w", "name: kept")
        assertNull(fm.fields["a"])
        assertNull(fm.fields["b"])
        assertNull(fm.fields["c"])
        assertEquals("kept", fm.scalar("name"))
        assertTrue(fm.notes.any { it.contains("`a`") })
        assertTrue(fm.notes.any { it.contains("`b`") })
        assertTrue(fm.notes.any { it.contains("`c`") })
    }

    @Test
    fun `a block scalar indentation indicator is not guessed`() {
        val fm = parse("a: |2", "    text", "name: kept")
        assertNull(fm.fields["a"])
        assertEquals("kept", fm.scalar("name"))
        assertTrue(fm.notes.single().contains("缩进指示符"))
    }

    @Test
    fun `an unquoted colon in a plain value invalidates the whole frontmatter like Claude Code`() {
        val fm = parse("name: x", "description: Use when: the user asks")
        assertTrue(fm.present)
        assertTrue(fm.fields.isEmpty())
        assertEquals("正文", fm.body)
        assertTrue(fm.notes.single().contains("整个 frontmatter 被忽略"))
    }

    @Test
    fun `tabs, inconsistent indent and unclosed quotes invalidate the frontmatter`() {
        for (yaml in listOf(listOf("name: x", "\tdescription: y"), listOf("a:", "    b: 1", "  c: 2"), listOf("a: \"open", "b: 1"))) {
            val fm = parse(*yaml.toTypedArray())
            assertTrue("$yaml", fm.fields.isEmpty())
            assertEquals("$yaml", 1, fm.notes.size)
        }
    }

    @Test
    fun `the closing marker may also be three dots`() {
        val fm = FrontmatterParser.parse("---\nname: x\n...\nbody", "f.md")
        assertEquals("x", fm.scalar("name"))
        assertEquals("body", fm.body)
    }

    @Test
    fun `typed accessors coerce booleans the way the skills doc lists and report wrong types`() {
        val notes = mutableListOf<String>()
        val fm = parse(
            "a: yes", "b: Off", "c: 1", "d: 0", "e: TRUE", "f: maybe", "g: [x]",
            "n: 12", "s: abc", "list: x, y", "seq:", "  - 1",
        )
        val f = FrontmatterFields(fm.fields, "f.md", notes)
        assertEquals(true, f.bool("a"))
        assertEquals(false, f.bool("b"))
        assertEquals(true, f.bool("c"))
        assertEquals(false, f.bool("d"))
        assertEquals(true, f.bool("e"))
        assertNull(f.bool("f"))
        assertNull(f.bool("g"))
        assertEquals(12, f.int("n"))
        assertNull(f.int("s"))
        assertNull(f.string("g"))
        assertEquals(listOf("x", "y"), f.list("list", FrontmatterFields.Separators.CommaOnly))
        assertEquals(listOf("1"), f.list("seq", FrontmatterFields.Separators.CommaOnly))
        assertEquals(4, notes.size) // f 不是布尔、g 不是布尔、s 不是整数、g 不是字符串
        assertTrue(notes.all { it.contains("f.md") })
    }

    @Test
    fun `tool lists split on commas and spaces but not inside parentheses or braces`() {
        val notes = mutableListOf<String>()
        val fm = parse(
            "a: Bash(git add *) Bash(git commit *), Read",
            "b: src/**/*.{ts,tsx}, docs/a b/*.md",
        )
        val f = FrontmatterFields(fm.fields, "f.md", notes)
        assertEquals(listOf("Bash(git add *)", "Bash(git commit *)", "Read"), f.list("a", FrontmatterFields.Separators.CommaOrSpace))
        assertEquals(listOf("src/**/*.{ts,tsx}", "docs/a b/*.md"), f.list("b", FrontmatterFields.Separators.CommaOnly))
    }

    @Test
    fun `an argument hint written as an unquoted bracket is restored from the YAML list`() {
        val notes = mutableListOf<String>()
        val fm = parse("a: [issue-number]", "b: [x, y]", "c: \"[filename] [format]\"", "d: <id>")
        val f = FrontmatterFields(fm.fields, "f.md", notes)
        assertEquals("[issue-number]", f.hint("a"))
        assertEquals("[x, y]", f.hint("b"))
        assertEquals("[filename] [format]", f.hint("c"))
        assertEquals("<id>", f.hint("d"))
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `toJson infers booleans and numbers only for unquoted scalars`() {
        val fm = parse("a: true", "b: 5", "c: 1.5", "d: \"true\"", "e: |", "  7")
        assertEquals("""{"a":true,"b":5,"c":1.5,"d":"true","e":"7\n"}""", FmValue.Mapping(fm.fields).toJson().toString())
    }
}
