package dev.min.agent.tools

import dev.min.agent.protocol.ImageSource
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.images
import dev.min.agent.testkit.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read 读 .ipynb：按单元渲染（id、类型、源码、输出） */
class NotebookReadTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs
    private val path = "/work/a.ipynb"

    private suspend fun read(vararg more: Pair<String, Any?>) = ReadTool.call(ctx, "file_path" to path, *more)

    /** 只含一个 code 单元的 notebook，[outputs] 是 outputs 数组里的 JSON 片段 */
    private fun codeNb(outputs: String, source: String = "\"x\"") =
        """{"cells":[{"cell_type":"code","execution_count":1,"id":"c1","metadata":{},"outputs":[$outputs],"source":[$source]}],"metadata":{},"nbformat":4,"nbformat_minor":5}"""

    // ---- 各类单元 ----

    @Test
    fun `markdown code raw 三种单元带 id 类型 序号 源码与输出`() = runTest {
        fs.put(path, NB1)
        val r = read()
        assertFalse(r.text, r.isError)
        assertEquals(
            "Jupyter notebook /work/a.ipynb（nbformat 4.5，python，共 3 个单元）\n" +
                "<cell id=\"md000001\" index=\"0\" type=\"markdown\">\n# 标题\n正文\n</cell>\n" +
                "<cell id=\"code0002\" index=\"1\" type=\"code\" execution_count=\"3\">\nprint('hi')\n" +
                "<output stream=\"stdout\">\nhi\n</output>\n</cell>\n" +
                "<cell id=\"raw00003\" index=\"2\" type=\"raw\">\nraw text\n</cell>\n",
            r.text,
        )
    }

    @Test
    fun `读过之后 touched 记下文件状态，供 NotebookEdit 做先读后改`() = runTest {
        fs.put(path, NB1)
        assertEquals(listOf(fs.stamp(path)), read().touched)
    }

    @Test
    fun `source 是单个字符串也能读，空源码单元只有头尾`() = runTest {
        fs.put(path, """{"cells":[{"cell_type":"markdown","id":"m","metadata":{},"source":"一行\n两行"},{"cell_type":"code","id":"e","metadata":{},"outputs":[],"execution_count":null,"source":[]}],"metadata":{},"nbformat":4,"nbformat_minor":5}""")
        assertTrue(
            read().text.endsWith(
                "<cell id=\"m\" index=\"0\" type=\"markdown\">\n一行\n两行\n</cell>\n<cell id=\"e\" index=\"1\" type=\"code\">\n</cell>\n",
            ),
        )
    }

    @Test
    fun `没有单元的 notebook 说明怎么加第一个`() = runTest {
        fs.put(path, nb())
        val r = read()
        assertFalse(r.isError)
        assertTrue(r.text, r.text.contains("共 0 个单元") && r.text.contains("edit_mode=insert"))
    }

    // ---- 旧格式（没有 cell id）----

    @Test
    fun `没有 id 的旧格式用 cell-序号 代替，序号从 0 起`() = runTest {
        fs.put(path, """{"cells":[{"cell_type":"markdown","metadata":{},"source":["a"]},{"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],"source":["b"]}],"metadata":{},"nbformat":4,"nbformat_minor":2}""")
        val r = read()
        assertTrue(r.text, r.text.contains("（nbformat 4.2，共 2 个单元）"))
        assertTrue(r.text, r.text.contains("<cell id=\"cell-0\" index=\"0\" type=\"markdown\">"))
        assertTrue(r.text, r.text.contains("<cell id=\"cell-1\" index=\"1\" type=\"code\">"))
    }

    @Test
    fun `nbformat 3 的旧格式给出升级办法`() = runTest {
        fs.put(path, """{"nbformat":3,"nbformat_minor":0,"worksheets":[{"cells":[]}],"metadata":{}}""")
        val r = read()
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("旧格式") && r.text.contains("nbconvert"))
    }

    // ---- 输出 ----

    @Test
    fun `stream 的 stdout 与 stderr 分开标，text 可以是数组`() = runTest {
        fs.put(path, codeNb("""{"output_type":"stream","name":"stdout","text":["a\n","b\n"]},{"output_type":"stream","name":"stderr","text":"warn\n"}"""))
        assertTrue(read().text.contains("<output stream=\"stdout\">\na\nb\n</output>\n<output stream=\"stderr\">\nwarn\n</output>\n"))
    }

    @Test
    fun `execute_result 取 text plain，display_data 也一样`() = runTest {
        fs.put(path, codeNb("""{"output_type":"execute_result","execution_count":1,"metadata":{},"data":{"text/plain":["42"],"text/html":["<b>42</b>"]}},{"output_type":"display_data","metadata":{},"data":{"text/plain":"shown"}}"""))
        assertTrue(read().text.contains("<output kind=\"result\">\n42\n</output>\n<output kind=\"display\">\nshown\n</output>\n"))
    }

    @Test
    fun `没有文本形式的输出给占位说明`() = runTest {
        fs.put(path, codeNb("""{"output_type":"display_data","metadata":{},"data":{"text/html":"<b>x</b>","image/svg+xml":"<svg/>"}}"""))
        assertTrue(read().text.contains("<output kind=\"display\">[只有 text/html、image/svg+xml，没有文本形式，已省略]</output>"))
    }

    @Test
    fun `错误输出取 traceback 并去掉 ANSI 色码`() = runTest {
        val tb = """"\u001b[0;31m---------------------------------------------------------------------------\u001b[0m","\u001b[0;31mZeroDivisionError\u001b[0m\u001b[0;31m:\u001b[0m division by zero""""
        fs.put(path, codeNb("""{"output_type":"error","ename":"ZeroDivisionError","evalue":"division by zero","traceback":[$tb]}"""))
        val text = read().text
        assertTrue(
            text,
            text.contains("<output error>\n---------------------------------------------------------------------------\nZeroDivisionError: division by zero\n</output>"),
        )
        assertFalse(text.contains('\u001B'))
    }

    @Test
    fun `没有 traceback 的错误用 ename 与 evalue`() = runTest {
        fs.put(path, codeNb("""{"output_type":"error","ename":"KeyboardInterrupt","evalue":"","traceback":[]}"""))
        assertTrue(read().text.contains("<output error>\nKeyboardInterrupt\n</output>"))
    }

    @Test
    fun `进度条的回车覆盖只留最后一版`() = runTest {
        fs.put(path, codeNb("""{"output_type":"stream","name":"stderr","text":"  0%|          | 0/2\r 50%|#####     | 1/2\r100%|##########| 2/2\n"}"""))
        val text = read().text
        assertTrue(text.replace("\r", "<CR>"), text.contains("<output stream=\"stderr\">\n100%|##########| 2/2\n</output>"))
    }

    // ---- 图片 ----

    @Test
    fun `png 输出以 ImagePart 内嵌，随带的 text plain 不重复`() = runTest {
        fs.put(path, codeNb("""{"output_type":"display_data","metadata":{},"data":{"image/png":"AQID\n","text/plain":"<Figure size 640x480>"}}"""))
        val r = read()
        val img = r.images.single()
        assertEquals("image/png", img.mediaType)
        assertEquals(ImageSource.Base64("AQID"), img.source)
        assertFalse(r.text.contains("Figure"))
        assertTrue(r.text, r.text.contains("<output kind=\"image\" type=\"image/png\">"))
    }

    @Test
    fun `一次最多内嵌 8 张，多的给占位`() = runTest {
        val one = """{"output_type":"display_data","metadata":{},"data":{"image/png":"AQID"}}"""
        fs.put(path, codeNb(List(10) { one }.joinToString(",")))
        val r = read()
        assertEquals(8, r.images.size)
        assertEquals(2, Regex("本次已内嵌 8 张，其余省略").findAll(r.text).count())
    }

    @Test
    fun `不是合法 base64 的图片给占位而不是把坏数据发出去`() = runTest {
        fs.put(path, codeNb("""{"output_type":"display_data","metadata":{},"data":{"image/png":"@@@@"}}"""))
        val r = read()
        assertTrue(r.images.isEmpty())
        assertTrue(r.text, r.text.contains("不是合法 base64"))
    }

    @Test
    fun `超过 5MB 的图片给占位`() = runTest {
        val big = "A".repeat(7 * 1024 * 1024)
        fs.put(path, codeNb("""{"output_type":"display_data","metadata":{},"data":{"image/png":"$big"}}"""))
        val r = read()
        assertTrue(r.images.isEmpty())
        assertTrue(r.text, r.text.contains("超过 5 MB 上限，已省略"))
    }

    // ---- 截断与分页 ----

    @Test
    fun `超长输出保留头尾各一半并注明省略多少`() = runTest {
        // 用换行拆成每行 100 字符，免得先被「单行 2000 字符」的截断吃掉
        val body = "a".repeat(4000) + "b".repeat(4000) + "MIDDLE" + "c".repeat(4000) + "d".repeat(4000)
        val lines = body.chunked(100).joinToString("\\n")
        fs.put(path, codeNb("""{"output_type":"stream","name":"stdout","text":"$lines"}"""))
        val out = read().text
        val omitted = Regex("…\\[输出共 (\\d+) 字符，中间省略了 (\\d+) 字符]…").find(out)!!
        // 16006 个字符 + 160 个换行（共 161 行，行间 160 个）
        assertEquals(16006 + 160, omitted.groupValues[1].toInt())
        assertEquals(omitted.groupValues[1].toInt() - 8000, omitted.groupValues[2].toInt())
        assertTrue(out.contains("a".repeat(100)))
        assertTrue(out.contains("d".repeat(100)))
        assertFalse(out.contains("MIDDLE"))
    }

    @Test
    fun `单行超过 2000 字符截断，源码也一样`() = runTest {
        fs.put(path, codeNb("""{"output_type":"stream","name":"stdout","text":"${"x".repeat(2500)}"}""", source = "\"${"y".repeat(2100)}\""))
        val text = read().text
        assertTrue(text.contains("x".repeat(2000) + "…[该行共 2500 字符，已截断]"))
        assertTrue(text.contains("y".repeat(2000) + "…[该行共 2100 字符，已截断]"))
    }

    @Test
    fun `offset 与 limit 按单元计，没读完时说明`() = runTest {
        fs.put(path, NB1)
        val r = read("offset" to 2, "limit" to 1)
        assertTrue(r.text, r.text.contains("<cell id=\"code0002\""))
        assertFalse(r.text.contains("md000001"))
        assertFalse(r.text.contains("raw00003"))
        assertTrue(r.text, r.text.endsWith("（共 3 个单元，只显示了第 2–2 个，用 offset/limit 读其余部分；offset / limit 对 notebook 按单元计，从 1 起）\n"))
    }

    @Test
    fun `offset 超出单元数是错误`() = runTest {
        fs.put(path, NB1)
        val r = read("offset" to 4)
        assertTrue(r.isError)
        assertEquals("offset=4 超出 notebook 末尾：/work/a.ipynb 只有 3 个单元（offset 对 notebook 按单元计）。", r.text)
    }

    @Test
    fun `超过 10MB 的 notebook 拒绝并建议 jq`() = runTest {
        fs.put(path, ByteArray(10 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        val r = read()
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("10485760") && r.text.contains("jq"))
    }

    // ---- 坏文件 ----

    @Test
    fun `坏 JSON 说明文件可能损坏`() = runTest {
        fs.put(path, "{\"cells\": [")
        val r = read()
        assertTrue(r.isError)
        assertTrue(r.text, r.text.startsWith("/work/a.ipynb 不是合法的 JSON，notebook 文件可能损坏："))
        assertTrue(r.touched.isEmpty())
    }

    @Test
    fun `顶层不是对象或没有 cells 都是错误`() = runTest {
        fs.put(path, "[1,2]")
        assertEquals("/work/a.ipynb 的顶层不是 JSON 对象，不是 Jupyter notebook。", read().text)
        fs.put(path, """{"metadata":{},"nbformat":4}""")
        assertEquals("/work/a.ipynb 缺少 `cells` 数组，不是有效的 Jupyter notebook。", read().text)
        fs.put(path, """{"cells":[1],"nbformat":4}""")
        assertEquals("/work/a.ipynb 的第 0 个单元不是 JSON 对象，notebook 结构不对。", read().text)
    }

    @Test
    fun `空文件按空文件处理`() = runTest {
        fs.put(path, "")
        val r = read()
        assertFalse(r.isError)
        assertEquals("文件存在但是空的：/work/a.ipynb", r.text)
    }

    @Test
    fun `扩展名大小写不敏感，带 BOM 也能读`() = runTest {
        fs.put("/work/B.IPYNB", "\uFEFF" + NB1)
        assertTrue(ReadTool.call(ctx, "file_path" to "/work/B.IPYNB").text.contains("md000001"))
    }
}
