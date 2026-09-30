package dev.min.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditMatchTest {
    private fun apply(content: String, found: Located.Found) =
        content.substring(0, found.start) + found.replacement + content.substring(found.end)

    @Test
    fun `逐字相同且唯一时直接替换`() {
        val content = "a\nb = 1\nc\n"
        val found = EditMatch.locate(content, "b = 1", "b = 2") as Located.Found
        assertEquals(MatchStrategy.Exact, found.strategy)
        assertEquals("a\nb = 2\nc\n", apply(content, found))
    }

    @Test
    fun `逐字相同但出现多处时报出每处的行号，不猜`() {
        val located = EditMatch.locate("x()\ny\nx()\n", "x()", "z()")
        assertEquals(Located.Ambiguous(listOf(1, 3), MatchStrategy.Exact), located)
    }

    @Test
    fun `行尾空白不同照样能匹配`() {
        val content = "val a = 1   \nval b = 2\t\nval c = 3\n"
        val found = EditMatch.locate(content, "val a = 1\nval b = 2", "val a = 10\nval b = 20") as Located.Found
        assertEquals(MatchStrategy.TrailingWhitespace, found.strategy)
        assertEquals("val a = 10\nval b = 20\nval c = 3\n", apply(content, found))
    }

    @Test
    fun `缩进整体不同时匹配，并把替换内容改成文件原有的缩进`() {
        val content = "class A {\n    fun f() {\n        x()\n    }\n}\n"
        // 模型抄丢了外层 4 个空格
        val old = "fun f() {\n    x()\n}"
        val new = "fun f() {\n    y()\n    z()\n}"
        val found = EditMatch.locate(content, old, new) as Located.Found
        assertEquals(MatchStrategy.Indentation, found.strategy)
        assertEquals("class A {\n    fun f() {\n        y()\n        z()\n    }\n}\n", apply(content, found))
    }

    @Test
    fun `弯引号与破折号的写法不同也能定位，长度不变所以下标可直接用`() {
        val content = "print(“hi”) — done\nnext\n"
        val found = EditMatch.locate(content, "print(\"hi\") - done", "print(\"bye\")") as Located.Found
        assertEquals(MatchStrategy.Punctuation, found.strategy)
        assertEquals("print(\"bye\")\nnext\n", apply(content, found))
    }

    @Test
    fun `放宽之后仍有多处匹配时也停下`() {
        val content = "  a()\n  b()\n\n    a()\n    b()\n"
        val located = EditMatch.locate(content, "a()\nb()", "c()")
        assertEquals(Located.Ambiguous(listOf(1, 4), MatchStrategy.Indentation), located)
    }

    @Test
    fun `以换行结尾的 old_string 连同那一行的换行一起替换`() {
        val content = "keep\n  drop me  \nkeep too\n"
        val found = EditMatch.locate(content, "drop me\n", "") as Located.Found
        assertEquals("keep\nkeep too\n", apply(content, found))
    }

    @Test
    fun `哪一级都没找到时，给出与第一行相似的行号`() {
        val content = "a\nb\nfun compute() {\n    return 2\n}\n"
        val located = EditMatch.locate(content, "fun compute() {\n    return 1\n}", "x")
        assertEquals(Located.NotFound(listOf(3)), located)
        assertEquals(Located.NotFound(emptyList()), EditMatch.locate(content, "nothing like it", "x"))
    }

    @Test
    fun `replace_all 只数逐字相同的出现`() {
        assertEquals(listOf(0, 6), EditMatch.locateAll("foo()\nfoo()\n  foo ()\n", "foo()"))
        assertTrue(EditMatch.locateAll("abc", "x").isEmpty())
    }

    @Test
    fun `行号从 1 起`() {
        assertEquals(1, EditMatch.lineOf("abc", 0))
        assertEquals(3, EditMatch.lineOf("a\nb\nc", 4))
    }
}
