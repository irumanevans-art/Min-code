package dev.min.agent.context

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.MemorySource.Nested
import dev.min.agent.spi.MemorySource.PathRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按需加载的发现：哪些文件触发哪些记忆、顺序、去重、范围、预算 */
class MemoryDiscoveryTest {
    private val fs = InMemoryFs(workspace = "/workspace")

    private fun discovery(cwd: String = "/workspace", limits: MemoryLimits = MemoryLimits()) =
        MemoryDiscovery(fs, GuestPath(cwd), GuestPath("/root"), limits)

    private fun MemoryDiscovery.attach(vararg touched: String, inContext: Set<String> = emptySet()): MemorySnapshot =
        runBlocking { attach(touched.map(::GuestPath), inContext.map(::GuestPath).toSet()) }

    private fun MemorySnapshot.order() = files.map { "${it.path} ${it.source}" }

    @Test
    fun `a file in a subtree loads every layer from the level below cwd down to its directory`() {
        fs.file("/workspace/CLAUDE.md", "根的记忆启动时已读")
        fs.file("/workspace/src/CLAUDE.md", "src")
        fs.file("/workspace/src/AGENTS.md", "src agents")
        fs.file("/workspace/src/CLAUDE.local.md", "src local")
        fs.file("/workspace/src/api/.claude/CLAUDE.md", "api dot")
        fs.file("/workspace/src/api/CLAUDE.md", "api")
        fs.file("/workspace/src/api/x.ts", "code")
        fs.file("/workspace/src/other/CLAUDE.md", "别的子树")

        val result = discovery().attach("/workspace/src/api/x.ts")

        assertEquals(
            listOf(
                "/workspace/src/CLAUDE.md $Nested",
                "/workspace/src/AGENTS.md $Nested",
                "/workspace/src/CLAUDE.local.md $Nested",
                "/workspace/src/api/CLAUDE.md $Nested",
                "/workspace/src/api/.claude/CLAUDE.md $Nested",
            ),
            result.order(),
        )
        // 只在启动时读的 cwd 层与没碰到的子树都不会出现
        assertTrue(result.files.none { it.path.value == "/workspace/CLAUDE.md" || "other" in it.path.value })
    }

    @Test
    fun `a rule with an invalid glob matches nothing for that entry, is noted once, and its other entries still work`() {
        fs.file(
            "/workspace/.claude/rules/mixed.md",
            "---\npaths:\n  - \"app/[id]/page.tsx\"\n  - \"[z-a].ts\"\n  - \"src/**/*.ts\"\n---\nMixed rule.",
        )
        fs.file("/workspace/src/a.ts", "a")
        val d = discovery()

        val first = d.attach("/workspace/src/a.ts")
        assertEquals("好的那条照常命中", listOf("/workspace/.claude/rules/mixed.md $PathRule"), first.order())
        assertEquals(1, first.notes.count { it.contains("mixed.md") && it.contains("无效") })
        assertTrue(first.notes.joinToString(), first.notes.any { it.contains("app/[id]/page.tsx") && it.contains("[z-a].ts") })

        // 同一条规则一个会话只说一次
        val second = d.attach("/workspace/src/b.ts", inContext = setOf("/workspace/.claude/rules/mixed.md"))
        assertTrue(second.notes.joinToString(), second.notes.none { it.contains("无效") })
    }

    @Test
    fun `a rule whose only glob is invalid never matches and never throws`() {
        fs.file("/workspace/.claude/rules/bad.md", "---\npaths:\n  - \"app/**/[[...slug]]/page.tsx\"\n---\nBad.")
        fs.file("/workspace/app/docs/[[...slug]]/page.tsx", "x")
        val result = discovery().attach("/workspace/app/docs/[[...slug]]/page.tsx")
        assertEquals(emptyList<String>(), result.order())
        assertEquals(1, result.notes.count { it.contains("无效") })
    }

    @Test
    fun `a layer file with the same content as another is skipped but counted as covered`() {
        fs.file("/workspace/s/CLAUDE.md", "Same text")
        fs.file("/workspace/s/AGENTS.md", "Same text")
        fs.file("/workspace/s/a.ts", "a")
        val result = discovery().attach("/workspace/s/a.ts")
        assertEquals(listOf("/workspace/s/CLAUDE.md $Nested"), result.order())
        assertEquals(listOf(GuestPath("/workspace/s/AGENTS.md")), result.covered)
    }

    @Test
    fun `reading a memory file itself does not inject it again, and its same-content sibling is covered too`() {
        fs.file("/workspace/s/CLAUDE.md", "Same text")
        fs.file("/workspace/s/AGENTS.md", "Same text")
        fs.file("/workspace/s/CLAUDE.local.md", "Mine")
        val result = discovery().attach("/workspace/s/CLAUDE.md")
        assertEquals("读过的那份不再注入，不同内容的兄弟照常", listOf("/workspace/s/CLAUDE.local.md $Nested"), result.order())
        assertEquals(listOf(GuestPath("/workspace/s/CLAUDE.md"), GuestPath("/workspace/s/AGENTS.md")), result.covered)
    }

    @Test
    fun `the imports of a memory file the model read directly are still loaded`() {
        fs.file("/workspace/s/CLAUDE.md", "See @docs.md")
        fs.file("/workspace/s/docs.md", "Docs text")
        val result = discovery().attach("/workspace/s/CLAUDE.md")
        assertEquals(listOf("/workspace/s/docs.md ${dev.min.agent.spi.MemorySource.Import}"), result.order())
        assertEquals(listOf(GuestPath("/workspace/s/CLAUDE.md")), result.covered)
    }

    @Test
    fun `a file directly in cwd loads no nested memory`() {
        fs.file("/workspace/CLAUDE.md", "根")
        fs.file("/workspace/main.kt", "code")
        assertEquals(emptyList<String>(), discovery().attach("/workspace/main.kt").order())
    }

    @Test
    fun `paths already in context are neither read nor loaded again`() {
        fs.file("/workspace/src/CLAUDE.md", "src")
        fs.file("/workspace/src/api/CLAUDE.md", "api")
        val d = discovery()

        val second = d.attach("/workspace/src/api/y.ts", inContext = setOf("/workspace/src/CLAUDE.md"))

        assertEquals(listOf("/workspace/src/api/CLAUDE.md $Nested"), second.order())
        assertTrue("已在上下文里的文件连读都不该读", "/workspace/src/CLAUDE.md" !in fs.reads)
        assertEquals(emptyList<String>(), d.attach("/workspace/src/api/z.ts", inContext = setOf("/workspace/src/CLAUDE.md", "/workspace/src/api/CLAUDE.md")).order())
    }

    @Test
    fun `files outside cwd trigger nothing`() {
        fs.file("/other/CLAUDE.md", "别处")
        fs.file("/other/sub/CLAUDE.md", "别处的子目录")
        fs.file("/workspace2/CLAUDE.md", "名字只是前缀相同")
        fs.file("/root/.claude/rules/all.md", "---\npaths: \"**\"\n---\n用户规则")
        val d = discovery()
        assertEquals(emptyList<String>(), d.attach("/other/sub/x.ts").order())
        assertEquals(emptyList<String>(), d.attach("/workspace2/x.ts").order())
        assertEquals(emptyList<String>(), d.attach("/root/.claude/settings.json").order())
    }

    @Test
    fun `a rule with paths loads only for matching files, relative to the project root`() {
        fs.file("/workspace/.claude/rules/ts.md", "---\npaths:\n  - \"src/**/*.ts\"\n---\nTS rules")
        fs.file("/workspace/.claude/rules/docs.md", "---\npaths: *.md, docs/**\n---\nDocs rules")

        val d = discovery()
        val ts = d.attach("/workspace/src/a.ts")
        assertEquals(listOf("/workspace/.claude/rules/ts.md $PathRule"), ts.order())
        assertEquals("TS rules", ts.files.single().content.trim())

        assertEquals(emptyList<String>(), d.attach("/workspace/lib/a.ts").order())
        // README.md 匹配 *.md（根下），没有 src/**/*.ts 的份
        assertEquals(listOf("/workspace/.claude/rules/docs.md $PathRule"), d.attach("/workspace/README.md").order())
        assertEquals(emptyList<String>(), d.attach("/workspace/sub/NOTES.md").order())
    }

    @Test
    fun `rules in ancestors of cwd and the user rules are matched too`() {
        // 祖先目录的规则相对它自己的根：/proj 之下的 packages/app/x.ts
        fs.file("/proj/.claude/rules/pk.md", "---\npaths: packages/**/*.ts\n---\nancestor rule")
        fs.file("/root/.claude/rules/user.md", "---\npaths: src/**\n---\nuser rule")
        fs.file("/proj/packages/app/.claude/rules/own.md", "---\npaths: \"src/*.ts\"\n---\nown rule")

        val result = discovery(cwd = "/proj/packages/app").attach("/proj/packages/app/src/x.ts")

        assertEquals(
            listOf(
                // 用户级规则的根取 cwd：src/** 相对 /proj/packages/app
                "/root/.claude/rules/user.md $PathRule",
                "/proj/.claude/rules/pk.md $PathRule",
                "/proj/packages/app/.claude/rules/own.md $PathRule",
            ),
            result.order(),
        )
    }

    @Test
    fun `a nested rules directory matches relative to its own project root`() {
        // src/api 自己的 .claude/rules：paths 相对 /workspace/src/api，不是 /workspace
        fs.file("/workspace/src/api/.claude/rules/handlers.md", "---\npaths: handlers/*.ts\n---\nhandler rule")
        fs.file("/workspace/src/api/.claude/rules/plain.md", "no paths, applies under src/api")
        fs.file("/workspace/src/api/handlers/h.ts", "code")
        fs.file("/workspace/src/api/other.ts", "code")

        val h = discovery().attach("/workspace/src/api/handlers/h.ts")
        assertEquals(
            listOf(
                // 没有 paths 的规则是子目录记忆（nested）；带 paths 的命中后是路径规则
                "/workspace/src/api/.claude/rules/plain.md $Nested",
                "/workspace/src/api/.claude/rules/handlers.md $PathRule",
            ),
            h.order(),
        )
        // 从项目根 /workspace 起算 handlers/*.ts 匹配不上，所以 other.ts 不触发 handlers 规则
        val other = discovery().attach("/workspace/src/api/other.ts")
        assertEquals(listOf("/workspace/src/api/.claude/rules/plain.md $Nested"), other.order())
    }

    @Test
    fun `nested memory comes before path rules and imports expand like at startup`() {
        fs.file("/workspace/.claude/rules/ts.md", "---\npaths: src/**\n---\nrule")
        fs.file("/workspace/src/CLAUDE.md", "see @notes.md")
        fs.file("/workspace/src/notes.md", "imported notes")

        val result = discovery().attach("/workspace/src/a.ts")

        assertEquals(
            listOf(
                "/workspace/src/notes.md ${dev.min.agent.spi.MemorySource.Import}",
                "/workspace/src/CLAUDE.md $Nested",
                "/workspace/.claude/rules/ts.md $PathRule",
            ),
            result.order(),
        )
    }

    @Test
    fun `several touched files in one batch share one result without duplicates`() {
        fs.file("/workspace/src/CLAUDE.md", "src")
        fs.file("/workspace/src/api/CLAUDE.md", "api")
        fs.file("/workspace/.claude/rules/ts.md", "---\npaths: src/**/*.ts\n---\nrule")

        val result = discovery().attach("/workspace/src/api/a.ts", "/workspace/src/api/b.ts", "/workspace/src/c.ts")

        assertEquals(
            listOf(
                "/workspace/src/CLAUDE.md $Nested",
                "/workspace/src/api/CLAUDE.md $Nested",
                "/workspace/.claude/rules/ts.md $PathRule",
            ),
            result.order(),
        )
    }

    @Test
    fun `a rule that already loaded is not loaded again`() {
        fs.file("/workspace/.claude/rules/ts.md", "---\npaths: src/**\n---\nrule")
        val d = discovery()
        assertEquals(1, d.attach("/workspace/src/a.ts").files.size)
        assertEquals(0, d.attach("/workspace/src/b.ts", inContext = setOf("/workspace/.claude/rules/ts.md")).files.size)
    }

    @Test
    fun `memory attached together is capped at half the total budget and whole files that do not fit become a one line note`() {
        val limits = MemoryLimits(fileChars = 100, totalChars = 100) // 一条事件最多 50 字符
        fs.file("/workspace/a/CLAUDE.md", "x".repeat(40))
        fs.file("/workspace/a/b/CLAUDE.md", "y".repeat(40)) // 40 + 40 > 50
        fs.file("/workspace/a/b/c/CLAUDE.md", "z".repeat(5)) // 放得下的后面的文件仍然收

        val result = discovery(limits = limits).attach("/workspace/a/b/c/f.txt")

        assertEquals(3, result.files.size)
        assertEquals("x".repeat(40), result.files[0].content)
        assertEquals(MemoryLoader.ATTACH_NOT_LOADED, result.files[1].content)
        assertEquals("z".repeat(5), result.files[2].content)
        assertTrue(result.notes.any { "/workspace/a/b/CLAUDE.md" in it })
    }

    @Test
    fun `single files are cut at the per-file limit with a notice`() {
        val result = run {
            fs.file("/workspace/a/CLAUDE.md", "x".repeat(300))
            discovery(limits = MemoryLimits(fileChars = 100, totalChars = 1_000)).attach("/workspace/a/f.txt")
        }
        assertTrue(result.files.single().content.startsWith("x".repeat(100)))
        assertTrue(result.files.single().content.contains("Truncated"))
    }

    @Test
    fun `an unreadable memory file is skipped with a note and the others still load`() {
        fs.file("/workspace/a/CLAUDE.md", "fine")
        fs.fail("/workspace/a/AGENTS.md")
        val result = discovery().attach("/workspace/a/f.txt")
        assertEquals(listOf("/workspace/a/CLAUDE.md $Nested"), result.order())
        assertTrue(result.notes.any { "AGENTS.md" in it })
    }

    @Test
    fun `cwd at the filesystem root works`() {
        fs.file("/src/CLAUDE.md", "src")
        assertEquals(listOf("/src/CLAUDE.md $Nested"), discovery(cwd = "/").attach("/src/a.ts").order())
    }
}
