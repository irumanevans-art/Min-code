package dev.min.agent.tools.web

/*
 * 网页 HTML → Markdown 的纯转换函数（WebFetch 工具的地基）。
 * 只做转换：不抓取、不联网、不管工具编排。输入是任意字符串，输出要么是结果要么是合理的东西——不抛异常。
 */

/** 转换结果：网页标题（<title>，没有为 null）与正文 Markdown */
data class ConvertedPage(val title: String?, val markdown: String)

object HtmlToMarkdown {

    /**
     * [baseUrl]：网页自己的地址，用来把相对链接、相对图片地址变成绝对地址；null 时原样保留。
     */
    fun convert(html: String, baseUrl: String? = null): ConvertedPage {
        val parsed = HtmlParser.parse(html)
        val markdown = MarkdownRenderer(baseUrl).render(contentRoot(parsed.root))
        return ConvertedPage(parsed.title, markdown)
    }

    /** 正文优先：有 <main> 只转 main 里面的；没有但有无 <article> 转第一个 article；再没有转 <body>；都没有就转全部 */
    private fun contentRoot(root: HtmlElement): HtmlElement =
        findFirst(root, "main") ?: findFirst(root, "article") ?: findFirst(root, "body") ?: root

    /** 文档序的第一个指定标签 */
    private fun findFirst(node: HtmlElement, tag: String): HtmlElement? {
        for (child in node.children) {
            if (child !is HtmlElement) continue
            if (child.tag == tag) return child
            findFirst(child, tag)?.let { return it }
        }
        return null
    }
}
