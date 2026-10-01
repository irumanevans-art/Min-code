package dev.min.agent.tools

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionOption
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AskUserQuestionInput(val questions: List<UserQuestion>)

/**
 * 向用户提选择题，澄清需求或在几种做法之间做决定（与 Claude Code 的 AskUserQuestion 同名同形）。
 *
 * 提问本身就是在问人，所以不走审批：只读，任何模式下权限引擎都直接放行，[permissionAsk] 只给一句摘要。
 * 等答复靠 [ToolContext.ask]——持久请求，进程被杀后恢复逻辑按 Replay 给这次调用补「没拿到结果、可以重新调用」，
 * 模型需要时重新问。一次只问一组（不可并发）：两张提问卡片同时弹出，人分不清先答哪个。
 */
object AskUserQuestionTool : Tool<AskUserQuestionInput> {
    override val name = "AskUserQuestion"

    const val MAX_QUESTIONS = 4
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    const val DECLINED_MESSAGE =
        "用户没有回答这些问题（关掉了提问）。请按现有信息自行判断后继续；确实需要用户决定的，换个问法再问，不要原样重复同样的问题。"

    const val DONT_ASK_MESSAGE =
        "当前是不询问用户的模式（dontAsk），没有向用户提问。请按现有信息自行判断后继续，并在最后说明你做了哪些假设。"

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("questions"))))
        put(
            "properties",
            buildJsonObject {
                put(
                    "questions",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "要问的问题，1–$MAX_QUESTIONS 个")
                        put("items", questionSchema())
                    },
                )
            },
        )
    }

    private fun questionSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf("question", "header", "options", "multiSelect").map(::JsonPrimitive)))
        put(
            "properties",
            buildJsonObject {
                put("question", prop("string", "完整的问题，清楚具体，以问号结尾"))
                put("header", prop("string", "界面上显示的短标签，不超过 12 个字符，如「数据库」「认证方式」"))
                put(
                    "options",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "$MIN_OPTIONS–$MAX_OPTIONS 个互斥的选项；不要加「其他」，界面会自动提供")
                        put(
                            "items",
                            schemaObject(
                                listOf("label", "description"),
                                "label" to prop("string", "选项名，简短（1–5 个词）"),
                                "description" to prop("string", "选了这个意味着什么、有什么取舍"),
                            ),
                        )
                    },
                )
                put("multiSelect", prop("boolean", "true 表示可以多选（选项之间不互斥时用）"))
            },
        )
    }

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "向用户提 1–$MAX_QUESTIONS 个选择题并等待回答。用来澄清含糊的需求、确认用户的偏好，或在几种可行做法之间让用户拍板；" +
            "能自己查到的事实不要问。每题给 $MIN_OPTIONS–$MAX_OPTIONS 个选项（label 简短，description 说明取舍），" +
            "用户总能自己填「其他」，不要自己加这个选项；选项可以同时选多个时把 multiSelect 设为 true。" +
            "想推荐某个选项就把它放在第一个，并在 label 末尾加「（推荐）」。" +
            "在计划模式里用它在定稿前澄清需求；不要用它问「计划行不行」，计划请用 ExitPlanMode 交给用户批准。"

    override fun parse(raw: JsonObject): ToolInput<AskUserQuestionInput> = parseArgs {
        val list = questionsArray(raw)
        if (list.size !in 1..MAX_QUESTIONS) throw BadArg("`questions` 要有 1–$MAX_QUESTIONS 个问题，收到 ${list.size} 个。")
        val questions = list.mapIndexed { i, e -> within("questions[$i]") { question(e) } }
        val repeated = questions.groupBy { it.question }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) throw BadArg("问题不能重复：「${repeated.first()}」出现了不止一次。")
        AskUserQuestionInput(questions)
    }

    /** 有的模型会把数组整个写成 JSON 字符串；能解析就宽容接受，省一个来回 */
    private fun questionsArray(raw: JsonObject): JsonArray {
        val e = raw["questions"] ?: throw BadArg("缺少必填字段 `questions`（数组，1–$MAX_QUESTIONS 个问题）。")
        if (e is JsonArray) return e
        val text = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        val parsed = text?.let {
            try {
                AgentJson.parseToJsonElement(it)
            } catch (_: SerializationException) {
                null
            }
        }
        return parsed as? JsonArray ?: throw BadArg("字段 `questions` 必须是数组，每个元素是一个问题对象。")
    }

    private fun question(e: JsonElement): UserQuestion {
        val obj = e as? JsonObject ?: throw BadArg("每个问题必须是对象（含 question、header、options、multiSelect）。")
        val question = obj.reqString("question").trim()
        if (question.isEmpty()) throw BadArg("字段 `question` 不能为空。")
        // header 只是界面标签：超长不拒绝（界面自己截），空的拿问题开头顶上，不为它多要一个来回
        val header = obj.optString("header")?.trim()?.takeIf { it.isNotEmpty() } ?: question.clip(12)
        val rawOptions = obj["options"] as? JsonArray
            ?: throw BadArg("缺少必填字段 `options`（数组，$MIN_OPTIONS–$MAX_OPTIONS 个选项）。")
        if (rawOptions.size !in MIN_OPTIONS..MAX_OPTIONS) {
            throw BadArg("`options` 要有 $MIN_OPTIONS–$MAX_OPTIONS 个选项，收到 ${rawOptions.size} 个。")
        }
        val options = rawOptions.mapIndexed { j, o -> within("options[$j]") { option(o) } }
        val repeated = options.groupBy { it.label }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) throw BadArg("同一题的选项名不能重复：「${repeated.first()}」出现了不止一次。")
        return UserQuestion(question, header, options, multiSelect = obj.optBool("multiSelect") ?: false)
    }

    private fun option(e: JsonElement): QuestionOption {
        val obj = e as? JsonObject ?: throw BadArg("每个选项必须是对象（含 label、description）。")
        val label = obj.reqString("label").trim()
        if (label.isEmpty()) throw BadArg("字段 `label` 不能为空。")
        return QuestionOption(label, obj.optString("description")?.trim().orEmpty())
    }

    /** 嵌套入参的错误带上位置，模型才知道改哪一个 */
    private inline fun <T> within(where: String, block: () -> T): T =
        try {
            block()
        } catch (e: BadArg) {
            throw BadArg("$where：${e.message}")
        }

    override fun permissionAsk(input: AskUserQuestionInput, ctx: ToolContext): PermissionAsk {
        val first = input.questions.first()
        val more = if (input.questions.size > 1) "（共 ${input.questions.size} 个问题）" else ""
        return PermissionAsk(tool = name, summary = "向你提问：${first.question.clip(80)}$more")
    }

    override fun run(input: AskUserQuestionInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        // dontAsk 的约定是「会打扰人的一律不做」：在这里等一个不会来的答复只会把无人值守的会话挂死
        if (ctx.mode == PermissionMode.DontAsk) return@doneFlow okResult(DONT_ASK_MESSAGE)
        when (val outcome = ctx.ask(input.questions)) {
            is QuestionOutcome.Answered -> okResult(formatAnswers(input.questions, outcome.answers))
            QuestionOutcome.Declined -> okResult(DECLINED_MESSAGE)
        }
    }

    /** 给模型的话：每题一行「问题 → 选择」，用户自己写的话原样带上并标明出处 */
    fun formatAnswers(questions: List<UserQuestion>, answers: List<QuestionAnswer>): String = buildString {
        append("用户回答了你的问题：\n")
        questions.zip(answers).forEach { (q, a) ->
            val picked = a.selected.joinToString("、").takeIf { it.isNotEmpty() }
            val other = a.other?.trim()?.takeIf { it.isNotEmpty() }?.let { "（用户自己填写）$it" }
            append("- ").append(q.question).append(" → ").append(listOfNotNull(picked, other).joinToString("；")).append('\n')
        }
        append("请按用户的回答继续。")
    }
}
