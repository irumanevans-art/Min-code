package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * ReportFindings：`/code-review` 把审查结果交给宿主的结构化列表（Claude Code 的 code-review 页：宿主请求时用它代替正文里的列表）。
 * 每条发现：file、一句话 summary、failure_scenario（什么情况下会出错）必填；line、category、severity 可选。
 * severity 对照 Code Review 的三种标记：important（合并前应修）、nit（小问题）、pre_existing（既有问题、非本次引入）。
 *
 * 工具本身不碰工作区、不声明任何会话效果：结果只是交上来的一份清单，宿主用 [ReportFindings.rows] 把它排成卡片。
 * 为什么不让模型把结果写成正文：卡片需要结构（文件、行、严重度），正文里的列表没法可靠地拆回来。
 */
enum class FindingSeverity(val wire: String) {
    Important("important"),
    Nit("nit"),
    PreExisting("pre_existing"),
    ;

    companion object {
        fun of(wire: String): FindingSeverity? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一条发现。[line] 为空表示只知道文件；[category] 如 `correctness` */
data class Finding(
    val file: String,
    val summary: String,
    val failureScenario: String,
    val line: Int? = null,
    val category: String? = null,
    val severity: FindingSeverity? = null,
)

/** 卡片的一行：位置文字（`文件:行`，没有行号就只有文件）加上其余字段，卡片的中英文标签由 App 决定 */
data class FindingRow(
    val location: String,
    val summary: String,
    val failureScenario: String,
    val category: String?,
    val severity: FindingSeverity?,
)

object ReportFindings {
    /** 把已通过校验的入参转成发现列表；入参不合格时抛 [InvalidSubagentInput]（与工具的 parse 用同一套规则） */
    fun from(raw: JsonObject): List<Finding> {
        val list = raw["findings"] as? JsonArray ?: throw InvalidSubagentInput("字段 `findings` 必须是非空数组。")
        if (list.isEmpty()) throw InvalidSubagentInput("字段 `findings` 必须至少有一条。")
        return list.mapIndexed { i, el -> finding(el as? JsonObject ?: throw InvalidSubagentInput("第 ${i + 1} 条必须是对象。"), i) }
    }

    /** 同 [from]，入参不合格返回 null：渲染层拿到坏数据要退回原样显示，不能抛异常 */
    fun fromOrNull(raw: JsonObject): List<Finding>? = try {
        from(raw)
    } catch (_: InvalidSubagentInput) {
        null
    }

    /** 卡片行：位置、说明、失败场景、分类、严重度 */
    fun rows(findings: List<Finding>): List<FindingRow> = findings.map { f ->
        FindingRow(
            location = f.line?.let { "${f.file}:$it" } ?: f.file,
            summary = f.summary,
            failureScenario = f.failureScenario,
            category = f.category,
            severity = f.severity,
        )
    }

    /** 给模型看的结果：一共几条、每条一行（位置、说明、分类与严重度） */
    fun modelText(findings: List<Finding>): String = buildString {
        append("已报告 ${findings.size} 条发现：")
        rows(findings).forEachIndexed { i, r ->
            val tags = listOfNotNull(r.category, r.severity?.wire).joinToString("，")
            append("\n${i + 1}. ${r.location}：${r.summary}")
            if (tags.isNotEmpty()) append("（$tags）")
        }
    }

    private fun finding(o: JsonObject, at: Int): Finding {
        val line = o["line"]?.let { el ->
            val p = el as? JsonPrimitive
            val n = if (p == null || p.isString) null else p.content.toIntOrNull()
            if (n == null || n < 1) throw InvalidSubagentInput("第 ${at + 1} 条的字段 `line` 必须是从 1 开始的整数。")
            n
        }
        val severity = o["severity"]?.let { el ->
            val p = el as? JsonPrimitive
            (if (p == null || !p.isString) null else FindingSeverity.of(p.content))
                ?: throw InvalidSubagentInput("第 ${at + 1} 条的字段 `severity` 只能是 important、nit 或 pre_existing。")
        }
        val category = o["category"]?.let { el ->
            val p = el as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank()) throw InvalidSubagentInput("第 ${at + 1} 条的字段 `category` 必须是非空字符串。")
            p.content.trim()
        }
        return Finding(
            file = o.reqText("file", at),
            summary = o.reqText("summary", at),
            failureScenario = o.reqText("failure_scenario", at),
            line = line,
            category = category,
            severity = severity,
        )
    }

    private fun JsonObject.reqText(key: String, at: Int): String {
        val p = this[key] as? JsonPrimitive
        if (p == null || !p.isString || p.content.isBlank()) throw InvalidSubagentInput("第 ${at + 1} 条的字段 `$key` 必须是非空字符串。")
        return p.content.trim()
    }
}

object ReportFindingsTool : Tool<JsonObject> {
    override val name = "ReportFindings"

    /** 只读、可并发：不碰工作区，也不改会话状态；恢复时重放无副作用 */
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

    private val itemProperties = JsonObject(
        mapOf(
            "file" to subagentProperty("string", "文件路径（相对工作区根目录）"),
            "line" to subagentProperty("integer", "可选：行号，从 1 开始"),
            "summary" to subagentProperty("string", "一句话说明问题"),
            "failure_scenario" to subagentProperty("string", "在什么输入、路径或条件下会出错，结果是什么"),
            "category" to subagentProperty("string", "可选：分类，如 correctness"),
            "severity" to subagentProperty("string", "可选：important（合并前应修）、nit（小问题）、pre_existing（既有问题、非本次引入）"),
        ),
    )

    override val inputSchema = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                mapOf(
                    "findings" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("array"),
                            "description" to JsonPrimitive("本次审查的全部发现，至少一条；没有发现时不要调用这个工具"),
                            "items" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("object"),
                                    "properties" to itemProperties,
                                    "required" to JsonArray(listOf("file", "summary", "failure_scenario").map(::JsonPrimitive)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "required" to JsonArray(listOf(JsonPrimitive("findings"))),
        ),
    )

    override fun description(profile: HarnessProfile) =
        "把代码审查的结果一次交上来（/code-review 用）。每条发现必须有 file、summary（一句话）和 failure_scenario（会在什么情况下出错）；" +
            "能确定时带 line、category，severity 用 important / nit / pre_existing。宿主会把它渲染成卡片。没有发现时不要调用，直接在回复里说明。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) { ReportFindings.from(raw) }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "报告审查结果")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val findings = ReportFindings.from(input)
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(ReportFindings.modelText(findings))))))
    }
}
