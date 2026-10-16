package dev.min.agent.ext.skills

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 参数替换的期望逐条对照 skills.md「Argument substitution」；文档没写的边角（越界 `$ARGUMENTS[N]`、`$12abc`、名字前缀）
 * 对照 Claude Code 2.1.286 二进制里的实现，测试名里标了。
 * Kotlin 字符串里 `$` 要写成 `\$`，下面的期望都是这么写的。
 */
class SkillArgumentsTest {
    private fun sub(body: String, args: String?, names: List<String> = emptyList()) = SkillArguments.substitute(body, args, names)

    // ---- 拆参数 ----

    @Test
    fun `parse splits on whitespace and honours shell style quotes`() {
        assertEquals(listOf("hello world", "second"), SkillArguments.parse("\"hello world\" second"))
        assertEquals(listOf("a b", "c"), SkillArguments.parse("'a b' c"))
        assertEquals(listOf("a\"b", "c"), SkillArguments.parse("\"a\\\"b\" c"))
        assertEquals(listOf("a b"), SkillArguments.parse("a\\ b"))
        // 引号紧贴文字：拼成一个参数
        assertEquals(listOf("ab c"), SkillArguments.parse("a\"b c\""))
        // 空引号也是一个（空）参数
        assertEquals(listOf("", "x"), SkillArguments.parse("\"\" x"))
    }

    @Test
    fun `parse of nothing is empty and an open quote runs to the end`() {
        assertEquals(emptyList<String>(), SkillArguments.parse(null))
        assertEquals(emptyList<String>(), SkillArguments.parse("   "))
        assertEquals(listOf("a", "b c"), SkillArguments.parse("a \"b c"))
    }

    @Test
    fun `parse does not expand shell variables`() {
        assertEquals(listOf("\$HOME", "\$(x)"), SkillArguments.parse("\$HOME \$(x)"))
    }

    // ---- 占位符 ----

    @Test
    fun `ARGUMENTS is the full string exactly as typed`() {
        assertEquals("Fix GitHub issue 123 now.", sub("Fix GitHub issue \$ARGUMENTS now.", "123"))
        assertEquals("x: \"hello world\"  second", sub("x: \$ARGUMENTS", "\"hello world\"  second"))
    }

    @Test
    fun `indexed placeholders use zero based shell quoted arguments`() {
        assertEquals("Migrate SearchBar from JavaScript to TypeScript.", sub("Migrate \$0 from \$1 to \$2.", "SearchBar JavaScript TypeScript"))
        assertEquals("Migrate SearchBar from JavaScript.", sub("Migrate \$ARGUMENTS[0] from \$ARGUMENTS[1].", "SearchBar JavaScript"))
        assertEquals("[hello world] [second]", sub("[\$0] [\$1]", "\"hello world\" second"))
    }

    @Test
    fun `named placeholders map to positions in order and an absent one becomes empty`() {
        assertEquals("issue=7 branch=main", sub("issue=\$issue branch=\$branch", "7 main", listOf("issue", "branch")))
        assertEquals("issue=7 branch=", sub("issue=\$issue branch=\$branch", "7", listOf("issue", "branch")))
    }

    @Test
    fun `a longer name wins over its prefix and a name followed by a word character or bracket does not match`() {
        assertEquals("A=1 B=2 \$issues", sub("A=\$issue B=\$issue_id \$issues", "1 2", listOf("issue", "issue_id")))
        assertEquals("[1] \$issuex", sub("[\$issue] \$issuex", "1", listOf("issue")))
        // 名字后面紧跟 `[` 也不算（CC 的 (?![\[\w])）
        assertEquals("\$issue[0]\n\nARGUMENTS: 1", sub("\$issue[0]", "1", listOf("issue")))
    }

    @Test
    fun `blank and purely numeric argument names are dropped before positions are counted`() {
        // 名字表 ["1", "", "a"] 滤掉前两个之后 a 是第 0 个；$1 是越界的下标占位符，原样留着
        assertEquals("\$1 hello", sub("\$1 \$a", "hello", listOf("1", "", "a")))
    }

    // ---- 名字上限（CC 2.1.301：只填前 64 个名字，每个最长 64 字符） ----

    @Test
    fun `only the first 64 argument names are filled and later ones stay literal`() {
        val names = (0 until 65).map { "n$it" }
        val args = (0 until 65).joinToString(" ") { "v$it" }
        assertEquals("v0 v63 \$n64", sub("\$n0 \$n63 \$n64", args, names))
        // 名字之外的占位符（$ARGUMENTS[N]、$N）不受名字上限影响
        assertEquals("v64", sub("\$ARGUMENTS[64]", args, names))
    }

    @Test
    fun `a name of 65 characters is skipped but 64 is kept, and later names keep their own position`() {
        val long = "a".repeat(65)
        val ok = "b".repeat(64)
        // 超长名字被跳过：它占的位置（第 0 个）留空，后面的名字仍对应第 1 个 / 第 2 个参数
        assertEquals("\$$long|x1|x2", sub("\$$long|\$short|\$$ok", "x0 x1 x2", listOf(long, "short", ok)))
        assertEquals("\$$long\n\nARGUMENTS: x0", sub("\$$long", "x0", listOf(long)))
        assertEquals("x0", sub("\$$ok", "x0", listOf(ok)))
    }

    @Test
    fun `names dropped for length do not count against the limit of 64`() {
        // CC 先按长度滤、再取前 64：66 个名字里一个超长，剩下 65 个，最后一个（位置 65）落在 64 个之外
        val names = listOf("x".repeat(65)) + (1..65).map { "m$it" }
        val args = (0..65).joinToString(" ") { "v$it" }
        assertEquals("v1 v64 \$m65", sub("\$m1 \$m64 \$m65", args, names))
    }

    // ---- 没有收到参数的情况 ----

    @Test
    fun `arguments are appended when no placeholder received them`() {
        assertEquals("Run the deploy.\n\nARGUMENTS: staging --force", sub("Run the deploy.", "staging --force"))
    }

    @Test
    fun `nothing is appended for empty arguments and nothing changes for null arguments`() {
        assertEquals("Run the deploy.", sub("Run the deploy.", ""))
        assertEquals("Run \$ARGUMENTS and \\\$1.", sub("Run \$ARGUMENTS and \\\$1.", null))
    }

    @Test
    fun `an indexed placeholder without an argument stays literal and does not count as received`() {
        // 只有 $2 且只给了一个参数：$2 原样留着，所以仍然要追加
        assertEquals("take \$2\n\nARGUMENTS: one", sub("take \$2", "one"))
        assertEquals("take \$ARGUMENTS[3]\n\nARGUMENTS: one", sub("take \$ARGUMENTS[3]", "one"))
    }

    @Test
    fun `a named placeholder counts as received even without an argument at its position`() {
        assertEquals("a=", sub("a=\$x", "", listOf("x")))
        assertEquals("a=", sub("a=\$y", "  ", listOf("x", "y")))
    }

    @Test
    fun `a digit run followed by a word character is not a placeholder`() {
        // CC：\$(\d+)(?!\w)，所以 $12abc 与 $1a 都不展开
        assertEquals("\$12abc \$1a\n\nARGUMENTS: x y", sub("\$12abc \$1a", "x y"))
    }

    // ---- 转义与二次展开 ----

    @Test
    fun `a backslash escapes a placeholder into literal text`() {
        assertEquals("costs \$1.00, you passed hi", sub("costs \\\$1.00, you passed \$0", "hi"))
        assertEquals("\$ARGUMENTS and hi", sub("\\\$ARGUMENTS and \$ARGUMENTS", "hi"))
        assertEquals("\$topic is x", sub("\\\$topic is \$topic", "x", listOf("topic")))
    }

    @Test
    fun `a backslash before any other dollar is left alone`() {
        assertEquals("\\\$HOME \\\$\n\nARGUMENTS: x", sub("\\\$HOME \\\$", "x"))
    }

    @Test
    fun `a doubled backslash does not escape`() {
        // 文档：两个反斜杠接 $1，两个都留着，$1 照常展开
        assertEquals("\\\\hi", sub("\\\\\$0", "hi"))
    }

    @Test
    fun `an escaped placeholder does not count as received`() {
        assertEquals("price \$1\n\nARGUMENTS: x", sub("price \\\$1", "x"))
    }

    @Test
    fun `argument values are inserted literally and never expanded again`() {
        assertEquals("[\$1] [b]", sub("[\$0] [\$1]", "\\\$1 b"))
        assertEquals("got \$ARGUMENTS and \$1", sub("got \$0", "'\$ARGUMENTS and \$1'"))
        assertEquals("all: \$0 \$1", sub("all: \$ARGUMENTS", "\$0 \$1"))
    }
}
