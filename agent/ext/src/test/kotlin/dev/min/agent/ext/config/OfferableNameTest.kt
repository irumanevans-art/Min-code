package dev.min.agent.ext.config

import dev.min.agent.ext.plugins.PluginBundle
import dev.min.agent.spi.GuestPath
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CC 2.1.299：文件 / 文件夹名带隐藏、改变方向、看起来像空白的字符，或 `<` `>`，skill / command 不再提供；`name:` 不合格则忽略。
 * 判定本身（[isOfferableName]）与三处加载器（skills、commands、插件）各测一遍。特殊字符一律用 `\uXXXX` 转义写，免得源码里藏着看不见的字符。
 */
class OfferableNameTest {
    private val fs = InMemoryFs()

    private fun load() = runBlocking { ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")) }
    private fun md(description: String = "d", body: String = "body") = "---\ndescription: $description\n---\n$body"

    /** 每一类坏字符各一个代表；名字是「前缀 + 坏字符 + 后缀」，坏字符藏在中间最难肉眼发现 */
    private val badChars = linkedMapOf(
        "bidi override U+202E" to "\u202E",
        "bidi isolate U+2067" to "\u2067",
        "left-to-right mark U+200E" to "\u200E",
        "arabic letter mark U+061C" to "\u061C",
        "zero width space U+200B" to "\u200B",
        "zero width joiner U+200D" to "\u200D",
        "word joiner U+2060" to "\u2060",
        "byte order mark U+FEFF" to "\uFEFF",
        "hangul filler U+3164" to "\u3164",
        "halfwidth hangul filler U+FFA0" to "\uFFA0",
        "mongolian vowel separator U+180E" to "\u180E",
        "variation selector U+FE0F" to "\uFE0F",
        "tag character U+E0041" to "\uDB40\uDC41",
        "no-break space U+00A0" to "\u00A0",
        "em space U+2003" to "\u2003",
        "ideographic space U+3000" to "\u3000",
        "line separator U+2028" to "\u2028",
        "paragraph separator U+2029" to "\u2029",
        "replacement character U+FFFD" to "\uFFFD",
        "braille blank U+2800" to "\u2800",
        "control U+0007" to "\u0007",
        "delete U+007F" to "\u007F",
        "private use U+E000" to "\uE000",
        "lone surrogate" to "\uD800",
        "less-than" to "<",
        "greater-than" to ">",
    )

    // ---- 判定 ----

    @Test
    fun `ordinary names are offered, including chinese, a plain space and a namespace colon`() {
        for (name in listOf(
            "deploy", "git-commit_2", "Release.v2", "发布流程", "テスト", "한글", "my skill", "frontend:component", "ns:sub:cmd",
            "café", "cafe\u0301", "नमस\u094Dत\u0947", "a".repeat(256),
        )) {
            assertTrue("应当提供：${shownName(name)}", isOfferableName(name))
        }
    }

    @Test
    fun `every hidden, direction-changing or blank-like character makes a name unoffered`() {
        for ((label, bad) in badChars) {
            for (name in listOf("a${bad}b", "${bad}ab", "ab$bad")) {
                assertFalse("$label 应当被拒绝：${shownName(name)}", isOfferableName(name))
            }
        }
    }

    @Test
    fun `empty, over-long and badly spaced names are not offered`() {
        assertFalse(isOfferableName(""))
        assertFalse(isOfferableName("a".repeat(257)))
        assertFalse(isOfferableName(" lead"))
        assertFalse(isOfferableName("trail "))
        assertFalse(isOfferableName("two  spaces"))
        assertFalse(isOfferableName("ns: cmd"))
        assertFalse(isOfferableName("ns :cmd"))
        assertFalse(isOfferableName(":cmd"))
        assertFalse(isOfferableName("cmd:"))
    }

    @Test
    fun `stacked or dangling combining marks are not offered but normal accents are`() {
        assertFalse(isOfferableName("\u0301abc"))
        assertFalse(isOfferableName("a" + "\u0301".repeat(9)))
        assertTrue(isOfferableName("a" + "\u0301".repeat(2)))
        // ı 与 ȷ 后接记号、点号字母 i 后接 U+0307：伪造点号
        assertFalse(isOfferableName("ı\u0301"))
        assertFalse(isOfferableName("i\u0307"))
        // 拉丁字母上叠泰文声调符号（非 Inherited 脚本的记号）
        assertFalse(isOfferableName("a\u0E48"))
        assertTrue(isOfferableName("ก\u0E48"))
    }

    @Test
    fun `shownName spells hidden characters out`() {
        assertEquals("a\\u{202E}b", shownName("a\u202Eb"))
        assertEquals("plain 名字", shownName("plain 名字"))
    }

    // ---- skills：目录名 ----

    @Test
    fun `a skill folder with a bad character in its name is not loaded and says why`() {
        fs.file("/workspace/.claude/skills/good/SKILL.md", md())
        fs.file("/workspace/.claude/skills/中文 name/SKILL.md", md())
        for ((index, bad) in listOf("\u202E", "\u200B", "\u3164", "<").withIndex()) {
            fs.file("/workspace/.claude/skills/bad$index${bad}x/SKILL.md", md())
        }
        val c = load()
        assertEquals(setOf("good", "中文 name"), c.skills.map { it.name }.toSet())
        for (index in 0..3) {
            assertTrue("bad$index 的诊断：${c.notes}", c.notes.any { it.contains("bad$index") && it.contains("不合格") && it.contains("改名后才会加载") })
        }
        // 诊断里的隐藏字符是转义过的，不会把坏字符原样抄进去
        assertTrue(c.notes.any { it.contains("\\u{202E}") })
        assertFalse(c.notes.any { it.contains("\u202E") })
    }

    // ---- skills：name: ----

    @Test
    fun `a bad frontmatter name is ignored and the folder name is used`() {
        for ((index, bad) in listOf("\u202E", "\u200B", "\u3164", "<").withIndex()) {
            fs.file("/workspace/.claude/skills/dir$index/SKILL.md", "---\nname: \"x${bad}y\"\ndescription: d\n---\nbody")
        }
        val c = load()
        assertEquals(setOf("dir0", "dir1", "dir2", "dir3"), c.skills.map { it.name }.toSet())
        assertEquals(4, c.notes.count { it.contains("`name:`") && it.contains("已忽略") })
    }

    @Test
    fun `a good frontmatter name still overrides the folder name, chinese and plain spaces included`() {
        fs.file("/workspace/.claude/skills/a/SKILL.md", "---\nname: 发布流程\ndescription: d\n---\nbody")
        fs.file("/workspace/.claude/skills/b/SKILL.md", "---\nname: my skill\ndescription: d\n---\nbody")
        val c = load()
        assertEquals(setOf("发布流程", "my skill"), c.skills.map { it.name }.toSet())
        assertTrue(c.notes.isEmpty())
    }

    // ---- commands ----

    @Test
    fun `a command file or folder with a bad character in its name is not loaded and says why`() {
        fs.file("/workspace/.claude/commands/ok.md", "Run ok")
        fs.file("/workspace/.claude/commands/命令 two.md", "Run two")
        fs.file("/workspace/.claude/commands/frontend/component.md", "Run component")
        fs.file("/workspace/.claude/commands/bad0\u202Ex.md", "x")
        fs.file("/workspace/.claude/commands/bad1\u200Bx.md", "x")
        fs.file("/workspace/.claude/commands/bad2\u3164x.md", "x")
        fs.file("/workspace/.claude/commands/bad3<x.md", "x")
        // 文件夹名不合格：里面的命令名 `bad4<dir:inner` 也不合格
        fs.file("/workspace/.claude/commands/bad4\u202Edir/inner.md", "x")
        val c = load()
        assertEquals(setOf("ok", "命令 two", "frontend:component"), c.commands.map { it.name }.toSet())
        for (index in 0..4) {
            assertTrue("bad$index 的诊断：${c.notes}", c.notes.any { it.contains("bad$index") && it.contains("不合格") })
        }
    }

    // ---- 插件 ----

    @Test
    fun `plugin skills and commands go through the same check`() = runBlocking {
        val plug = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", """{"name":"p"}""")
            put("/plug/skills/good/SKILL.md", md())
            put("/plug/skills/bad\u202Eone/SKILL.md", md())
            put("/plug/commands/fine.md", "Run fine")
            put("/plug/commands/bad\u3164cmd.md", "Run bad")
        }
        val cwd = GuestPath("/work")
        val home = GuestPath("/home")
        val loaded = PluginBundle.load(plug, cwd, home, SettingsLoader.load(plug, cwd, home).merged, listOf(GuestPath("/plug")))
        assertEquals(listOf("p:good"), loaded.skills.map { it.name })
        assertEquals(listOf("p:fine"), loaded.commands.map { it.name })
        assertTrue(loaded.notes.toString(), loaded.notes.any { it.contains("不合格") && it.contains("\\u{202E}") })
        assertTrue(loaded.notes.toString(), loaded.notes.any { it.contains("不合格") && it.contains("\\u{3164}") })
    }

    @Test
    fun `inline plugin commands from the manifest are checked too`() = runBlocking {
        val plug = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", "{\"name\":\"p\",\"commands\":{\"fine\":{\"content\":\"Run fine\"},\"bad\u3164cmd\":{\"content\":\"Run bad\"}}}")
        }
        val cwd = GuestPath("/work")
        val home = GuestPath("/home")
        val loaded = PluginBundle.load(plug, cwd, home, SettingsLoader.load(plug, cwd, home).merged, listOf(GuestPath("/plug")))
        assertEquals(listOf("p:fine"), loaded.commands.map { it.name })
        assertTrue(loaded.notes.toString(), loaded.notes.any { it.contains("不合格") && it.contains("\\u{3164}") })
    }
}
