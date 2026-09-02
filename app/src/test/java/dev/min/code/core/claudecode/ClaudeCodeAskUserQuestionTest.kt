package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.min.code.ui.session.composeAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AskUserQuestion。
 *
 * 这个工具走的是**和权限确认同一个** `can_use_tool` 帧，但语义完全不同：要的是"在选项里挑"，
 * 答案还必须经 `updatedInput.answers` 回传。之前统一按权限面板渲染，用户看到的是一对
 * allow/deny 按钮、根本没有选项可点，模型收到的就是一句"没收到选择"。
 */
class ClaudeCodeAskUserQuestionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun input(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    private val sample = input(
        """
        {"questions":[
          {"question":"要装哪个 LaTeX 发行版？","header":"LaTeX","multiSelect":false,
           "options":[
             {"label":"完整版","description":"约 5GB"},
             {"label":"中等版","description":"含中文支持，约 1GB"}]},
          {"question":"还要装什么？","header":"其他","multiSelect":true,
           "options":[{"label":"R"},{"label":"pandoc"}]}]}
        """
    )

    // --- 解析 ---

    /**
     * 入参是复数 `questions` **数组**，不是单个 `question` 字段。
     * 读错字段的代价是折叠态永远只显示兜底文案、面板里一个选项都渲染不出来。
     */
    @Test
    fun `questions are parsed from the plural array`() {
        val questions = parseAskUserQuestions(sample)
        assertEquals(2, questions.size)

        val first = questions[0]
        assertEquals("要装哪个 LaTeX 发行版？", first.question)
        assertEquals("LaTeX", first.header)
        assertFalse(first.multiSelect)
        assertEquals(listOf("完整版", "中等版"), first.options.map { it.label })
        assertEquals("约 5GB", first.options[0].description)

        assertTrue(questions[1].multiSelect)
    }

    @Test
    fun `option preview is kept for comparison`() {
        val questions = parseAskUserQuestions(
            input("""{"questions":[{"question":"选版式","options":[
                {"label":"A","preview":"+---+\n| x |\n+---+"},{"label":"B"}]}]}""")
        )
        assertEquals("+---+\n| x |\n+---+", questions[0].options[0].preview)
        assertEquals(null, questions[0].options[1].preview)
    }

    /** 字段缺失、形状不符都必须降级成空表，绝不能抛——抛出去会把整个读取循环打断 */
    @Test
    fun `malformed input degrades to an empty list`() {
        assertTrue(parseAskUserQuestions(input("{}")).isEmpty())
        assertTrue(parseAskUserQuestions(input("""{"questions":"nope"}""")).isEmpty())
        assertTrue(parseAskUserQuestions(input("""{"questions":[{"header":"没有问题正文"}]}""")).isEmpty())
        assertTrue(parseAskUserQuestions(input("""{"question":"单数字段不认"}""")).isEmpty())
    }

    /** 没有 label 的选项直接丢掉，不能渲染成一个点不出名堂的空按钮 */
    @Test
    fun `options without a label are dropped`() {
        val questions = parseAskUserQuestions(
            input("""{"questions":[{"question":"q","options":[{"label":"ok"},{"description":"无 label"}]}]}""")
        )
        assertEquals(listOf("ok"), questions[0].options.map { it.label })
    }

    // --- 回传 ---

    /**
     * 答案必须**在原始入参之上**增补。CLI 会拿 updatedInput 重新跑一遍工具入参的 schema 校验，
     * 只回一个 `{answers:...}` 会因为缺 `questions` 被判不合法。
     */
    @Test
    fun `the answer keeps the original input and adds answers`() {
        val updated = buildAskUserQuestionAnswer(
            sample,
            mapOf("要装哪个 LaTeX 发行版？" to "中等版", "还要装什么？" to "R, pandoc"),
        )
        assertTrue("questions 必须原样留着", updated.containsKey("questions"))
        assertEquals(
            "中等版",
            updated["answers"]!!.jsonObject["要装哪个 LaTeX 发行版？"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "R, pandoc",
            updated["answers"]!!.jsonObject["还要装什么？"]!!.jsonPrimitive.content,
        )
    }

    /** 入参里已经带了 answers（重放场景）时用新的覆盖，不能出现两个 answers */
    @Test
    fun `an existing answers field is replaced not duplicated`() {
        val withOld = input("""{"questions":[],"answers":{"a":"old"}}""")
        val updated = buildAskUserQuestionAnswer(withOld, mapOf("a" to "new"))
        assertEquals("new", updated["answers"]!!.jsonObject["a"]!!.jsonPrimitive.content)
    }

    /** allow 应答里要真的带上 updatedInput，否则选择根本传不回去 */
    @Test
    fun `the permission response carries updated input`() {
        val line = encodeClaudeCodePermissionResponse(
            requestId = "req-1",
            allow = true,
            updatedInput = buildAskUserQuestionAnswer(sample, mapOf("q" to "a")),
        )
        val result = json.parseToJsonElement(line).jsonObject["response"]!!
            .jsonObject["response"]!!.jsonObject
        assertEquals("allow", result["behavior"]!!.jsonPrimitive.content)
        assertEquals("a", result["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["q"]!!.jsonPrimitive.content)
    }

    /** 普通权限确认不带 updatedInput —— CLI 会拿它重跑 schema 校验，多一次失败机会 */
    @Test
    fun `a plain approval still omits updated input`() {
        val line = encodeClaudeCodePermissionResponse(requestId = "req-1", allow = true)
        val result = json.parseToJsonElement(line).jsonObject["response"]!!
            .jsonObject["response"]!!.jsonObject
        assertFalse(result.containsKey("updatedInput"))
    }

    // --- 无人应答的识别 ---

    /**
     * 选择没能传回 CLI 时，工具会拿空答案跑完。认出这个形状才能给用户一句有用的话，
     * 而不是让他盯着模型自说自话地"没收到选择"。
     */
    @Test
    fun `an empty auto resolved answer is detected`() {
        assertTrue(ClaudeCodeManager.looksLikeUnansweredQuestion("User has answered your questions: ."))
        assertTrue(ClaudeCodeManager.looksLikeUnansweredQuestion("User has answered your questions:"))
        assertTrue(ClaudeCodeManager.looksLikeUnansweredQuestion("  "))
    }

    /** 真的有答案时绝不能误报——误报会在每次正常提问后都插一条吓人的红字 */
    @Test
    fun `a real answer is not flagged`() {
        assertFalse(
            ClaudeCodeManager.looksLikeUnansweredQuestion("User has answered your questions: 中等版")
        )
        assertFalse(ClaudeCodeManager.looksLikeUnansweredQuestion("some other tool output"))
    }

    // --- 「其他」和预置项的共存 ---
    //
    // 「其他」是**多出来的一个选项**，只不过内容由用户填，不是"另一种输入模式"。
    // 第一版把两者做成互斥的，结果是：点一下输入框，已勾的选项全没了；
    // 再勾回选项，打好的字又被清空。下面这组用例把这个语义钉死。

    private val multiQ = ClaudeCodeQuestion(
        question = "还要装什么？",
        multiSelect = true,
        options = listOf(
            ClaudeCodeQuestionOption("R"),
            ClaudeCodeQuestionOption("pandoc"),
        ),
    )

    private val singleQ = ClaudeCodeQuestion(
        question = "要装哪个 LaTeX？",
        multiSelect = false,
        options = listOf(
            ClaudeCodeQuestionOption("完整版"),
            ClaudeCodeQuestionOption("中等版"),
        ),
    )

    @Test
    fun `multi select combines presets with the custom answer`() {
        assertEquals(
            "R, pandoc, Stata",
            composeAnswer(multiQ, setOf("R", "pandoc"), customChecked = true, customText = "Stata"),
        )
    }

    /** 只勾预置项时「其他」的文本必须被忽略，而不是混进答案里 */
    @Test
    fun `unchecked custom text is ignored`() {
        assertEquals(
            "R",
            composeAnswer(multiQ, setOf("R"), customChecked = false, customText = "打了一半的字"),
        )
    }

    /** 只勾「其他」时就只回它自己 */
    @Test
    fun `custom only answer stands alone`() {
        assertEquals(
            "Stata",
            composeAnswer(multiQ, emptySet(), customChecked = true, customText = "Stata"),
        )
    }

    /** 连接顺序按**选项原始顺序**，不按勾选顺序 —— 同样的选择每次都要产生同样的字符串 */
    @Test
    fun `parts are joined in the original option order`() {
        assertEquals(
            "R, pandoc",
            composeAnswer(multiQ, setOf("pandoc", "R"), customChecked = false, customText = ""),
        )
    }

    /** 勾了「其他」却没填字 = 这一问没答完，返回空串好让提交按钮保持禁用 */
    @Test
    fun `checked but empty custom blocks submission`() {
        assertEquals("", composeAnswer(multiQ, setOf("R"), customChecked = true, customText = ""))
        assertEquals("", composeAnswer(multiQ, setOf("R"), customChecked = true, customText = "   "))
    }

    @Test
    fun `single select returns exactly one value`() {
        assertEquals(
            "中等版",
            composeAnswer(singleQ, setOf("中等版"), customChecked = false, customText = ""),
        )
        assertEquals(
            "TeX Live 完整",
            composeAnswer(singleQ, emptySet(), customChecked = true, customText = "TeX Live 完整"),
        )
    }

    @Test
    fun `nothing chosen yields an empty answer`() {
        assertEquals("", composeAnswer(singleQ, emptySet(), customChecked = false, customText = ""))
    }
}
