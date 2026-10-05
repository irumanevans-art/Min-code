package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** /output-style 的写回：保留其它键与顺序、建文件建目录、顶层不是对象时拒绝覆盖 */
class OutputStyleCommandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writing creates the file and directory when missing`() {
        val file = tmp.root.toPath().resolve("work/.claude/settings.local.json")
        writeOutputStyleSetting(file, "Learning")
        assertEquals("""{"outputStyle":"Learning"}""", Files.readString(file).replace("\\s".toRegex(), ""))
    }

    @Test
    fun `writing preserves other keys and their order`() {
        val file = tmp.root.toPath().resolve("settings.local.json")
        Files.writeString(file, """{"permissions": {"allow": ["Bash(ls)"]}, "outputStyle": "Old", "effort": "high"}""")
        writeOutputStyleSetting(file, "Explanatory")
        val text = Files.readString(file)
        assertTrue(text, text.contains("Bash(ls)"))
        assertTrue(text, text.contains("\"outputStyle\":\"Explanatory\""))
        assertTrue("旧的值没了", !text.contains("Old"))
        assertTrue("顺序保持：permissions 在前，effort 在后", text.indexOf("permissions") < text.indexOf("outputStyle") && text.indexOf("outputStyle") < text.indexOf("effort"))
    }

    @Test
    fun `a non-object top level is refused, not overwritten`() {
        val file = tmp.root.toPath().resolve("settings.local.json")
        Files.writeString(file, "[1, 2]")
        var thrown = false
        try {
            writeOutputStyleSetting(file, "Learning")
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("原文件原样", "[1, 2]", Files.readString(file))
    }

    @Test
    fun `the listing marks the active style and includes builtins`() {
        val text = styleList(emptyMap(), active = "Default")
        assertTrue(text, text.contains("* Default"))
        assertTrue(text, text.contains("Explanatory"))
        assertTrue(text, text.contains("Learning"))
        assertTrue("没生效的不带标记", !text.contains("* Explanatory"))
    }
}
