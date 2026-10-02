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
