package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.CRON_DEFAULT_BUDGET_USD
import dev.min.agent.spi.CRON_MAX_ACTIVE
import dev.min.agent.spi.CRON_MIN_INTERVAL_MS
import dev.min.agent.spi.CRON_RECURRING_TTL_MS
import dev.min.agent.spi.CronBoard
import dev.min.agent.spi.CronExpression
import dev.min.agent.spi.CronParsed
import dev.min.agent.spi.CronTask
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.parseCronInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 定时任务三件套：CronCreate / CronDelete / CronList（名字与语义照 Claude Code 的 scheduled-tasks）。
 * 会话级：任务存在根会话自己的日志里，会话开着且空闲时才触发，重开会话恢复未过期的，循环任务 7 天自动过期。
 * 手机上多一道：每个任务有单次运行的花费上限（默认 0.05 美元），到了就停并取消任务。
 *
 * 和任务清单同一个分工：工具只读 [ToolContext.crons] 这份视图、在结果里声明 [SessionEffect]（CronCreate / CronDelete），
 * 编号、创建时刻与过期时刻由内核落事件时给——工具不读时钟、不存任何东西。
 *
 * traits：三个都标只读（只改会话自己的定时任务表，碰不到工作区；Claude Code 里这三个也不需要权限）。
 * 创建 / 删除不并发（编号一个一个分配），恢复策略 Replay：效果只随 ToolOutcome 一起落盘，执行到一半被杀等于没发生。
 */
private val CRON_TRAITS_WRITE = ToolTraits(readOnly = true, concurrencySafe = false, resumePolicy = ResumePolicy.Replay)
private val CRON_TRAITS_READ = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

private fun ToolContext.cronRefusal(): ToolResult? = crons.refusal?.let { ToolResult(listOf(TextPart(it)), isError = true) }

private val WHEN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

private fun CronBoard.timeText(ms: Long?): String =
    if (ms == null) "（不会再触发）" else WHEN_FORMAT.format(Instant.ofEpochMilli(ms).atZone(zone)) + " " + (if (zone.id == "Z") "UTC" else zone.id)

private fun usd(v: Double): String {
    val plain = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    // 至少两位小数：0.05 → $0.05，1 → $1.00，0.025 → $0.025
    return "$" + if (plain.substringAfter('.', "").length >= 2) plain else String.format(Locale.ROOT, "%.2f", v)
}

/** 一条任务的一行说明：循环 / 一次性、调度、上限、触发次数、下次时刻，以及提示词（Claude Code 的 CronList 带限定词与提示词） */
private fun CronBoard.describe(t: CronTask): String {
    val kind = if (t.recurring) "循环" else "一次性"
    val next = if (t.active) "下次 ${timeText(t.nextFireAt(zone, 0))}" else "已结束：${t.endedBecause}"
    val expiry = t.expiresAtMs?.takeIf { t.active }?.let { "，${timeText(it)} 过期" } ?: ""
    return "- ${t.id}｜$kind｜${t.scheduleText()}｜单次上限 ${usd(t.maxBudgetUsd)}｜已触发 ${t.fires} 次｜$next$expiry\n  提示词：${t.prompt}"
}

object CronCreateTool : Tool<JsonObject> {
    override val name = "CronCreate"
    override val traits = CRON_TRAITS_WRITE
    override val inputSchema = subagentSchema(
        listOf("prompt"),
        "prompt" to subagentProperty("string", "到点时在本会话里执行的提示词（完整的一条指令，到点时你看到的就是它）"),
        "cron" to subagentProperty("string", "5 字段 cron 表达式（分 时 日 月 周），按本机时区；例如 \"0 9 * * 1-5\" 是工作日早上 9 点。与 every 二选一"),
        "every" to subagentProperty("string", "间隔：30m / 2h / 1d（至少 1 分钟）。循环任务每隔这么久触发，一次性任务过这么久触发一次。与 cron 二选一"),
        "recurring" to subagentProperty("boolean", "默认 true：循环；false：只触发一次"),
        "max_budget_usd" to subagentProperty("number", "每次运行的花费上限（美元），默认 $CRON_DEFAULT_BUDGET_USD；一次运行花到它就被停下并取消该任务"),
    )

    override fun description(profile: HarnessProfile) =
        "在当前会话里安排一个定时提示词：循环（cron 表达式或间隔）或一次性。任务只在会话开着且空闲时触发，到点时提示词作为一条预设任务送进会话（不是用户发的话）；" +
            "重开会话会恢复未过期的任务，循环任务创建 7 天后自动过期，错过的不补跑；每个会话最多 $CRON_MAX_ACTIVE 个。App 被系统杀掉时定时任务不跑。" +
            "无人值守会花用户的额度，所以每次运行有花费上限（默认 \$$CRON_DEFAULT_BUDGET_USD），到了就停。只有主会话能建，子 agent 不行。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, "prompt")
        if ("cron" in raw) requireSubagentString(raw, "cron")
        if ("every" in raw) requireSubagentString(raw, "every")
        val cron = raw.subagentString("cron")
        val every = raw.subagentString("every")
        if ((cron == null) == (every == null)) throw InvalidSubagentInput("`cron` 与 `every` 要恰好给一个（cron 表达式，或 30m / 2h / 1d 这样的间隔）。")
        if (cron != null) (CronExpression.parse(cron) as? CronParsed.Invalid)?.let { throw InvalidSubagentInput(it.reason) }
        if (every != null) {
            parseCronInterval(every) ?: throw InvalidSubagentInput("`every` 要写成 30m / 2h / 1d（数字加 m、h、d），最短 ${CRON_MIN_INTERVAL_MS / 60_000} 分钟：「$every」。")
        }
        raw["recurring"]?.let {
            val p = it as? JsonPrimitive
            if (p == null || p.isString || p.booleanOrNull == null) throw InvalidSubagentInput("字段 `recurring` 必须是布尔值 true 或 false。")
        }
        raw["max_budget_usd"]?.let {
            val p = it as? JsonPrimitive
            val v = if (p == null || p.isString) null else p.doubleOrNull
            if (v == null || !v.isFinite() || v <= 0) throw InvalidSubagentInput("字段 `max_budget_usd` 必须是大于 0 的数字（美元）。")
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "安排定时任务：" + (input.subagentString("cron")?.let { "cron $it" } ?: "every ${input.subagentString("every")}"))

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(create(input, ctx)))
    }

    private fun create(input: JsonObject, ctx: ToolContext): ToolResult {
        ctx.cronRefusal()?.let { return it }
        val board = ctx.crons
        if (board.active.size >= CRON_MAX_ACTIVE) return error("这个会话已经有 $CRON_MAX_ACTIVE 个定时任务（上限），先用 CronDelete 删掉几个。")
        val cron = input.subagentString("cron")
        val interval = input.subagentString("every")?.let(::parseCronInterval)
        val recurring = (input["recurring"] as? JsonPrimitive)?.booleanOrNull ?: true
        val budget = (input["max_budget_usd"] as? JsonPrimitive)?.doubleOrNull ?: CRON_DEFAULT_BUDGET_USD
        val prompt = input.subagentString("prompt")!!
        val preview = CronTask(board.nextId, prompt, recurring, budget, board.nowMs, cron = cron, intervalMs = interval)
        val next = preview.nextFireAt(board.zone)
            ?: return error("这个 cron 表达式在未来 8 年里不会匹配任何时刻（比如不存在的日期），请检查。")
        val lifetime = if (recurring) "循环，创建 7 天后自动过期" else "一次性，触发一次就结束"
        return ToolResult(
            listOf(
                TextPart(
                    "已创建定时任务 ${board.nextId}：${preview.scheduleText()}（$lifetime）。下次触发约 ${board.timeText(next)}；" +
                        "每次运行花费上限 ${usd(budget)}，花到就停并取消该任务。只在会话开着且空闲时触发，App 被系统杀掉时不跑。",
                ),
            ),
            effects = listOf(SessionEffect.CronCreate(prompt, recurring, budget, cron = cron, intervalMs = interval)),
        )
    }

    private fun error(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
}

object CronDeleteTool : Tool<JsonObject> {
    override val name = "CronDelete"
    override val traits = CRON_TRAITS_WRITE
    override val inputSchema = subagentSchema(
        listOf("id"),
        "id" to subagentProperty("string", "定时任务编号（CronCreate 的结果、CronList 每行开头的 c 加数字）"),
    )

    override fun description(profile: HarnessProfile) = "按编号取消一个定时任务（CronList 里能看到编号）。已结束的任务不用删。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) { requireSubagentString(raw, "id") }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "取消定时任务 ${input.subagentString("id")}")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val id = input.subagentString("id")!!.trim()
        val board = ctx.crons
        val task = board[id]
        val result = when {
            task == null -> ToolResult(listOf(TextPart("没有定时任务 $id。" + activeHint(board))), isError = true)
            !task.active -> ToolResult(listOf(TextPart("定时任务 $id 已经结束了（${task.endedBecause}），不用删。")), isError = true)
            else -> ToolResult(listOf(TextPart("已取消定时任务 $id（${task.scheduleText()}）。")), effects = listOf(SessionEffect.CronDelete(id)))
        }
        emit(ToolProgress.Done(result))
    }

    private fun activeHint(board: CronBoard) =
        if (board.active.isEmpty()) "现在没有进行中的定时任务。" else "进行中的：" + board.active.joinToString("、") { it.id }
}

object CronListTool : Tool<JsonObject> {
    override val name = "CronList"
    override val traits = CRON_TRAITS_READ
    override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))

    override fun description(profile: HarnessProfile) =
        "列出本会话的定时任务：编号、循环或一次性、调度、单次花费上限、已触发次数、下次触发时刻和提示词。已结束的任务也列出并写明原因。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = ToolInput.Valid(raw)

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "列出定时任务")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val board = ctx.crons
        val text = if (board.tasks.isEmpty()) "本会话没有定时任务。" else board.tasks.joinToString("\n") { board.describe(it) }
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(text)))))
    }
}
