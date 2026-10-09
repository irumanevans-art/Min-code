package dev.min.agent.tools

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionOption
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.min.agent.testkit.*

class AskUserQuestionToolTest {
    private val tool = AskUserQuestionTool

    private fun option(label: String, description: String = "$label 的说明") =
        JsonObject(mapOf("label" to JsonPrimitive(label), "description" to JsonPrimitive(description)))

    private fun question(
        text: String,
        header: String? = "标签",
        options: List<JsonElement> = listOf(option("甲"), option("乙")),
        multiSelect: Boolean? = false,
    ): JsonObject = JsonObject(
        buildMap {
            put("question", JsonPrimitive(text))
            header?.let { put("header", JsonPrimitive(it)) }
            put("options", JsonArray(options))
            multiSelect?.let { put("multiSelect", JsonPrimitive(it)) }
        },
    )

    private fun questions(vararg q: JsonObject) = "questions" to JsonArray(q.toList())

    private val db = UserQuestion(
        "用哪个数据库？", "数据库",
        listOf(QuestionOption("PostgreSQL", "关系型"), QuestionOption("SQLite", "单文件")),
    )
    private val modules = UserQuestion(
        "要哪些模块？", "模块",
        listOf(QuestionOption("登录", "账号体系"), QuestionOption("支付", "收款"), QuestionOption("报表", "导出")),
        multiSelect = true,
    )

    @Test
    fun `parses questions in the Claude Code shape`() {
        val input = tool.parsed(
            questions(
                question("用哪个数据库？", "数据库", listOf(option("PostgreSQL", "关系型"), option("SQLite", "单文件"))),
                question(
                    "要哪些模块？", "模块",
                    listOf(option("登录", "账号体系"), option("支付", "收款"), option("报表", "导出")),
                    multiSelect = true,
                ),
            ),
        )
        assertEquals(listOf(db, modules), input.questions)
    }

    @Test
    fun `rejects wrong counts with the position and the limit`() {
        assertTrue(tool.reject().contains("questions"))
        assertTrue(tool.reject(questions()).contains("1–4"))
        val five = (1..5).map { question("问题 $it？") }.toTypedArray()
        assertTrue(tool.reject(questions(*five)).contains("收到 5 个"))
        val one = tool.reject(questions(question("只有一个选项？", options = listOf(option("甲")))))
        assertTrue(one, one.startsWith("questions[0]") && one.contains("2–4"))
        val many = tool.reject(questions(question("一？"), question("选项太多？", options = (1..5).map { option("选$it") })))
        assertTrue(many, many.startsWith("questions[1]") && many.contains("收到 5 个"))
    }

    @Test
    fun `rejects malformed fields and duplicates`() {
        assertTrue(tool.reject(questions(question(" "))).contains("`question` 不能为空"))
        val badOption = tool.reject(questions(question("一？", options = listOf(option("甲"), JsonPrimitive("乙")))))
        assertTrue(badOption, badOption.startsWith("questions[0]：options[1]"))
        val blankLabel = tool.reject(questions(question("一？", options = listOf(option("甲"), option("  ")))))
        assertTrue(blankLabel, blankLabel.contains("`label` 不能为空"))
        assertTrue(tool.reject(questions(question("一？", options = listOf(option("甲"), option("甲"))))).contains("选项名不能重复"))
        assertTrue(tool.reject(questions(question("一？"), question("一？"))).contains("问题不能重复"))
        assertTrue(tool.reject("questions" to JsonPrimitive("不是数组")).contains("必须是数组"))
    }

    @Test
    fun `is lenient where leniency costs nothing`() {
        // multiSelect 缺省为单选；header 缺省用问题开头；整个数组被写成 JSON 字符串也认
        val q = tool.parsed(questions(question("要不要加缓存？", header = null, multiSelect = null))).questions.single()
        assertFalse(q.multiSelect)
        assertTrue(q.header.isNotBlank())
        val stringified = JsonArray(listOf(question("一？"))).toString()
        assertEquals(1, tool.parsed("questions" to JsonPrimitive(stringified)).questions.size)
    }

    @Test
    fun `answers come back one line per question`() = runBlocking {
        val ctx = TestToolContext()
        ctx.answer = {
            QuestionOutcome.Answered(
                listOf(QuestionAnswer(listOf("PostgreSQL")), QuestionAnswer(listOf("登录", "支付"), other = "还要导出 CSV")),
            )
        }
        val result = tool.call(
            ctx,
            questions(
                question("用哪个数据库？", "数据库", listOf(option("PostgreSQL", "关系型"), option("SQLite", "单文件"))),
                question("要哪些模块？", "模块", listOf(option("登录", "账号体系"), option("支付", "收款"), option("报表", "导出")), true),
            ),
        )
        assertFalse(result.isError)
        assertEquals(listOf(listOf(db, modules)), ctx.asked)
        // 答案是用户说的话：在 userText 里（选项与自填都在），工具结果文本只有一句事实
        assertEquals(
            "用户回答了你的问题：\n" +
                "- 用哪个数据库？ → PostgreSQL\n" +
                "- 要哪些模块？ → 登录、支付；（用户自己填写）还要导出 CSV\n" +
                "请按用户的回答继续。",
            result.userText,
        )
        assertEquals(AskUserQuestionTool.ANSWERED_MESSAGE, result.text)
        listOf("PostgreSQL", "登录", "支付", "还要导出 CSV").forEach {
            assertFalse("工具结果文本里不该有用户的回答「$it」：${result.text}", result.text.contains(it))
        }
    }

    @Test
    fun `a picked option alone and a free-text answer alone both stay out of the result text`() = runBlocking {
        val picked = TestToolContext().apply { answer = { QuestionOutcome.Answered(listOf(QuestionAnswer(listOf("SQLite")))) } }
            .let { tool.call(it, questions(question("用哪个数据库？", "数据库", listOf(option("PostgreSQL", "关系型"), option("SQLite", "单文件"))))) }
        assertTrue(picked.userText, picked.userText!!.contains("用哪个数据库？ → SQLite"))
        assertFalse(picked.text.contains("SQLite"))

        val typed = TestToolContext().apply { answer = { QuestionOutcome.Answered(listOf(QuestionAnswer(other = "先用内存存储"))) } }
            .let { tool.call(it, questions(question("用哪个数据库？", "数据库", listOf(option("PostgreSQL", "关系型"), option("SQLite", "单文件"))))) }
        assertTrue(typed.userText, typed.userText!!.contains("用哪个数据库？ → （用户自己填写）先用内存存储"))
        assertFalse(typed.text.contains("先用内存存储"))
    }

    @Test
    fun `a free-text answer alone is passed through`() {
        val text = tool.formatAnswers(listOf(db), listOf(QuestionAnswer(other = "先用内存存储")))
        assertTrue(text, text.contains("用哪个数据库？ → （用户自己填写）先用内存存储"))
    }

    @Test
    fun `a declined question is not an error and tells the model how to go on`() = runBlocking {
        val ctx = TestToolContext()
        ctx.answer = { QuestionOutcome.Declined }
        val result = tool.call(ctx, questions(question("一？")))
        assertFalse(result.isError)
        assertEquals(AskUserQuestionTool.DECLINED_MESSAGE, result.text)
        assertNull(result.userText)
        assertTrue(result.text.contains("没有回答"))
    }

    @Test
    fun `dontAsk mode never bothers the user`() = runBlocking {
        val ctx = TestToolContext()
        ctx.mode = PermissionMode.DontAsk
        val result = tool.call(ctx, questions(question("一？")))
        assertFalse(result.isError)
        assertTrue(ctx.asked.isEmpty())
        assertEquals(AskUserQuestionTool.DONT_ASK_MESSAGE, result.text)
    }

    @Test
    fun `traits and permission summary`() {
        val traits = tool.traits
        assertTrue(traits.readOnly)
        assertFalse("一次只问一组", traits.concurrencySafe)
        assertEquals(ResumePolicy.Replay, traits.resumePolicy)
        val ask = tool.permissionAsk(tool.parsed(questions(question("用哪个数据库？"), question("二？"))), TestToolContext())
        assertEquals("AskUserQuestion", ask.tool)
        assertEquals("向你提问：用哪个数据库？（共 2 个问题）", ask.summary)
        assertTrue(ask.paths.isEmpty() && ask.commands.isEmpty() && ask.domains.isEmpty())
    }

    @Test
    fun `schema keeps to the shared subset and describes every field`() {
        val text = tool.inputSchema.toString()
        assertFalse(text.contains("oneOf") || text.contains("anyOf") || text.contains("\$ref"))
        val items = ((tool.inputSchema["properties"] as JsonObject)["questions"] as JsonObject)["items"] as JsonObject
        val props = items["properties"] as JsonObject
        assertEquals(setOf("question", "header", "options", "multiSelect"), props.keys)
        props.values.forEach { assertTrue((it as JsonObject).containsKey("description")) }
        assertTrue(tool.description(HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)).contains("ExitPlanMode"))
    }
}
