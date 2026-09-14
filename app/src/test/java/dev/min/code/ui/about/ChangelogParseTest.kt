package dev.min.code.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParseTest {

    @Test
    fun `empty markdown yields no entries`() {
        assertTrue(parseChangelog("").isEmpty())
        assertTrue(parseChangelog("# 更新日志\n\n每次发版…").isEmpty())
    }

    @Test
    fun `dated and undated headers`() {
        val md = """
            # 更新日志

            ## 1.0.12 — 2026-09-12

            - 最新一条

            ## 1.0.2

            - 旧的一条
        """.trimIndent()
        val entries = parseChangelog(md)
        assertEquals(2, entries.size)
        assertEquals("1.0.12", entries[0].version)
        assertEquals("2026-09-12", entries[0].date)
        assertEquals("- 最新一条", entries[0].body)
        assertEquals("1.0.2", entries[1].version)
        assertNull(entries[1].date)
        assertEquals("- 旧的一条", entries[1].body)
    }

    @Test
    fun `keeps multiline body and document order`() {
        val md = """
            ## 1.0.11 — 2026-09-11

            - 第一行
            - 第二行

            空行也留着。

            ## 1.0.10 — 2026-09-10

            - 更早
        """.trimIndent()
        val entries = parseChangelog(md)
        assertEquals(2, entries.size)
        assertTrue(entries[0].body.contains("第一行"))
        assertTrue(entries[0].body.contains("空行也留着。"))
        assertEquals("1.0.10", entries[1].version)
    }

    @Test
    fun `versionKeys splits a b c`() {
        assertEquals(VersionKeys("1", "1.1"), versionKeys("1.1.4"))
        assertEquals(VersionKeys("1", "1.0"), versionKeys("1.0.12"))
        assertEquals(VersionKeys("2", "2.0"), versionKeys("2.0.0"))
        assertEquals(VersionKeys("dev", "dev"), versionKeys("dev"))
    }

    @Test
    fun `groupChangelog nests patch under minor under major`() {
        val entries = listOf(
            ChangelogEntry("1.1.4", "2026-09-14", "- a"),
            ChangelogEntry("1.1.3", "2026-09-14", "- b"),
            ChangelogEntry("1.0.12", "2026-09-12", "- c"),
            ChangelogEntry("1.0.2", null, "- d"),
        )
        val groups = groupChangelog(entries)
        assertEquals(1, groups.size)
        assertEquals("1", groups[0].major)
        assertEquals(listOf("1.1", "1.0"), groups[0].minors.map { it.minor })
        assertEquals(listOf("1.1.4", "1.1.3"), groups[0].minors[0].entries.map { it.version })
        assertEquals(listOf("1.0.12", "1.0.2"), groups[0].minors[1].entries.map { it.version })
    }

    @Test
    fun `groupChangelog keeps separate majors in document order`() {
        val entries = listOf(
            ChangelogEntry("2.0.1", null, "- x"),
            ChangelogEntry("1.1.0", null, "- y"),
        )
        val groups = groupChangelog(entries)
        assertEquals(listOf("2", "1"), groups.map { it.major })
    }
}
