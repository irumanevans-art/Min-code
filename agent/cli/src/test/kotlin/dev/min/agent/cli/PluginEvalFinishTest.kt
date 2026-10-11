package dev.min.agent.cli

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files

class PluginEvalFinishTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `JS 空反向引用和 ASCII digit 按原生语义评分`() = runBlocking {
        assertTrue("JS 未参与捕获的反向引用匹配空串", evalRegex("(a|(b))\\2c").containsMatchIn("ac"))
        assertFalse("JS digit 不含非 ASCII 数字", evalRegex("\\d", "u").containsMatchIn("١"))
    }
    @Test fun `JS 命名捕获 lookbehind 属性集 sticky 和零宽 Unicode 计数`() {
        assertTrue(evalRegex("(?<value>a)\\k<value>").containsMatchIn("aa"))
        assertTrue(evalRegex("(?<=a)b").containsMatchIn("ab"))
        assertTrue(evalRegex("\\p{Script=Greek}+", "u").containsMatchIn("αβ"))
        assertFalse(evalRegex("b", "y").containsMatchIn("ab"))
        assertEquals(2, evalRegex(".", "u").findAll("😀a").count())
        assertEquals(3, evalRegex("(?:)", "u").findAll("😀a").count())
        for ((pattern, flags) in listOf("a" to "ii", "(?i)a" to "", "a" to "z")) {
            var refused = false; try { evalRegex(pattern, flags) } catch(_: Exception) { refused = true }; assertTrue(refused)
        }
    }
}
