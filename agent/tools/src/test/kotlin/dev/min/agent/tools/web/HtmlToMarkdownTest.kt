package dev.min.agent.tools.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * 转换规则表逐行 + 正文优先/空白/实体/转义/相对地址五组 + 容错一组的单测。
 * 断言输出完全相等（md() 去掉结尾换行方便读）；样本的逐字比对在 HtmlToMarkdownSampleTest。
 */
class HtmlToMarkdownTest {
    private fun md(html: String, base: String? = null): String = HtmlToMarkdown.convert(html, base).markdown.trimEnd()

    // ---- 规则表逐行 ----

    @Test
    fun `h1 to h6 become atx headings with blank lines`() {
        assertEquals("# A\n\n## B\n\n###### C", md("<h1>A</h1><h2>B</h2><h6>C</h6>"))
    }

    @Test
    fun `block tags separate paragraphs with a blank line`() {
        assertEquals("a\n\nb\n\nc", md("<p>a</p><div>b</div><section>c</section>"))
    }

    @Test
    fun `br becomes a line break`() {
        assertEquals("a\nb", md("<p>a<br>b</p>"))
    }

    @Test
    fun `hr becomes three dashes`() {
        assertEquals("a\n\n---\n\nb", md("<p>a</p><hr><p>b</p>"))
    }

    @Test
    fun `strong and b wrap in double asterisks`() {
        assertEquals("x **a** **b** y", md("<p>x <strong>a</strong> <b>b</b> y</p>"))
    }

    @Test
    fun `em and i wrap in one asterisk`() {
        assertEquals("*a* *b*", md("<p><em>a</em> <i>b</i></p>"))
    }

    @Test
    fun `empty emphasis is dropped`() {
        assertEquals("x", md("<p><b></b>x<i> </i></p>"))
    }

    @Test
    fun `inline code uses backticks`() {
        assertEquals("run `x = 1` now", md("<p>run <code>x = 1</code> now</p>"))
    }

    @Test
    fun `inline code containing a backtick wraps in two backticks`() {
        assertEquals("``a`b``", md("<p><code>a`b</code></p>"))
    }

    @Test
    fun `pre becomes a fenced block that preserves whitespace verbatim`() {
        assertEquals(
            "```\nval a = 1\n    indented\n\nplain\n```",
            md("<pre><code>val a = 1\n    indented\n\nplain</code></pre>"),
        )
    }

    @Test
    fun `pre code language comes from the class attribute`() {
        assertEquals(
            "```kotlin\nval a = 1\n```",
            md("""<pre><code class="language-kotlin">val a = 1</code></pre>"""),
        )
    }

    @Test
    fun `a with relative href resolves against baseUrl`() {
        assertEquals("[文字](https://e.com/a/b.png)", md("""<a href="b.png">文字</a>""", "https://e.com/a/page.html"))
    }

    @Test
    fun `a with javascript href keeps only the text`() {
        assertEquals("点我", md("""<a href="javascript:void(0)">点我</a>""", "https://e.com/"))
    }

    @Test
    fun `a with empty text uses the url as its label`() {
        assertEquals("[https://e.com/x](https://e.com/x)", md("""<a href="/x"><span> </span></a>""", "https://e.com/"))
    }

    @Test
    fun `a without href keeps only the text`() {
        assertEquals("锚点", md("""<a name="top">锚点</a>"""))
    }

    @Test
    fun `img becomes an image with resolved src`() {
        assertEquals("![截图](https://e.com/a/shot.png)", md("""<img src="shot.png" alt="截图">""", "https://e.com/a/"))
    }

    @Test
    fun `img without alt keeps empty alt`() {
        assertEquals("![](https://e.com/x.png)", md("""<img src="/x.png">""", "https://e.com/"))
    }

    @Test
    fun `img without src is dropped`() {
        assertEquals("前后", md("""<p>前<img alt="无地址">后</p>"""))
    }

    @Test
    fun `ul renders dash items`() {
        assertEquals("- a\n- b", md("<ul><li>a</li><li>b</li></ul>"))
    }

    @Test
    fun `ol numbers items in order`() {
        assertEquals("1. a\n2. b\n3. c", md("<ol><li>a</li><li>b</li><li>c</li></ol>"))
    }

    @Test
    fun `ol start attribute offsets the numbering`() {
        assertEquals("5. a\n6. b", md("""<ol start="5"><li>a</li><li>b</li></ol>"""))
    }

    @Test
    fun `nested lists indent two spaces per level`() {
        assertEquals("- a\n  - b\n    - c", md("<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li></ul>"))
    }

    @Test
    fun `blockquote prefixes every line`() {
        assertEquals("> a\n>\n> b", md("<blockquote><p>a</p><p>b</p></blockquote>"))
    }

    @Test
    fun `nested blockquotes stack the markers`() {
        assertEquals("> outer\n>\n> > inner", md("<blockquote><p>outer</p><blockquote><p>inner</p></blockquote></blockquote>"))
    }

    @Test
    fun `simple table becomes a gfm pipe table with header separator`() {
        assertEquals(
            "| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |",
            md("<table><tr><th>名称</th><th>值</th></tr><tr><td>a</td><td>1</td></tr><tr><td>b</td><td>2</td></tr></table>"),
        )
    }

    @Test
    fun `table cell pipes are escaped`() {
        assertEquals(
            "| a | b |\n| --- | --- |\n| x\\|y | z |",
            md("<table><tr><th>a</th><th>b</th></tr><tr><td>x|y</td><td>z</td></tr></table>"),
        )
    }

    @Test
    fun `thead and tbody wrappers still yield rows`() {
        assertEquals(
            "| h |\n| --- |\n| d |",
            md("<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>d</td></tr></tbody></table>"),
        )
    }

    @Test
    fun `table with colspan renders one line per row`() {
        assertEquals(
            "名称 | 值\n横跨\na | 1",
            md(
                "<table><tr><th>名称</th><th>值</th></tr>" +
                    "<tr><td colspan=\"2\">横跨</td></tr><tr><td>a</td><td>1</td></tr></table>",
            ),
        )
    }

    @Test
    fun `del and s wrap in tildes`() {
        assertEquals("~~删~~ ~~掉~~", md("<p><del>删</del> <s>掉</s></p>"))
    }

    @Test
    fun `script style and friends are dropped whole`() {
        assertEquals(
            "keep",
            md(
                "<script>var a = 1;</script><style>.a{color:red}</style><noscript>n</noscript>" +
                    "<template>t</template><svg><circle r='1'/></svg><canvas>c</canvas>" +
                    "<iframe src='https://e.com'></iframe><p>keep</p>",
            ),
        )
    }

    @Test
    fun `head is dropped but its title is captured`() {
        val page = HtmlToMarkdown.convert(
            "<html><head><title>T &amp; T</title><style>.x{}</style></head><body><p>正文</p></body></html>",
        )
        assertEquals("T & T", page.title)
        assertEquals("正文", page.markdown.trimEnd())
    }

    @Test
    fun `comments and doctype are dropped`() {
        assertEquals("a", md("<!DOCTYPE html>\n<!-- 导航 --><p>a</p><!--/导航-->"))
    }

    @Test
    fun `unknown tags keep their text`() {
        assertEquals("x y z", md("<p><span>x</span> <font>y</font> <abbr>z</abbr></p>"))
    }

    // ---- 正文优先 ----

    @Test
    fun `main wins over article and body`() {
        assertEquals(
            "内",
            md("<body><p>头</p><article><p>文</p></article><main><p>内</p></main><p>尾</p></body>"),
        )
    }

    @Test
    fun `first article wins when there is no main`() {
        assertEquals(
            "一",
            md("<body><p>头</p><article><p>一</p></article><article><p>二</p></article></body>"),
        )
    }

    @Test
    fun `body is the fallback and nav junk around it stays`() {
        // 没有 main/article：转整个 body，nav 里的文字也算正文的一部分
        assertEquals("# 站点\n\n正文", md("<body><nav><h1>站点</h1></nav><p>正文</p></body>"))
    }

    @Test
    fun `no title means null title`() {
        assertNull(HtmlToMarkdown.convert("<p>a</p>").title)
    }

    // ---- 空白 ----

    @Test
    fun `runs of whitespace collapse to one space`() {
        assertEquals("a b c", md("<p>a\n\t\tb   c</p>"))
    }

    @Test
    fun `paragraph edges are trimmed`() {
        assertEquals("a b", md("<div>   a\n\n b   </div>"))
    }

    @Test
    fun `three consecutive brs do not yield three newlines`() {
        assertEquals("a\n\nb", md("<p>a<br><br><br>b</p>"))
    }

    // ---- 实体 ----

    @Test
    fun `named and numeric entities decode`() {
        assertEquals(
            "a & < > \" ' ' b { 😀 c &madeup; d",
            md("<p>a &amp; &lt; &gt; &quot; &#39; &apos; &nbsp; b &#123; &#x1F600; c &madeup; d</p>"),
        )
    }

    @Test
    fun `ampersand without entity stays literal`() {
        assertEquals("AT&T and R&D", md("<p>AT&amp;T and R&amp;D</p>"))
    }

    // ---- Markdown 特殊字符 ----

    @Test
    fun `markdown punctuation in plain text is escaped`() {
        assertEquals("a \\* b \\_ c \\[ d \\] e \\` f", md("<p>a * b _ c [ d ] e ` f</p>"))
    }

    @Test
    fun `inline code is not escaped`() {
        assertEquals("`*a* [b]`", md("<p><code>*a* [b]</code></p>"))
    }

    @Test
    fun `code blocks are not escaped`() {
        assertEquals("```\n*a* [b] `c`\n```", md("<pre>*a* [b] `c`</pre>"))
    }

    // ---- 相对地址 ----

    @Test
    fun `relative urls merge with the base directory`() {
        assertEquals(
            "![](https://e.com/a/b/c.png)",
            md("""<img src="c.png">""", "https://e.com/a/b/page.html"),
        )
    }

    @Test
    fun `dot-dot climbs the base directory`() {
        assertEquals("![](https://e.com/a/up.png)", md("""<img src="../up.png">""", "https://e.com/a/b/page.html"))
    }

    @Test
    fun `root relative uses the host only`() {
        assertEquals("![](https://e.com/x.png)", md("""<img src="/x.png">""", "https://e.com/a/b/page.html"))
    }

    @Test
    fun `protocol relative keeps the base scheme`() {
        assertEquals("![](https://cdn.e.com/x.png)", md("""<img src="//cdn.e.com/x.png">""", "https://e.com/"))
    }

    @Test
    fun `fragment anchors keep the page path`() {
        assertEquals(
            "[跳转](https://e.com/a/page.html#sec)",
            md("""<a href="#sec">跳转</a>""", "https://e.com/a/page.html"),
        )
    }

    @Test
    fun `absolute and mailto urls pass through untouched`() {
        assertEquals(
            "[邮我](mailto:a@e.com) [外站](https://other.e.com/x)",
            md("""<p><a href="mailto:a@e.com">邮我</a> <a href="https://other.e.com/x">外站</a></p>""", "https://e.com/"),
        )
    }

    @Test
    fun `null baseUrl keeps hrefs as-is`() {
        assertEquals("[文字](b.png)", md("""<a href="b.png">文字</a>"""))
    }

    @Test
    fun `base without scheme cannot resolve and keeps the ref`() {
        assertEquals("[文字](b.png)", md("""<a href="b.png">文字</a>""", "not-a-url"))
    }

    // ---- 容错 ----

    @Test
    fun `unclosed p tags still split paragraphs`() {
        assertEquals("a\n\nb\n\nc", md("<div><p>a<p>b<div>c</div></div>"))
    }

    @Test
    fun `unclosed li tags still split items`() {
        assertEquals("- a\n- b\n- c", md("<ul><li>a<li>b<li>c</ul>"))
    }

    @Test
    fun `stray close tags are ignored`() {
        assertEquals("a\n\nb", md("<p>a</p></div></li></p><p>b</p>"))
    }

    @Test
    fun `unquoted attribute values parse`() {
        assertEquals("[y](https://e.com/x)", md("<a href=https://e.com/x>y</a>", null))
    }

    @Test
    fun `mixed case tags are matched case-insensitively`() {
        assertEquals("a", md("<DIV><P>a</P></DIV>"))
    }

    @Test
    fun `self closing void tags work`() {
        assertEquals("a\n\n---\n\nb\n\n![](x.png)", md("<p>a</p><hr /><p>b<br/></p><img src=x.png />"))
    }

    @Test
    fun `angle brackets inside script do not become tags`() {
        assertEquals("ok", md("<script>if (a<b && c>d) { x }</script><p>ok</p>"))
    }

    @Test
    fun `bare less-than in text stays text`() {
        assertEquals("a < b", md("<p>a < b</p>"))
    }

    @Test
    fun `truncated markup does not throw`() {
        // 扫到结尾都没有 '>'：坏到哪里算哪里
        assertEquals("# 标题", md("<h1>标题<h2"))
        assertEquals("文字 <", md("<p>文字 <"))
    }

    @Test
    fun `empty input gives empty output`() {
        val page = HtmlToMarkdown.convert("")
        assertNull(page.title)
        assertEquals("", page.markdown)
    }
}
