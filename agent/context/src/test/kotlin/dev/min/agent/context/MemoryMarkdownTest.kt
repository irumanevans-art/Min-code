package dev.min.agent.context

import org.junit.Assert.assertEquals
import org.junit.Test

/** `@` 导入与规则 frontmatter 的纯文本解析，逐条对照 CC 官方文档的规则 */
class MemoryMarkdownTest {
    private fun imports(text: String) = MemoryMarkdown.imports(text)

    @Test
    fun `imports anywhere in prose with relative, home and absolute paths`() {
        val text = """
            See @README for project overview and @package.json for available npm commands.
            - git workflow @docs/git-instructions.md
            @./local.md @../up.md @~/.claude/mine.md @/abs/x.md
        """.trimIndent()
        assertEquals(
            listOf("README", "package.json", "docs/git-instructions.md", "./local.md", "../up.md", "~/.claude/mine.md", "/abs/x.md"),
            imports(text),
        )
    }

    @Test
    fun `escaped spaces join the path and a fragment is dropped`() {
        assertEquals(listOf("Design Docs/api-conventions.md"), imports("- API conventions @Design\\ Docs/api-conventions.md#errors"))
        // 不转义的空格就是路径的结尾
        assertEquals(listOf("Design"), imports("@Design Docs/api.md"))
    }

    @Test
    fun `things that only look like imports are not imports`() {
        val text = """
            mail me at someone@example.com
            quoted @"Design Docs/a.md" and @'b.md'
            a lone @ sign, @/ root, @@twice, @#anchor
        """.trimIndent()
        assertEquals(emptyList<String>(), imports(text))
    }

    @Test
    fun `code spans and fenced blocks are skipped`() {
        val text = """
            literal `see @README` and `` cat @double ` tick`` stay text; @after.md is real
            unclosed ` backtick keeps @open.md
            ```bash
            echo @inside-backticks.md
            ```
            ~~~~
            @inside-tildes.md
            ```
            @still-inside.md
            ~~~~
               ```
            @indented-fence.md
               ```
            @outside.md
        """.trimIndent()
        assertEquals(listOf("after.md", "open.md", "outside.md"), imports(text))
    }

    @Test
    fun `an unclosed fence hides everything after it`() {
        assertEquals(listOf("before.md"), imports("@before.md\n```\n@never.md"))
    }

    @Test
    fun `frontmatter paths in list, inline and flow forms`() {
        val list = MemoryMarkdown.splitFrontmatter("---\npaths:\n  - \"src/api/**/*.ts\"\n  - lib/**\nother: 1\n---\n# API\nbody")
        assertEquals(listOf("src/api/**/*.ts", "lib/**"), list.paths)
        assertEquals("# API\nbody", list.body)

        assertEquals(listOf("src/**", "*.md"), MemoryMarkdown.splitFrontmatter("---\npaths: src/**, *.md\n---\nx").paths)
        assertEquals(listOf("a/**", "b/**"), MemoryMarkdown.splitFrontmatter("---\npaths: [\"a/**\", 'b/**']\n---\nx").paths)
        // 花括号里的逗号是同一个 glob 的一部分，不拆
        assertEquals(listOf("src/**/*.{ts,tsx}", "docs/**"), MemoryMarkdown.splitFrontmatter("---\npaths: src/**/*.{ts,tsx}, docs/**\n---\nx").paths)
        assertEquals(listOf("src/**/*.{ts,tsx}"), MemoryMarkdown.splitFrontmatter("---\npaths: [src/**/*.{ts,tsx}]\n---\nx").paths)
        // 与键同缩进的列表也是合法 YAML
        assertEquals(listOf("x/**"), MemoryMarkdown.splitFrontmatter("---\npaths:\n- x/**\n---\n").paths)
    }

    @Test
    fun `frontmatter without paths is stripped and missing or unclosed frontmatter keeps the text`() {
        val other = MemoryMarkdown.splitFrontmatter("---\ndescription: style\n---\nUse tabs.")
        assertEquals(Frontmatter(emptyList(), "Use tabs."), other)
        // 空的 paths 等于没有：无条件加载
        assertEquals(emptyList<String>(), MemoryMarkdown.splitFrontmatter("---\npaths:\n---\nx").paths)
        assertEquals(Frontmatter(emptyList(), "no frontmatter"), MemoryMarkdown.splitFrontmatter("no frontmatter"))
        assertEquals(Frontmatter(emptyList(), "---\npaths: a\nnever closed"), MemoryMarkdown.splitFrontmatter("---\npaths: a\nnever closed"))
    }
}
