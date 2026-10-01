package dev.min.agent.tools

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookEditToolTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs
    private val path = "/work/a.ipynb"

    private fun seed(content: String, p: String = path) {
        fs.put(p, content)
        ctx.see(p)
    }

    private suspend fun edit(vararg pairs: Pair<String, Any?>) = NotebookEditTool.call(ctx, "notebook_path" to path, *pairs)

    /** 新插入的单元 id：从结果文字里取，并在文件里换成固定占位，期望值才能手写 */
    private fun newId(r: ToolResult): String =
        Regex("单元 ([0-9a-f]{8})（序号").find(r.text)!!.groupValues[1]

    private val NEW_CODE = "  {\n" +
        "   \"cell_type\": \"code\",\n" +
        "   \"execution_count\": null,\n" +
        "   \"id\": \"NEWID000\",\n" +
        "   \"metadata\": {},\n" +
        "   \"outputs\": [],\n" +
        "   \"source\": [\n" +
        "    \"x = 1\\n\",\n" +
        "    \"x\"\n" +
        "   ]\n" +
        "  }"

    /** 替换后的 code 单元：id 与 metadata 照旧，outputs 清空，execution_count 为 null */
    private val REPLACED_CODE = "  {\n" +
        "   \"cell_type\": \"code\",\n" +
        "   \"execution_count\": null,\n" +
        "   \"id\": \"code0002\",\n" +
        "   \"metadata\": {\n" +
        "    \"tags\": [\n" +
        "     \"x\"\n" +
        "    ]\n" +
        "   },\n" +
        "   \"outputs\": [],\n" +
        "   \"source\": [\n" +
        "    \"x = 1\\n\",\n" +
        "    \"x\"\n" +
        "   ]\n" +
        "  }"

    // ---- parse ----

    @Test
    fun `缺字段与取值不对都被拒绝，话写给模型`() {
        val p = "notebook_path" to path
        assertEquals("缺少必填字段 `notebook_path`（字符串）。", NotebookEditTool.reject())
        assertTrue(NotebookEditTool.reject("notebook_path" to "a.ipynb").contains("绝对路径"))
        assertEquals("字段 `notebook_path` 必须是 .ipynb 文件，收到 `/work/a.py`。改别的文件请用 Edit 或 Write。", NotebookEditTool.reject("notebook_path" to "/work/a.py"))
        assertEquals("字段 `edit_mode` 必须是 replace、insert 或 delete，收到 `append`。", NotebookEditTool.reject(p, "edit_mode" to "append", "new_source" to "x", "cell_id" to "a"))
        assertEquals("字段 `cell_type` 必须是 code、markdown 或 raw，收到 `text`。", NotebookEditTool.reject(p, "cell_type" to "text", "new_source" to "x", "cell_id" to "a"))
    }

    @Test
    fun `replace 与 delete 必须给 cell_id，insert 必须给 cell_type，非 delete 必须给 new_source`() {
        val p = "notebook_path" to path
        assertEquals("edit_mode 为 replace 时必须给 `cell_id`。cell_id 取自 Read 这个 notebook 的输出。", NotebookEditTool.reject(p, "new_source" to "x"))
        assertEquals("edit_mode 为 delete 时必须给 `cell_id`。cell_id 取自 Read 这个 notebook 的输出。", NotebookEditTool.reject(p, "edit_mode" to "delete"))
        assertEquals("edit_mode 为 insert 时必须给 `cell_type`（code / markdown / raw）。", NotebookEditTool.reject(p, "edit_mode" to "insert", "new_source" to "x"))
        assertEquals("缺少必填字段 `new_source`（字符串）。", NotebookEditTool.reject(p, "cell_id" to "a"))
    }

    @Test
    fun `默认是 replace，delete 不需要 new_source，入参里的 CRLF 转成 LF`() {
        val replace = NotebookEditTool.parsed("notebook_path" to path, "cell_id" to "a", "new_source" to "a\r\nb")
        assertEquals(NotebookEditMode.Replace, replace.mode)
        assertEquals("a\nb", replace.newSource)
        val delete = NotebookEditTool.parsed("notebook_path" to path, "cell_id" to "a", "edit_mode" to "delete")
        assertEquals(NotebookEditMode.Delete, delete.mode)
        val insert = NotebookEditTool.parsed("notebook_path" to path, "edit_mode" to "insert", "cell_type" to "code", "new_source" to "")
        assertEquals(null, insert.cellId)
    }

    // ---- 权限与 traits ----

    @Test
    fun `权限请求按写路径声明，带分级`() {
        fs.scopes["/sdcard"] = PathScope.Device
        val ask = NotebookEditTool.permissionAsk(NotebookEditTool.parsed("notebook_path" to "/sdcard/n.ipynb", "cell_id" to "a", "new_source" to "x"), ctx)
        assertEquals("NotebookEdit", ask.tool)
        assertEquals("修改 notebook /sdcard/n.ipynb", ask.summary)
        assertEquals(GuestPath("/sdcard/n.ipynb"), ask.paths.single().path)
        assertEquals(AccessKind.Write, ask.paths.single().kind)
        assertEquals(PathScope.Device, ask.paths.single().scope)
    }

    @Test
    fun `traits 是写类工具的保守默认`() {
        assertFalse(NotebookEditTool.traits.readOnly)
        assertFalse(NotebookEditTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Unknown, NotebookEditTool.traits.resumePolicy)
    }

    // ---- 先读后改 ----

    @Test
    fun `没读过的 notebook 不许改，文件不动`() = runTest {
        fs.put(path, NB1)
        val r = edit("cell_id" to "md000001", "new_source" to "x")
        assertTrue(r.isError)
        assertEquals("修改 $path 之前先用 Read 读一遍。", r.text)
        assertEquals(NB1, fs.text(path))
        assertTrue(fs.writes.isEmpty())
    }

    @Test
    fun `读过之后被外部改过的不许改`() = runTest {
        seed(NB1)
        fs.touch(path)
        val r = edit("cell_id" to "md000001", "new_source" to "x")
        assertTrue(r.isError)
        assertEquals("$path 在你读过之后被改过，先重新 Read 再改。", r.text)
        assertEquals(NB1, fs.text(path))
    }

    @Test
    fun `文件不存在与是目录都说明怎么办`() = runTest {
        assertEquals("$path 不存在。NotebookEdit 只能改已有的 notebook；新建请用 Write 写入一个 notebook 的 JSON。", edit("cell_id" to "a", "new_source" to "x").text)
        fs.mkdir("/work/d.ipynb")
        val r = NotebookEditTool.call(ctx, "notebook_path" to "/work/d.ipynb", "cell_id" to "a", "new_source" to "x")
        assertEquals("/work/d.ipynb 是目录，不能编辑。", r.text)
    }

    @Test
    fun `改成功后 touched 记下新状态，紧接着再改不必重新读`() = runTest {
        seed(NB1)
        val first = edit("cell_id" to "md000001", "new_source" to "一")
        assertEquals(listOf(fs.stamp(path)), first.touched)
        ctx.seen[GuestPath(path)] = first.touched.single()
        assertFalse(edit("cell_id" to "raw00003", "new_source" to "二").isError)
    }

    // ---- replace ----

    @Test
    fun `替换 code 单元：只有这个单元变，输出与执行序号清空，其余字节一个不差`() = runTest {
        seed(NB1)
        val r = edit("cell_id" to "code0002", "new_source" to "x = 1\nx")
        assertFalse(r.text, r.isError)
        assertTrue(NB1.contains(CODE_CELL))
        assertEquals(nb(MD_CELL, REPLACED_CODE, RAW_CELL), fs.text(path))
        assertEquals(1, fs.writes.size)
        assertTrue(r.text, r.text.startsWith("已替换单元 code0002（序号 1）。它原有的输出与执行序号已清空，需要重新运行才有新输出。"))
        assertTrue(r.text, r.text.contains("<cell id=\"code0002\" index=\"1\" type=\"code\">\nx = 1\nx\n</cell>"))
    }

    @Test
    fun `连续替换两个单元（每次之后更新读到的状态）`() = runTest {
        seed(NB1)
        val r1 = edit("cell_id" to "md000001", "new_source" to "新标题")
        ctx.seen[GuestPath(path)] = r1.touched.single()
        val r2 = edit("cell_id" to "raw00003", "new_source" to "a\n\nb\n")
        assertFalse(r2.text, r2.isError)
        val md = MD_CELL.replace("    \"# 标题\\n\",\n    \"正文\"\n", "    \"新标题\"\n")
        val raw = RAW_CELL.replace("    \"raw text\"\n", "    \"a\\n\",\n    \"\\n\",\n    \"b\\n\"\n")
        assertEquals(nb(md, CODE_CELL, raw), fs.text(path))
    }

    @Test
    fun `source 原来是字符串就仍写成字符串`() = runTest {
        val text = """{"cells":[{"cell_type":"markdown","id":"m","metadata":{},"source":"旧"}],"metadata":{},"nbformat":4,"nbformat_minor":5}"""
        seed(text)
        edit("cell_id" to "m", "new_source" to "新\n行")
        assertEquals("""{"cells":[{"cell_type":"markdown","id":"m","metadata":{},"source":"新\n行"}],"metadata":{},"nbformat":4,"nbformat_minor":5}""", fs.text(path))
    }

    @Test
    fun `replace 带 cell_type 转换类型：markdown 变 code 补上空 outputs，code 变 markdown 去掉 outputs`() = runTest {
        seed(NB1)
        val r = edit("cell_id" to "md000001", "new_source" to "1+1", "cell_type" to "code")
        assertTrue(r.text, r.text.contains("类型由 markdown 改为 code。"))
        val code = "  {\n   \"cell_type\": \"code\",\n   \"execution_count\": null,\n   \"id\": \"md000001\",\n   \"metadata\": {},\n   \"outputs\": [],\n   \"source\": [\n    \"1+1\"\n   ]\n  }"
        assertEquals(nb(code, CODE_CELL, RAW_CELL), fs.text(path))

        seed(NB1)
        edit("cell_id" to "code0002", "new_source" to "说明", "cell_type" to "markdown")
        val md = "  {\n   \"cell_type\": \"markdown\",\n   \"id\": \"code0002\",\n   \"metadata\": {\n    \"tags\": [\n     \"x\"\n    ]\n   },\n   \"source\": [\n    \"说明\"\n   ]\n  }"
        assertEquals(nb(MD_CELL, md, RAW_CELL), fs.text(path))
    }

    @Test
    fun `键顺序不是字母序的 notebook，被改单元的键顺序也保持原样`() = runTest {
        val before = "{\n \"nbformat\": 4,\n \"nbformat_minor\": 5,\n \"metadata\": {},\n \"cells\": [\n" +
            "  {\n   \"id\": \"c\",\n   \"source\": [\n    \"old\"\n   ],\n   \"cell_type\": \"markdown\",\n   \"metadata\": {}\n  }\n ]\n}\n"
        seed(before)
        edit("cell_id" to "c", "new_source" to "new")
        assertEquals(before.replace("\"old\"", "\"new\""), fs.text(path))
    }

    @Test
    fun `2 空格缩进的 notebook 保持 2 空格`() = runTest {
        seed(NB1.doubleIndent())
        edit("cell_id" to "raw00003", "new_source" to "Z")
        assertEquals(nb(MD_CELL, CODE_CELL, RAW_CELL.replace("raw text", "Z")).doubleIndent(), fs.text(path))
    }

    @Test
    fun `按 cell-序号 指定没有 id 的单元`() = runTest {
        val before = """{"cells":[{"cell_type":"markdown","metadata":{},"source":["a"]},{"cell_type":"markdown","metadata":{},"source":["b"]}],"metadata":{},"nbformat":4,"nbformat_minor":2}"""
        seed(before)
        val r = edit("cell_id" to "cell-1", "new_source" to "B")
        assertTrue(r.text, r.text.startsWith("已替换单元 cell-1（序号 1）。"))
        assertEquals(before.replace("[\"b\"]", "[\"B\"]"), fs.text(path))
    }

    @Test
    fun `真 id 优先于 cell-序号；id 重复时提示改用序号`() = runTest {
        val dup = """{"cells":[{"cell_type":"raw","id":"d","metadata":{},"source":["a"]},{"cell_type":"raw","id":"d","metadata":{},"source":["b"]}],"metadata":{},"nbformat":4,"nbformat_minor":5}"""
        seed(dup)
        val r = edit("cell_id" to "d", "new_source" to "x")
        assertTrue(r.isError)
        assertEquals("id `d` 在 notebook 里重复出现（序号 0、1），改用 `cell-<序号>` 指定，如 `cell-0`。", r.text)
        assertEquals(dup, fs.text(path))
        assertFalse(edit("cell_id" to "cell-1", "new_source" to "x").isError)
    }

    @Test
    fun `找不到 cell_id：列出现有 id，文件不动`() = runTest {
        seed(NB1)
        val r = edit("cell_id" to "nope", "new_source" to "x")
        assertTrue(r.isError)
        assertEquals("notebook 里没有 id 为 `nope` 的单元。现有单元：md000001、code0002、raw00003。先用 Read 看一遍再指定。", r.text)
        assertEquals(NB1, fs.text(path))
        assertTrue(fs.writes.isEmpty())
        assertTrue(edit("cell_id" to "cell-9", "new_source" to "x").isError)
    }

    // ---- insert ----

    private suspend fun insertAndMask(vararg pairs: Pair<String, Any?>): String {
        val r = edit("edit_mode" to "insert", *pairs)
        assertFalse(r.text, r.isError)
        return fs.text(path)!!.replace(newId(r), "NEWID000")
    }

    @Test
    fun `插到开头：不给 cell_id`() = runTest {
        seed(NB1)
        val text = insertAndMask("new_source" to "x = 1\nx", "cell_type" to "code")
        assertEquals(nb(NEW_CODE, MD_CELL, CODE_CELL, RAW_CELL), text)
    }

    @Test
    fun `插到中间：在 cell_id 之后`() = runTest {
        seed(NB1)
        val text = insertAndMask("cell_id" to "md000001", "new_source" to "x = 1\nx", "cell_type" to "code")
        assertEquals(nb(MD_CELL, NEW_CODE, CODE_CELL, RAW_CELL), text)
    }

    @Test
    fun `插到末尾：在最后一个单元之后`() = runTest {
        seed(NB1)
        val text = insertAndMask("cell_id" to "raw00003", "new_source" to "x = 1\nx", "cell_type" to "code")
        assertEquals(nb(MD_CELL, CODE_CELL, RAW_CELL, NEW_CODE), text)
    }

    @Test
    fun `插入 markdown 单元没有 outputs 与执行序号`() = runTest {
        seed(NB1)
        val text = insertAndMask("new_source" to "# 新", "cell_type" to "markdown")
        val md = "  {\n   \"cell_type\": \"markdown\",\n   \"id\": \"NEWID000\",\n   \"metadata\": {},\n   \"source\": [\n    \"# 新\"\n   ]\n  }"
        assertEquals(nb(md, MD_CELL, CODE_CELL, RAW_CELL), text)
    }

    @Test
    fun `插入空源码得到空数组`() = runTest {
        seed(NB1)
        val text = insertAndMask("new_source" to "", "cell_type" to "raw")
        val raw = "  {\n   \"cell_type\": \"raw\",\n   \"id\": \"NEWID000\",\n   \"metadata\": {},\n   \"source\": []\n  }"
        assertEquals(nb(raw, MD_CELL, CODE_CELL, RAW_CELL), text)
    }

    @Test
    fun `插进没有单元的 notebook`() = runTest {
        seed(nb())
        val text = insertAndMask("new_source" to "x = 1\nx", "cell_type" to "code")
        assertEquals(nb(NEW_CODE), text)
    }

    @Test
    fun `新单元 id 符合 nbformat 4_5：8 位十六进制，不与已有重复`() = runTest {
        seed(NB1)
        val r = edit("edit_mode" to "insert", "new_source" to "x", "cell_type" to "code")
        val id = newId(r)
        assertTrue(Regex("[a-zA-Z0-9_-]{1,64}").matches(id))
        assertEquals(8, id.length)
        assertTrue(r.text, r.text.contains("<cell id=\"$id\" index=\"0\" type=\"code\">"))
        assertEquals(1, Regex(id).findAll(fs.text(path)!!).count())
    }

    @Test
    fun `多次插入得到的 id 各不相同`() = runTest {
        seed(nb())
        val ids = mutableSetOf<String>()
        repeat(20) {
            val r = edit("edit_mode" to "insert", "new_source" to "x", "cell_type" to "raw")
            ids += newId(r)
            ctx.seen[GuestPath(path)] = r.touched.single()
        }
        assertEquals(20, ids.size)
    }

    @Test
    fun `nbformat 4_4 及更旧的 notebook 插入的单元不带 id`() = runTest {
        val before = "{\n \"cells\": [],\n \"metadata\": {},\n \"nbformat\": 4,\n \"nbformat_minor\": 4\n}\n"
        seed(before)
        val r = edit("edit_mode" to "insert", "new_source" to "a", "cell_type" to "markdown")
        assertFalse(r.text, r.isError)
        assertEquals(
            "{\n \"cells\": [\n  {\n   \"cell_type\": \"markdown\",\n   \"metadata\": {},\n   \"source\": [\n    \"a\"\n   ]\n  }\n ],\n \"metadata\": {},\n \"nbformat\": 4,\n \"nbformat_minor\": 4\n}\n",
            fs.text(path),
        )
        assertTrue(r.text, r.text.contains("<cell id=\"cell-0\" index=\"0\" type=\"markdown\">"))
        assertTrue(r.text, r.text.contains("没有 id"))
    }

    @Test
    fun `插入时找不到 cell_id 同样报错且不写`() = runTest {
        seed(NB1)
        val r = edit("edit_mode" to "insert", "cell_id" to "nope", "new_source" to "x", "cell_type" to "code")
        assertTrue(r.isError)
        assertTrue(r.text.startsWith("notebook 里没有 id 为 `nope` 的单元。"))
        assertTrue(fs.writes.isEmpty())
    }

    // ---- delete ----

    @Test
    fun `删第一个、中间、最后一个`() = runTest {
        seed(NB1)
        val r = edit("edit_mode" to "delete", "cell_id" to "md000001")
        assertEquals("已删除单元 md000001（原序号 0，类型 markdown）。现在共 2 个单元。", r.text)
        assertEquals(nb(CODE_CELL, RAW_CELL), fs.text(path))

        seed(NB1)
        edit("edit_mode" to "delete", "cell_id" to "code0002")
        assertEquals(nb(MD_CELL, RAW_CELL), fs.text(path))

        seed(NB1)
        edit("edit_mode" to "delete", "cell_id" to "raw00003")
        assertEquals(nb(MD_CELL, CODE_CELL), fs.text(path))
    }

    @Test
    fun `删掉唯一的单元得到空数组`() = runTest {
        seed(nb(MD_CELL))
        edit("edit_mode" to "delete", "cell_id" to "md000001")
        assertEquals(nb(), fs.text(path))
    }

    @Test
    fun `删除找不到的单元报错且不写`() = runTest {
        seed(NB1)
        val r = edit("edit_mode" to "delete", "cell_id" to "zzz")
        assertTrue(r.isError)
        assertTrue(fs.writes.isEmpty())
    }

    // ---- 排版与编码 ----

    @Test
    fun `压成一行的 notebook 保持压缩写法`() = runTest {
        val before = """{"cells":[{"cell_type":"markdown","id":"a","metadata":{},"source":["1"]},{"cell_type":"markdown","id":"b","metadata":{},"source":["2"]}],"metadata":{},"nbformat":4,"nbformat_minor":5}"""
        seed(before)
        edit("cell_id" to "a", "new_source" to "x\ny")
        assertEquals(before.replace("[\"1\"]", "[\"x\\n\",\"y\"]"), fs.text(path))

        seed(before)
        val r = edit("edit_mode" to "insert", "cell_id" to "a", "new_source" to "n", "cell_type" to "raw")
        val id = newId(r)
        assertEquals(
            before.replace("},{\"cell_type\":\"markdown\",\"id\":\"b\"", "},{\"cell_type\":\"raw\",\"id\":\"$id\",\"metadata\":{},\"source\":[\"n\"]},{\"cell_type\":\"markdown\",\"id\":\"b\""),
            fs.text(path),
        )

        seed(before)
        edit("edit_mode" to "delete", "cell_id" to "b")
        assertEquals(before.replace(",{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"2\"]}", ""), fs.text(path))
    }

    @Test
    fun `CRLF 换行与 BOM 原样保留`() = runTest {
        val crlf = "\uFEFF" + NB1.replace("\n", "\r\n")
        seed(crlf)
        edit("cell_id" to "raw00003", "new_source" to "Z")
        assertEquals("\uFEFF" + nb(MD_CELL, CODE_CELL, RAW_CELL.replace("raw text", "Z")).replace("\n", "\r\n"), fs.text(path))
    }

    @Test
    fun `ensure_ascii 写法的 notebook 新内容也转义，其余单元不动`() = runTest {
        val before = "{\"cells\":[{\"cell_type\":\"markdown\",\"id\":\"a\",\"metadata\":{},\"source\":[\"\\u6807\\u9898\"]},{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"x\"]}],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}"
        seed(before)
        edit("cell_id" to "b", "new_source" to "中\"\\\t\u0001")
        assertEquals(before.replace("[\"x\"]", "[\"\\u4e2d\\\"\\\\\\t\\u0001\"]"), fs.text(path))
    }

    @Test
    fun `文件里本来就有真实的非 ASCII 字符，就不按 ensure_ascii 转义（哪怕别处有 u 转义）`() = runTest {
        val before = "{\"cells\":[{\"cell_type\":\"markdown\",\"id\":\"a\",\"metadata\":{},\"source\":[\"标题\"]},{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"\\ud83d\\ude00\"]}],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}"
        seed(before)
        edit("cell_id" to "a", "new_source" to "中")
        assertEquals(before.replace("[\"标题\"]", "[\"中\"]"), fs.text(path))
    }

    @Test
    fun `metadata 里的数字字面保持原样（不被重写成别的写法）`() = runTest {
        val before = "{\n \"cells\": [\n  {\n   \"cell_type\": \"raw\",\n   \"id\": \"a\",\n   \"metadata\": {\n    \"t\": 1.0,\n    \"u\": 1e-05,\n    \"v\": 100000000000000000000\n   },\n   \"source\": [\n    \"s\"\n   ]\n  }\n ],\n \"metadata\": {},\n \"nbformat\": 4,\n \"nbformat_minor\": 5\n}\n"
        seed(before)
        edit("cell_id" to "a", "new_source" to "t")
        assertEquals(before.replace("\"s\"", "\"t\""), fs.text(path))
    }

    @Test
    fun `其余单元里的大输出与内嵌图片一个字节都不动`() = runTest {
        val big = "A".repeat(100_000)
        val heavy = CODE_CELL.replace("\"hi\\n\"", "\"hi\\n\",\n      \"$big\"")
        seed(nb(MD_CELL, heavy))
        edit("cell_id" to "md000001", "new_source" to "只改这个")
        assertEquals(nb(MD_CELL.replace("    \"# 标题\\n\",\n    \"正文\"\n", "    \"只改这个\"\n"), heavy), fs.text(path))
    }

    // ---- 坏文件 ----

    @Test
    fun `坏 JSON 拒绝，不写`() = runTest {
        seed("{\"cells\": [")
        val r = edit("cell_id" to "a", "new_source" to "x")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("不是合法的 JSON"))
        assertTrue(fs.writes.isEmpty())
    }

    @Test
    fun `超过 10MB 的 notebook 拒绝`() = runTest {
        seed(String(CharArray(10 * 1024 * 1024 + 1) { 'a' }))
        val r = edit("cell_id" to "a", "new_source" to "x")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("超过 NotebookEdit 能处理的"))
    }

    @Test
    fun `结果与原文件相比，改动只落在目标单元（旧值不再出现）`() = runTest {
        seed(NB1)
        val r = edit("cell_id" to "code0002", "new_source" to "z")
        assertFalse(r.isError)
        val after = fs.text(path)!!
        assertNotEquals(NB1, after)
        assertFalse(after.contains("hi"))
        assertTrue(after.startsWith(NB_HEAD + MD_CELL + ",\n"))
        assertTrue(after.endsWith(",\n" + RAW_CELL + "\n ],\n" + NB_TAIL))
    }
}
