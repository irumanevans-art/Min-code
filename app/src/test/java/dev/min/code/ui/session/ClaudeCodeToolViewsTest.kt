package dev.min.code.ui.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用的类型化渲染。
 *
 * 这一层的价值全在"不展开也知道它在干什么、展开能看懂改了什么"，所以摘要文案和
 * diff 合成都要钉死。字段名取自 CLI 自带的 `sdk-tools.d.ts`
 * （`@anthropic-ai/claude-code` npm 包内），不是照直觉写的 —— 下面每个夹具都按那份
 * 声明构造，升级 CLI 时拿它复核。
 *
 * 摘要里的中文来自 [testTranscriptLabels]（values-zh 的现值），资源或文案改动时
 * 两边要一起对。
 */
class ClaudeCodeToolViewsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val labels = testTranscriptLabels()

    private fun input(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    // region unified diff 合成

    /**
     * Edit 给的是"整段换整段"，所以整块删 + 整块增，而不是逐行 LCS ——
     * 逐行对齐会把一次有意的重写拆成一堆零碎增删，反而看不出发生了什么。
     */
    @Test
    fun `edit becomes a removal block followed by an addition block`() {
        val diff = buildUnifiedDiff("/workspace/a/Protocol.kt", "old one\nold two", "new one\nnew two", labels)
        assertEquals(
            listOf(
                "--- Protocol.kt",
                "+++ Protocol.kt",
                "-old one",
                "-old two",
                "+new one",
                "+new two",
            ),
            diff.lines(),
        )
    }

    /** 新建文件（Write）没有旧内容，不能凭空多出一行 `-` */
    @Test
    fun `empty old string produces no removal lines`() {
        val diff = buildUnifiedDiff("new.txt", "", "hello", labels)
        assertEquals(listOf("--- new.txt", "+++ new.txt", "+hello"), diff.lines())
    }

    /** 删空一个文件同理，不能多出一行 `+` */
    @Test
    fun `empty new string produces no addition lines`() {
        val diff = buildUnifiedDiff(null, "bye", "", labels)
        assertEquals(listOf("-bye"), diff.lines())
    }

    /** 没有路径就不写文件头，DiffView 会把 `---`/`+++` 当文件头着色 */
    @Test
    fun `missing path omits the file header`() {
        val diff = buildUnifiedDiff(null, "a", "b", labels)
        assertEquals(listOf("-a", "+b"), diff.lines())
        assertTrue(buildUnifiedDiff("   ", "a", "b", labels).lines().none { it.startsWith("---") })
    }

    /** 内容自带尾随换行时不能产生一行孤零零的 `+` */
    @Test
    fun `trailing newline does not leave a dangling marker line`() {
        val diff = buildUnifiedDiff(null, "", "a\nb\n", labels)
        assertEquals(listOf("+a", "+b", "+"), diff.lines())
        assertTrue("末尾不该有空行", !diff.endsWith("\n"))
    }

    @Test
    fun `write and multiedit route through the same diff builder`() {
        val write = diffOf("Write", input("""{"file_path":"/w/x.kt","content":"line"}"""), labels)
        assertEquals(listOf("--- x.kt", "+++ x.kt", "+line"), write.lines())

        val multi = diffOf(
            "MultiEdit",
            input(
                """{"file_path":"/w/x.kt","edits":[
                   {"old_string":"a","new_string":"b"},
                   {"old_string":"c","new_string":"d"}]}"""
            ),
            labels,
        )
        assertEquals(listOf("-a", "+b", "-c", "+d"), multi.lines())
    }

    /** 非编辑类工具不该被当成 diff —— 角标会算出莫名其妙的 +0 −0 */
    @Test
    fun `non edit tools have no diff`() {
        assertEquals("", diffOf("Bash", input("""{"command":"ls"}"""), labels))
        assertEquals("", diffOf("Read", input("""{"file_path":"/w/x.kt"}"""), labels))
    }

    /**
     * Claude 为了让 `old_string` 唯一，习惯把目标行连同上下文一起带上。不折叠公共前后缀的话，
     * 改一行会渲染成"删 N 行 + 加 N 行"，手机上要滑好几屏才找得到真正变了的那一处。
     */
    @Test
    fun `common prefix and suffix collapse into context lines`() {
        val diff = buildUnifiedDiff(
            null,
            "fun a() {\n    val x = 1\n    return x\n}",
            "fun a() {\n    val x = 2\n    return x\n}",
            labels,
        )
        assertEquals(
            listOf(
                " fun a() {",
                "-    val x = 1",
                "+    val x = 2",
                "     return x",
                " }",
            ),
            diff.lines(),
        )
    }

    /** 上下文行不带 +/− 前缀，统计角标必须只数真正的改动 */
    @Test
    fun `context lines are excluded from the diff stats`() {
        val diff = buildUnifiedDiff(null, "a\nb\nc", "a\nB\nc", labels)
        val stats = dev.min.code.ui.richtext.parseDiffStats(diff)
        assertEquals(1, stats.additions)
        assertEquals(1, stats.deletions)
    }

    /** 上下文超过 3 行就省略，并用标准 `@@` hunk 头说明省了多少 —— DiffView 会把它着色 */
    @Test
    fun `long context is elided with a hunk header`() {
        val head = (1..10).joinToString("\n") { "head$it" }
        val tail = (1..10).joinToString("\n") { "tail$it" }
        val diff = buildUnifiedDiff(null, "$head\nOLD\n$tail", "$head\nNEW\n$tail", labels)
        val lines = diff.lines()

        assertEquals("@@ 上方 7 行未改动 @@", lines.first())
        assertEquals("@@ 下方 7 行未改动 @@", lines.last())
        assertEquals(listOf(" head8", " head9", " head10", "-OLD", "+NEW", " tail1", " tail2", " tail3"), lines.drop(1).dropLast(1))
    }

    /** 整块重写没有公共前后缀，仍然是"整块删 + 整块增"，不做逐行 LCS */
    @Test
    fun `a full rewrite still renders as two blocks`() {
        val diff = buildUnifiedDiff(null, "p\nq\nr", "x\ny\nz", labels)
        assertEquals(listOf("-p", "-q", "-r", "+x", "+y", "+z"), diff.lines())
    }

    /** 前后缀不能重叠计数：`a` 只有一行，不能既算前缀又算后缀 */
    @Test
    fun `prefix and suffix never overlap`() {
        val diff = buildUnifiedDiff(null, "a", "a\nb", labels)
        assertEquals(listOf(" a", "+b"), diff.lines())
    }

    /**
     * `Write` 的入参里带着整份文件内容。折叠态的角标和权限面板都要拼 diff，
     * 一个 200KB 的写入会在滚动时反复生成同样大小的字符串。
     */
    @Test
    fun `bulk fields are clipped before building the diff`() {
        val huge = "x".repeat(60_000)
        val diff = boundedDiffOf("Write", input("""{"file_path":"/w/big.txt","content":"$huge"}"""), labels)

        assertTrue("必须比原文短得多", diff.length < 25_000)
        assertTrue("要说明被截断了", diff.contains("已截断"))
        assertTrue("要报出原始长度", diff.contains("60000"))
    }

    /** 内容不长时 bounded 版本必须和原版逐字一致，不能凭空改变已有渲染 */
    @Test
    fun `small inputs are untouched by the bound`() {
        val json = input("""{"file_path":"/w/x.kt","old_string":"a","new_string":"b"}""")
        assertEquals(diffOf("Edit", json, labels), boundedDiffOf("Edit", json, labels))
    }

    // endregion

    // region 语言推断

    @Test
    fun `language is inferred from the extension`() {
        assertEquals("kotlin", languageOf("/workspace/Foo.kt"))
        assertEquals("kotlin", languageOf("build.gradle.kts"))
        assertEquals("python", languageOf("a/b/c.py"))
        assertEquals("bash", languageOf("install.sh"))
        assertEquals("yaml", languageOf(".github/workflows/ci.yml"))
        assertEquals("markdown", languageOf("README.md"))
    }

    /** 认不出就当纯文本，别瞎猜 —— 猜错的高亮比没有高亮更难读 */
    @Test
    fun `unknown extensions fall back to plain text`() {
        assertEquals("text", languageOf("Makefile"))
        assertEquals("text", languageOf(null))
        assertEquals("text", languageOf("noext"))
        assertEquals("text", languageOf("archive.tar.zst"))
    }

    /**
     * 散文类扩展名必须稳定落在 text / markdown 上：Read 的渲染靠这个判断折行还是横滑，
     * txt 按源码行宽排的话手机上每行只剩十几个字。
     */
    @Test
    fun `prose extensions are text or markdown so the reader wraps them`() {
        assertEquals("text", languageOf("/workspace/notes.txt"))
        assertEquals("text", languageOf("build.log"))
        assertEquals("text", languageOf("data/export.CSV"))
        assertEquals("markdown", languageOf("docs/guide.md"))
        assertEquals("markdown", languageOf("CHANGELOG.markdown"))
        assertEquals("text", languageOf("LICENSE"))
        assertEquals("text", languageOf("a/b/noext"))
    }

    @Test
    fun `file name strips the directory prefix`() {
        // 手机上显示不下绝对路径，而且 /workspace/ 前缀在每一条里都是重复的
        assertEquals("Protocol.kt", fileName("/workspace/data/claudecode/Protocol.kt"))
        assertEquals("x", fileName("x"))
        assertEquals("?", fileName(null))
        assertEquals("?", fileName(""))
    }

    // endregion

    // region 折叠态摘要

    /**
     * Bash 优先用 `description` —— CLI 的工具 schema 明确要求模型为每条命令写一句
     * 主动语态的说明（"Show working tree status"），比原始命令好读得多。
     */
    @Test
    fun `bash prefers the description over the raw command`() {
        assertEquals(
            "Show working tree status",
            toolSummary("Bash", input("""{"command":"git status","description":"Show working tree status"}"""), labels),
        )
        assertEquals("git status", toolSummary("Bash", input("""{"command":"git status"}"""), labels))
    }

    @Test
    fun `read shows the file name and line range`() {
        assertEquals("x.kt · 101–200 行", toolSummary("Read", input("""{"file_path":"/w/x.kt","offset":100,"limit":100}"""), labels))
        assertEquals("x.kt · 前 50 行", toolSummary("Read", input("""{"file_path":"/w/x.kt","limit":50}"""), labels))
        assertEquals("x.kt", toolSummary("Read", input("""{"file_path":"/w/x.kt"}"""), labels))
    }

    @Test
    fun `edit and write summaries carry the useful extra`() {
        assertEquals(
            "x.kt · 全部替换",
            toolSummary("Edit", input("""{"file_path":"/w/x.kt","old_string":"a","new_string":"b","replace_all":true}"""), labels),
        )
        assertEquals(
            "x.kt · 2 行",
            toolSummary("Write", input("""{"file_path":"/w/x.kt","content":"a\nb"}"""), labels),
        )
        assertEquals(
            "x.kt · 3 处",
            toolSummary("MultiEdit", input("""{"file_path":"/w/x.kt","edits":[{},{},{}]}"""), labels),
        )
    }

    @Test
    fun `todo summary reports progress`() {
        assertEquals(
            "1/3 已完成",
            toolSummary(
                "TodoWrite",
                input(
                    """{"todos":[
                       {"content":"a","status":"completed","activeForm":"A"},
                       {"content":"b","status":"in_progress","activeForm":"B"},
                       {"content":"c","status":"pending","activeForm":"C"}]}"""
                ),
                labels,
            ),
        )
    }

    /** 字段缺失、类型不符、未知工具都必须降级成一句话，绝不能抛 */
    @Test
    fun `summaries degrade instead of throwing`() {
        assertEquals("", toolSummary("Bash", input("{}"), labels))
        assertEquals("?", toolSummary("Read", input("{}"), labels))
        assertEquals("", toolSummary("TodoWrite", input("""{"todos":"nope"}"""), labels))
        assertEquals("", toolSummary("Edit", input("""{"file_path":null}"""), labels).substringBefore("?").trim())
        assertTrue(toolSummary("SomeFutureTool", input("""{"a":"b"}"""), labels).contains("a"))
        assertEquals("", toolSummary("SomeFutureTool", input("{}"), labels))
    }

    /** 摘要要压成单行并限长，否则多行命令会把折叠态撑开 */
    @Test
    fun `summaries are single line and bounded`() {
        val summary = toolSummary("Bash", input("""{"command":"${"x".repeat(400)}"}"""), labels)
        assertEquals(120, summary.length)

        val multiline = toolSummary("Bash", input("""{"command":"a\n\n   b\tc"}"""), labels)
        assertEquals("a b c", multiline)
    }

    // endregion
}
