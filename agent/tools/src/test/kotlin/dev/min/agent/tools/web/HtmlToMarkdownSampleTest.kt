package dev.min.agent.tools.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 样本测试：resources/web/ 下的 5 个手写样本各配一份期望 .md，逐字比对——
 * 单测盯规则，样本盯「整页拼起来的效果对不对」。样本是模仿真实网页手写的：
 * 技术博客文章页、API 文档页、带代码块的教程页、带表格的页面、一个老式乱页面。
 */
class HtmlToMarkdownSampleTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/web/$name.html")!!.readBytes().decodeToString()

    private fun expected(name: String): String =
        javaClass.getResourceAsStream("/web/$name.md")!!.readBytes().decodeToString()

    private fun check(name: String, baseUrl: String?) {
        assertEquals(name, expected(name), HtmlToMarkdown.convert(sample(name), baseUrl).markdown)
    }

    @Test
    fun `tech blog article page`() = check("blog", "https://blog.example.com/2026/06/html-to-markdown.html")

    @Test
    fun `api docs page`() = check("api-docs", "https://docs.example.com/api/v2/")

    @Test
    fun `tutorial page with code blocks`() = check("tutorial", "https://docs.example.com/tutorials/kernel.html")

    @Test
    fun `page with tables`() = check("tables", "https://pricing.example.com/models.html")

    @Test
    fun `messy old school page`() = check("messy", "http://www.laowang.com/notes/index.html")

    @Test
    fun `conversion time grows linearly up to two megabytes`() {
        // 防的是平方级退化（逐字符拼接、反复回溯之类），不是测机器快慢：全量构建时十几个模块并行跑测试，
        // 绝对时限会被负载顶破。所以比 0.5 MB 与 2 MB 的耗时之比：线性约 4 倍，平方级约 16 倍。
        // 不带 main/article/body：正文优先只认第一个，整页重复会让其余内容全被丢弃
        val chunk = """
            <h2>负载样本</h2>
            <p>这是一段足够长的正文，用来把输入堆到兆级：<strong>强调</strong>、<em>斜体</em>、<code>行内代码</code>，外加
            <a href="/rel">相对链接</a> 与 <img src="pic.png" alt="图">，以及 &amp; &lt; 实体和 * 星号 _ 下划线 [ 方括号]。</p>
            <ul><li>甲<ul><li>乙</li></ul></li><li>丙</li></ul>
            <blockquote><p>引用一段需要重复渲染的文字，让它也占点分量。</p></blockquote>
            <table><tr><th>k</th><th>v</th></tr><tr><td>a|b</td><td>1</td></tr></table>
            <pre><code class="language-kotlin">val x = 1 // 代码保持原样 *a* [b]</code></pre>
        """.trimIndent()
        fun page(size: Int) = buildString { while (length < size) append(chunk) }
        val small = page(500_000)
        val big = page(2_000_000)
        fun bestMs(html: String, runs: Int): Long = (1..runs).minOf {
            val start = System.nanoTime()
            HtmlToMarkdown.convert(html, "https://bench.example.com/")
            (System.nanoTime() - start) / 1_000_000
        }
        HtmlToMarkdown.convert(small, "https://bench.example.com/") // 先热一遍 JIT
        val smallMs = bestMs(small, 3).coerceAtLeast(1)
        val bigMs = bestMs(big, 2)
        val output = HtmlToMarkdown.convert(big, "https://bench.example.com/").markdown
        assertTrue("输出要实质存在（实际 ${output.length} 字符）", output.length > 500_000)
        assertTrue("2 MB 耗时 ${bigMs}ms，0.5 MB 耗时 ${smallMs}ms：涨幅远超线性", bigMs < smallMs * 10 + 200)
        assertTrue("2 MB 耗时 ${bigMs}ms，超过 10 秒", bigMs < 10_000)
    }
}
