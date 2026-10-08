package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
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

/**
 * 创建结果里关于花费的一句：说清这是无人值守的运行（模型承诺“每天 9 点帮你查”时要知道到点没有人在看），
 * 以及这个任务实际的单次上限（任务自己的与宿主天花板取小；都没有就是不限）
 */
private fun CronBoard.spendText(taskBudgetUsd: Double?): String {
    val limit = effectiveBudgetUsd(taskBudgetUsd)
    val spend = when {
        limit == null -> "没有花费上限，花的是用户自己的额度。"
        taskBudgetUsd == null -> "宿主设了花费上限 ${usd(limit)}，每次运行花到就停下这一次运行。"
        limit < taskBudgetUsd -> "宿主设的花费天花板 ${usd(limit)} 比你给的 ${usd(taskBudgetUsd)} 低，按 ${usd(limit)} 算；每次运行花到就停下这一次运行。"
        else -> "每次运行花费上限 ${usd(limit)}，花到就停下这一次运行。"
    }
    val pause = if (limit != null) "任务本身保持进行中，连续 2 次超限才会暂停并通知用户。" else ""
    return "这是无人值守的运行：到点触发时没有人在看，不要向用户提问、也别依赖审批。$spend$pause"
}

/** 一条任务的一行说明：循环 / 一次性、调度、上限、触发次数、下次时刻，以及提示词（Claude Code 的 CronList 带限定词与提示词） */
private fun CronBoard.describe(t: CronTask): String {
    val kind = if (t.recurring) "循环" else "一次性"
    val next = if (t.active) "下次 ${timeText(t.nextFireAt(zone, nextFireFloors[t.id] ?: 0))}" else "已结束：${t.endedBecause}"
    val expiry = t.expiresAtMs?.takeIf { t.active }?.let { "，${timeText(it)} 过期" } ?: ""
    return "- ${t.id}｜$kind｜${t.scheduleText()}｜单次上限 ${effectiveBudgetUsd(t.maxBudgetUsd)?.let(::usd) ?: "不限"}｜已触发 ${t.fires} 次｜$next$expiry\n  提示词：${t.prompt}"
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
        "max_budget_usd" to subagentProperty("number", "可选：每次运行的花费上限（美元）。不写就不限（和 Claude Code 一样，花的是用户自己的额度）；写了，一次运行（含它启动的子 agent、团队成员）花到它就停下这一次运行，任务保持进行中，连续 2 次超限才暂停。宿主设了天花板时取较小的"),
    )

    override fun description(profile: HarnessProfile) =
        "在当前会话里安排一个定时提示词：循环（cron 表达式或间隔）或一次性。任务只在会话开着且空闲时触发，到点时提示词作为一条预设任务送进会话（不是用户发的话）；" +
            "重开会话会恢复未过期的任务，循环任务创建 7 天后自动过期，错过的不补跑；每个会话最多 $CRON_MAX_ACTIVE 个。$LIVENESS_NOTE" +
            "这是无人值守的运行，花的是用户自己的额度：默认不限，要限就传 max_budget_usd（一次运行花到它就停）。只有主会话能建，子 agent 与团队成员不行。"

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
        val budget = (input["max_budget_usd"] as? JsonPrimitive)?.doubleOrNull
        val prompt = input.subagentString("prompt")!!
        val preview = CronTask(board.nextId, prompt, recurring, budget, board.nowMs, cron = cron, intervalMs = interval)
        val next = preview.nextFireAt(board.zone)
            ?: return error("这个 cron 表达式在未来 8 年里不会匹配任何时刻（比如不存在的日期），请检查。")
        val lifetime = if (recurring) "循环，创建 7 天后自动过期" else "一次性，触发一次就结束"
        return ToolResult(
            listOf(
                TextPart(
                    "已创建定时任务 ${board.nextId}：${preview.scheduleText()}（$lifetime）。下次触发约 ${board.timeText(next)}；" +
                        board.spendText(budget) + "只在会话开着且空闲时触发。$LIVENESS_NOTE",
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

/**
 * ScheduleWakeup：自驱循环（`/loop` 不给间隔时）的“下一轮”。和 CronCreate 是**同一个机制的另一个入口**——
 * 落成一条一次性的间隔任务（`source = wakeup`），所以同样只在会话开着且空闲时触发、同样有单次花费上限、同样随会话日志恢复。
 * 同一时刻最多一个：再次安排会替掉还没触发的那个；`stop: true` 取消它（不再有下一轮）。间隔 60 秒到 1 小时（Claude Code 同）。
 */
object ScheduleWakeupTool : Tool<JsonObject> {
    override val name = "ScheduleWakeup"
    override val traits = CRON_TRAITS_WRITE

    private const val MIN_DELAY_SECONDS = 60
    private const val MAX_DELAY_SECONDS = 3600

    override val inputSchema = subagentSchema(
        emptyList(),
        "delaySeconds" to subagentProperty("number", "多少秒后唤醒自己，$MIN_DELAY_SECONDS 到 $MAX_DELAY_SECONDS（超出会被夹到范围内并在结果里告诉你）；stop 为 true 时不用写"),
        "prompt" to subagentProperty("string", "唤醒时送进会话的提示词（通常是继续做这件事的指令）；stop 为 true 时不用写"),
        "reason" to subagentProperty("string", "一句话说明为什么等这么久，给用户看"),
        "stop" to subagentProperty("boolean", "true：取消已安排的唤醒，循环到此为止"),
        "max_budget_usd" to subagentProperty("number", "可选：这一轮的花费上限（美元），不写就不限"),
    )

    override fun description(profile: HarnessProfile) =
        "自驱循环的下一轮：安排一个唤醒，$MIN_DELAY_SECONDS 秒到 $MAX_DELAY_SECONDS 秒后把 prompt 送回本会话（只触发一次；想继续循环就在那一轮里再调用它）。" +
            "同一时刻最多一个，再次调用会替掉还没触发的。stop=true 取消。和 CronCreate 同一套规矩：只在会话开着且空闲时触发、无人值守（默认不限花费，要限就传 max_budget_usd）。$LIVENESS_NOTE"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        raw["stop"]?.let {
            val p = it as? JsonPrimitive
            if (p == null || p.isString || p.booleanOrNull == null) throw InvalidSubagentInput("字段 `stop` 必须是布尔值 true 或 false。")
        }
        if ((raw["stop"] as? JsonPrimitive)?.booleanOrNull == true) return@validateSubagentInput
        val delay = (raw["delaySeconds"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (delay == null || !delay.isFinite() || delay <= 0) {
            throw InvalidSubagentInput("字段 `delaySeconds` 必须是大于 0 的秒数（超出 $MIN_DELAY_SECONDS 到 $MAX_DELAY_SECONDS 的会被调整到范围内，不会报错）。")
        }
        requireSubagentString(raw, "prompt")
        if ("reason" in raw) requireSubagentString(raw, "reason")
        raw["max_budget_usd"]?.let {
            val p = it as? JsonPrimitive
            val v = if (p == null || p.isString) null else p.doubleOrNull
            if (v == null || !v.isFinite() || v <= 0) throw InvalidSubagentInput("字段 `max_budget_usd` 必须是大于 0 的数字（美元）。")
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "安排唤醒")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(wake(input, ctx)))
    }

    private fun wake(input: JsonObject, ctx: ToolContext): ToolResult {
        ctx.cronRefusal()?.let { return it }
        val board = ctx.crons
        val pending = board.active.filter { it.source == dev.min.agent.spi.CRON_SOURCE_WAKEUP }
        val cancels = pending.map { SessionEffect.CronDelete(it.id) }
        if ((input["stop"] as? JsonPrimitive)?.booleanOrNull == true) {
            val text = if (pending.isEmpty()) "没有安排中的唤醒。" else "已取消安排中的唤醒（${pending.joinToString("、") { it.id }}），循环到此为止。"
            return ToolResult(listOf(TextPart(text)), effects = cancels)
        }
        // 照 Claude Code：超出范围夹到 60–3600 并在结果里说明被夹过（wasClamped），不当错误拒绝——模型常按“等 CI 十分钟”随手填个大数
        val asked = (input["delaySeconds"] as JsonPrimitive).doubleOrNull!!.let { Math.round(it) }
        val delay = asked.coerceIn(MIN_DELAY_SECONDS.toLong(), MAX_DELAY_SECONDS.toLong())
        val clamped = if (delay != asked) "（你给的是 $asked 秒，超出 $MIN_DELAY_SECONDS 到 $MAX_DELAY_SECONDS 秒的范围，已调整为 $delay 秒）" else ""
        val budget = (input["max_budget_usd"] as? JsonPrimitive)?.doubleOrNull
        val at = board.nowMs + delay * 1000
        // 唤醒链共用链头那一次的 7 天寿命：下一轮会排到寿命之后的，直接告诉模型这条循环到此为止（内核落事件时也会拦，这里让模型当场知道）
        val chainEnd = board.wakeupChainExpiresAtMs
        if (chainEnd != null && at >= chainEnd) {
            return ToolResult(
                listOf(TextPart("这条自驱循环从第一次安排起已经满 7 天（${board.timeText(chainEnd)} 到期），不再安排下一轮。需要继续的话请用户重新发起。")),
                isError = true,
            )
        }
        val chainNote = chainEnd?.let { "这条自驱循环最晚到 ${board.timeText(it)}（从第一次安排起 7 天）。" } ?: ""
        val replaced = if (pending.isEmpty()) "" else "（替掉了还没触发的 ${pending.joinToString("、") { it.id }}）"
        val reason = input.subagentString("reason")?.let { "，原因：$it" } ?: ""
        return ToolResult(
            listOf(TextPart("已安排 $delay 秒后唤醒$clamped（编号 ${board.nextId}，约 ${board.timeText(at)}$reason）$replaced；${board.spendText(budget)}${chainNote}只在会话开着且空闲时触发。$LIVENESS_NOTE")),
            effects = cancels + SessionEffect.CronCreate(
                input.subagentString("prompt")!!, recurring = false, maxBudgetUsd = budget,
                intervalMs = delay * 1000, source = dev.min.agent.spi.CRON_SOURCE_WAKEUP,
            ),
        )
    }
}

/**
 * 手机上的现实，原样写给模型（别让它向用户承诺“每天 9 点一定帮你查”）：定时器是进程里的心跳，不是系统闹钟。
 * Android 把 App 切到后台一段时间后会冻结或杀掉进程，心跳随之停掉；前台服务只在有回合在跑时才保活，只剩定时任务的会话保不住它。
 * 所以：只在 Min 在前台、或进程碰巧还活着时才触发；切到后台一段时间后通常不触发，错过的也不补跑。桌面 CLI 同样只在进程活着时工作。
 */
private const val LIVENESS_NOTE =
    "仅在 Min 在前台或进程仍存活时触发，切到后台一段时间后通常不触发（进程被系统冻结或杀掉），错过的不补跑；不要向用户保证“到点一定会跑”。"
