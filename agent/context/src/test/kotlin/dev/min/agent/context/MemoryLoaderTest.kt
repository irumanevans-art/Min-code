package dev.min.agent.context

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.MemorySource.Import
import dev.min.agent.spi.MemorySource.Local
import dev.min.agent.spi.MemorySource.Project
import dev.min.agent.spi.MemorySource.Rule
import dev.min.agent.spi.MemorySource.User
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆文件的发现、顺序、导入与上限。期望顺序逐条对照 CC 官方文档（code.claude.com/docs/en/memory）：
 * 根 → cwd、同层 CLAUDE.local.md 最后、用户规则先于项目规则、被导入的文件排在导入它的文件之前。
 */
class MemoryLoaderTest {
    private val fs = InMemoryFs(workspace = "/workspace")

    private fun load(cwd: String = "/workspace", limits: MemoryLimits = MemoryLimits()) =
        runBlocking { MemoryLoader.load(fs, GuestPath(cwd), GuestPath("/root"), limits) }

    /** 加载结果压成「路径 来源」的列表，比对顺序一眼能看出错在哪 */
    private fun MemorySnapshot.order() = files.map { "${it.path} ${it.source}" }

    private fun MemorySnapshot.content(path: String) = files.single { it.path.value == path }.content

    private fun line(path: String, source: MemorySource) = "$path $source"

    @Test
    fun `files load from the root down to cwd and each level ends with its local file`() {
        // 故意乱序写入：顺序只能来自加载器
        fs.file("/workspace/app/CLAUDE.local.md", "app local")
        fs.file("/workspace/app/AGENTS.md", "app agents")
        fs.file("/workspace/app/.claude/CLAUDE.md", "app dot")
        fs.file("/workspace/app/CLAUDE.md", "app")
        fs.file("/workspace/CLAUDE.local.md", "ws local")
        fs.file("/workspace/CLAUDE.md", "ws")
        fs.file("/CLAUDE.md", "root")
        fs.file("/root/.claude/CLAUDE.md", "user")
        // cwd 之下的子目录按需加载，启动时不读
        fs.file("/workspace/app/sub/CLAUDE.md", "deeper")

        val memory = load(cwd = "/workspace/app")

        assertEquals(
            listOf(
                line("/root/.claude/CLAUDE.md", User),
                line("/CLAUDE.md", Project),
                line("/workspace/CLAUDE.md", Project),
                line("/workspace/CLAUDE.local.md", Local),
                line("/workspace/app/CLAUDE.md", Project),
                line("/workspace/app/.claude/CLAUDE.md", Project),
                line("/workspace/app/AGENTS.md", Project),
                line("/workspace/app/CLAUDE.local.md", Local),
            ),
            memory.order(),
        )
        assertEquals("app agents", memory.content("/workspace/app/AGENTS.md"))
        assertEquals(emptyList<String>(), memory.notes)
    }

    @Test
    fun `nothing to load gives an empty snapshot`() {
        fs.file("/workspace/README.md", "not memory")
        assertEquals(MemorySnapshot(emptyList(), emptyList()), load())
    }

    @Test
    fun `when home is an ancestor of cwd the user file is not loaded twice`() {
        fs.file("/root/.claude/CLAUDE.md", "user")
        fs.file("/root/.claude/rules/u.md", "user rule")
        fs.file("/root/proj/CLAUDE.md", "proj")
        val memory = load(cwd = "/root/proj")
        assertEquals(
            listOf(line("/root/.claude/CLAUDE.md", User), line("/root/.claude/rules/u.md", Rule), line("/root/proj/CLAUDE.md", Project)),
            memory.order(),
        )
        // 按路径去重，不该走到「内容相同」那一步
        assertEquals(emptyList<String>(), memory.notes)
    }

    @Test
    fun `rules without paths load recursively by name and rules with paths are skipped with a note`() {
        fs.file("/root/.claude/rules/mine.md", "user rule")
        fs.file("/workspace/.claude/rules/b.md", "b")
        fs.file("/workspace/.claude/rules/a.md", "---\ndescription: ignored\n---\na body")
        fs.file("/workspace/.claude/rules/sub/c.md", "c")
        fs.file("/workspace/.claude/rules/scoped.md", "---\npaths:\n  - \"src/api/**/*.ts\"\n---\napi only")
        fs.file("/workspace/.claude/rules/inline.md", "---\npaths: src/**\n---\nsrc only")
        fs.file("/workspace/.claude/rules/notes.txt", "not a rule")
        fs.file("/workspace/AGENTS.md", "agents")
        fs.file("/workspace/CLAUDE.local.md", "local")

        val memory = load()

        assertEquals(
            listOf(
                line("/root/.claude/rules/mine.md", Rule),
                line("/workspace/AGENTS.md", Project),
                line("/workspace/.claude/rules/a.md", Rule),
                line("/workspace/.claude/rules/b.md", Rule),
                line("/workspace/.claude/rules/sub/c.md", Rule),
                line("/workspace/CLAUDE.local.md", Local),
            ),
            memory.order(),
        )
        // frontmatter 不进上下文
        assertEquals("a body", memory.content("/workspace/.claude/rules/a.md"))
        assertEquals(
            listOf(
                "跳过规则 /workspace/.claude/rules/inline.md：带 paths: 的规则要在读到匹配文件时才加载，会话创建时不读",
                "跳过规则 /workspace/.claude/rules/scoped.md：带 paths: 的规则要在读到匹配文件时才加载，会话创建时不读",
            ),
            memory.notes,
        )
    }

    @Test
    fun `symlinks inside rules follow files in scope and never directories`() {
        fs.file("/workspace/shared/inside.md", "inside")
        fs.link("/workspace/.claude/rules/inside.md", "/workspace/shared/inside.md")
        fs.file("/opt/team/outside.md", "outside")
        fs.link("/workspace/.claude/rules/outside.md", "/opt/team/outside.md")
        fs.file("/opt/team/dir/x.md", "x")
        fs.link("/workspace/.claude/rules/dir", "/opt/team/dir")

        val memory = load()

        assertEquals(listOf(line("/workspace/.claude/rules/inside.md", Rule)), memory.order())
        assertEquals(
            listOf(
                "跳过 /workspace/.claude/rules/dir：规则目录里的符号链接目录暂不跟随",
                "跳过规则 /workspace/.claude/rules/outside.md：链接到工作区或 ~/.claude 之外",
            ),
            memory.notes,
        )
    }

    @Test
    fun `imports resolve against the importing file and load before it`() {
        fs.file("/workspace/CLAUDE.md", "Read @docs/guide.md first.")
        fs.file("/workspace/docs/guide.md", "Style: @style.md")
        fs.file("/workspace/docs/style.md", "tabs")
        // 相对 cwd 解析就会错拿这个
        fs.file("/workspace/style.md", "wrong")

        val memory = load()

        assertEquals(
            listOf(line("/workspace/docs/style.md", Import), line("/workspace/docs/guide.md", Import), line("/workspace/CLAUDE.md", Project)),
            memory.order(),
        )
        assertEquals("Read @docs/guide.md first.", memory.content("/workspace/CLAUDE.md"))
    }

    @Test
    fun `home imports load only from the claude directory`() {
        fs.file("/root/.claude/mine.md", "mine")
        fs.file("/root/notes.md", "notes")
        fs.file("/workspace/CLAUDE.md", "@~/.claude/mine.md @~/notes.md")

        val memory = load()

        assertEquals(listOf(line("/root/.claude/mine.md", Import), line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals(listOf("跳过导入 /root/notes.md（来自 /workspace/CLAUDE.md）：只导入工作区或 ~/.claude 下的文件"), memory.notes)
    }

    @Test
    fun `imports outside the workspace are skipped with a note`() {
        fs.file("/etc/hosts", "127.0.0.1")
        fs.file("/outside.md", "nope")
        fs.file("/workspace/CLAUDE.md", "@/etc/hosts and @../outside.md")

        val memory = load()

        assertEquals(listOf(line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals(
            listOf(
                "跳过导入 /etc/hosts（来自 /workspace/CLAUDE.md）：只导入工作区或 ~/.claude 下的文件",
                "跳过导入 /outside.md（来自 /workspace/CLAUDE.md）：只导入工作区或 ~/.claude 下的文件",
            ),
            memory.notes,
        )
        assertTrue("越界的文件连读都不该读：${fs.reads}", "/etc/hosts" !in fs.reads)
    }

    @Test
    fun `imports stop after four hops`() {
        fs.file("/workspace/CLAUDE.md", "@h1.md")
        (1..4).forEach { fs.file("/workspace/h$it.md", "@h${it + 1}.md") }
        fs.file("/workspace/h5.md", "too deep")

        val memory = load()

        assertEquals(
            listOf("h4", "h3", "h2", "h1").map { line("/workspace/$it.md", Import) } + line("/workspace/CLAUDE.md", Project),
            memory.order(),
        )
        assertEquals(listOf("跳过导入 /workspace/h5.md（来自 /workspace/h4.md）：导入最多 4 层"), memory.notes)
    }

    @Test
    fun `import cycles load each file once`() {
        fs.file("/workspace/CLAUDE.md", "@a.md")
        fs.file("/workspace/a.md", "a @b.md")
        fs.file("/workspace/b.md", "b @a.md @CLAUDE.md")

        val memory = load()

        assertEquals(
            listOf(line("/workspace/b.md", Import), line("/workspace/a.md", Import), line("/workspace/CLAUDE.md", Project)),
            memory.order(),
        )
        assertEquals(emptyList<String>(), memory.notes)
    }

    @Test
    fun `an imported AGENTS md is not read again at its own level`() {
        fs.file("/workspace/CLAUDE.md", "@AGENTS.md\n\n## Claude Code\nUse plan mode.")
        fs.file("/workspace/AGENTS.md", "shared")

        val memory = load()

        assertEquals(listOf(line("/workspace/AGENTS.md", Import), line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals(emptyList<String>(), memory.notes)
    }

    @Test
    fun `imports inside code are not followed and missing imports are noted`() {
        fs.file("/workspace/CLAUDE.md", "Mention `cat @secret.md` only.\n```\n@fenced.md\n```\nBut load @missing.md")
        fs.file("/workspace/secret.md", "s")
        fs.file("/workspace/fenced.md", "f")

        val memory = load()

        assertEquals(listOf(line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals(listOf("导入的文件不存在或不是普通文件：/workspace/missing.md（来自 /workspace/CLAUDE.md）"), memory.notes)
    }

    @Test
    fun `a CLAUDE md symlinked to AGENTS md is loaded once`() {
        fs.file("/workspace/AGENTS.md", "one copy")
        fs.link("/workspace/CLAUDE.md", "/workspace/AGENTS.md")

        val memory = load()

        assertEquals(listOf(line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals(listOf("跳过 /workspace/AGENTS.md：内容与 /workspace/CLAUDE.md 相同"), memory.notes)
    }

    @Test
    fun `a file over the per-file limit is cut and says so`() {
        fs.file("/workspace/CLAUDE.md", "0123456789ABCDEFGHIJ-tail")

        val memory = load(limits = MemoryLimits(fileChars = 20, totalChars = 1_000))

        assertEquals(
            "0123456789ABCDEFGHIJ\n\n[Truncated: this file is longer than 20 characters. Read the file for the rest.]",
            memory.content("/workspace/CLAUDE.md"),
        )
        assertEquals(listOf("/workspace/CLAUDE.md 超过 20 字符，已截断"), memory.notes)
    }

    @Test
    fun `the total limit cuts the file that crosses it and lists the rest as not loaded`() {
        fs.file("/root/.claude/CLAUDE.md", "u".repeat(20))
        fs.file("/workspace/CLAUDE.md", "p".repeat(20))
        fs.file("/workspace/CLAUDE.local.md", "l @later.md")
        fs.file("/workspace/later.md", "never read")

        val memory = load(limits = MemoryLimits(fileChars = 100, totalChars = 30))

        assertEquals("u".repeat(20), memory.content("/root/.claude/CLAUDE.md"))
        assertEquals(
            "p".repeat(10) + "\n\n[Truncated: memory files are limited to 30 characters in total. Read the file for the rest.]",
            memory.content("/workspace/CLAUDE.md"),
        )
        assertEquals(
            "[Not loaded: memory files are limited to 30 characters in total. Read the file if you need it.]",
            memory.content("/workspace/CLAUDE.local.md"),
        )
        // 预算用完后，未加载文件里的导入不跟
        assertEquals(3, memory.files.size)
        assertTrue("/workspace/later.md" !in fs.reads)
        assertEquals(
            listOf(
                "记忆文件合计超过 30 字符，/workspace/CLAUDE.md 已截断",
                "记忆文件合计超过 30 字符，/workspace/CLAUDE.local.md 未加载",
            ),
            memory.notes,
        )
    }

    @Test
    fun `cutting never splits a surrogate pair`() {
        fs.file("/workspace/CLAUDE.md", "abc😀def")
        val memory = load(limits = MemoryLimits(fileChars = 4, totalChars = 1_000))
        assertTrue(memory.content("/workspace/CLAUDE.md").startsWith("abc\n\n[Truncated"))
    }

    @Test
    fun `unreadable and binary files are skipped with a note and the rest still loads`() {
        fs.fail("/workspace/CLAUDE.md")
        fs.bytes("/workspace/AGENTS.md", byteArrayOf(0x50, 0x4b, 0x00, 0x03))
        fs.file("/workspace/CLAUDE.local.md", "still here")

        val memory = load()

        assertEquals(listOf(line("/workspace/CLAUDE.local.md", Local)), memory.order())
        assertEquals(
            listOf("读取 /workspace/CLAUDE.md 失败：权限不足", "跳过 /workspace/AGENTS.md：看起来不是文本文件"),
            memory.notes,
        )
    }

    @Test
    fun `byte order marks and CRLF are normalized and blank files are skipped`() {
        fs.bytes("/workspace/CLAUDE.md", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\r\nb".toByteArray())
        fs.file("/workspace/AGENTS.md", "  \n\n")

        val memory = load()

        assertEquals(listOf(line("/workspace/CLAUDE.md", Project)), memory.order())
        assertEquals("a\nb", memory.content("/workspace/CLAUDE.md"))
    }
}
