package dev.min.agent.spi

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/*
 * 定时任务的时间数学与折叠出的视图：纯函数、不读时钟（时间一律传进来），所以内核、工具和测试共用同一份、可以注入时钟。
 *
 * 触发时刻的规则（照 Claude Code，文档 scheduled-tasks）：
 *  - 错过不补跑：`nextFireAt` 只看「上次触发 / 创建 / 下限」之后的下一个时刻，不会一口气补发错过的几次；
 *  - 5 字段 cron 在会话所在时区解释（日与星期都限定时取并集，标准 cron 语义）；
 *  - 不做抖动（Claude Code 在计划时刻后至多 30 分钟内抖动是为了错开服务端负载，本地调度用不着）。
 */

/** 解析后的 5 字段 cron 表达式：分 时 日 月 周。周 0 和 7 都是周日 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domWild: Boolean,
    private val dowWild: Boolean,
    val text: String,
) {
    /** [afterMs] 之后（严格晚于）下一个匹配的整分钟时刻；8 年内没有（如 2 月 30 日）返回 null */
    fun nextAfter(afterMs: Long, zone: ZoneId): Long? {
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMs), zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(SEARCH_YEARS)
        while (t.isBefore(limit)) {
            when {
                t.monthValue !in months -> t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone)
                !dayMatches(t) -> t = t.toLocalDate().plusDays(1).atStartOfDay(zone)
                t.hour !in hours -> t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                t.minute !in minutes -> t = t.plusMinutes(1)
                else -> return t.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun dayMatches(t: ZonedDateTime): Boolean {
        val domOk = t.dayOfMonth in daysOfMonth
        val dowOk = (t.dayOfWeek.value % 7) in daysOfWeek
        // 标准 cron：日和星期都限定了就取并集，只限定一个就以它为准
        return if (!domWild && !dowWild) domOk || dowOk else domOk && dowOk
    }

    companion object {
        private const val SEARCH_YEARS = 8L

        private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        fun parse(text: String): CronParsed {
            val fields = text.trim().split(Regex("\\s+"))
            if (fields.size != 5) return CronParsed.Invalid("cron 表达式要正好 5 个字段（分 时 日 月 周），收到 ${fields.size} 个：「$text」。")
            val minutes = parseField(fields[0], 0, 59) ?: return invalid("分钟", fields[0])
            val hours = parseField(fields[1], 0, 23) ?: return invalid("小时", fields[1])
            val dom = parseField(fields[2], 1, 31) ?: return invalid("日", fields[2])
            val months = parseField(fields[3], 1, 12, MONTH_NAMES, nameBase = 1) ?: return invalid("月", fields[3])
            val dow = parseField(fields[4], 0, 7, DAY_NAMES, nameBase = 0) ?: return invalid("星期", fields[4])
            return CronParsed.Ok(
                CronExpression(minutes, hours, dom, months, dow.map { it % 7 }.toSet(), isWild(fields[2]), isWild(fields[4]), fields.joinToString(" ")),
            )
        }

        private fun invalid(name: String, field: String) = CronParsed.Invalid("cron 的「$name」字段写错了：「$field」。")

        private fun isWild(field: String) = field == "*" || field == "?" || field.startsWith("*/")

        /** 一个字段：逗号分隔的若干项，每项是 `*`、`a`、`a-b`，可带 `/步长`。非法返回 null */
        private fun parseField(field: String, min: Int, max: Int, names: List<String> = emptyList(), nameBase: Int = 0): Set<Int>? {
            val result = sortedSetOf<Int>()
            for (item in field.split(',')) {
                if (item.isEmpty()) return null
                val (range, stepText) = item.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step = if (stepText == null) 1 else stepText.toIntOrNull()?.takeIf { it > 0 } ?: return null
                val (lo, hi) = when {
                    range == "*" || range == "?" -> min to max
                    '-' in range -> {
                        val a = valueOf(range.substringBefore('-'), names, nameBase) ?: return null
                        val b = valueOf(range.substringAfter('-'), names, nameBase) ?: return null
                        a to b
                    }
                    else -> {
                        val v = valueOf(range, names, nameBase) ?: return null
                        // 「a/n」= 从 a 起每 n 到上限；单个值不带步长就只有它自己
                        v to (if (stepText != null) max else v)
                    }
                }
                if (lo < min || hi > max || lo > hi) return null
                var v = lo
                while (v <= hi) {
                    result += v
                    v += step
                }
            }
            return result.takeIf { it.isNotEmpty() }
        }

        private fun valueOf(token: String, names: List<String>, nameBase: Int): Int? =
            token.toIntOrNull() ?: names.indexOf(token.lowercase()).takeIf { it >= 0 }?.plus(nameBase)
    }
}

sealed interface CronParsed {
    data class Ok(val expression: CronExpression) : CronParsed
    data class Invalid(val reason: String) : CronParsed
}

/** 一条定时任务的状态（折叠出来的） */
enum class CronStatus { Active, Fired, Cancelled, Expired }

data class CronTask(
    val id: String,
    val prompt: String,
    val recurring: Boolean,
    val maxBudgetUsd: Double,
    val scheduledAtMs: Long,
    /** 5 字段表达式；与 [intervalMs] 恰好一个非空 */
    val cron: String? = null,
    val intervalMs: Long? = null,
    val expiresAtMs: Long? = null,
    val fires: Int = 0,
    val lastFiredAtMs: Long? = null,
    val status: CronStatus = CronStatus.Active,
    /** 结束原因：取消 / 过期（含 [CronExpiry] 与说明） / 一次性已触发 */
    val endedBecause: String? = null,
) {
    val active: Boolean get() = status == CronStatus.Active

    /** 到了过期时刻（只有循环任务有；一次性任务不按时间过期） */
    fun isPastExpiry(nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs

    /** 给人和模型看的调度描述：「cron 0 9 * * 1-5」「每 30 分钟」 */
    fun scheduleText(): String = cron?.let { "cron $it" } ?: intervalText(intervalMs ?: 0)

    /**
     * 下一次该触发的时刻。循环任务从「上次触发（没有就是创建）与 [floorMs] 中较晚者」往后数——[floorMs] 是会话（重新）
     * 打开的时刻，用来实现「错过不补跑」；一次性任务从创建时刻数，不受 floor 影响（错过的一次性由内核另行判定为过期）。
     * 表达式永远匹配不到（如 2 月 30 日）返回 null
     */
    fun nextFireAt(zone: ZoneId, floorMs: Long = 0): Long? {
        val base = if (recurring) maxOf(lastFiredAtMs ?: scheduledAtMs, floorMs) else scheduledAtMs
        val interval = intervalMs
        if (interval != null) return base + interval
        val parsed = CronExpression.parse(cron ?: return null)
        return (parsed as? CronParsed.Ok)?.expression?.nextAfter(base, zone)
    }
}

/** 工具看到的定时任务视图（[ToolContext.crons]）：当前任务 + 下一个编号 + 时钟 / 时区；[refusal] 非空 = 这个会话不能建 */
data class CronBoard(
    val tasks: List<CronTask> = emptyList(),
    val nextId: String = "1",
    val nowMs: Long = 0,
    val zone: ZoneId = ZoneId.of("UTC"),
    val refusal: String? = "这个环境不支持定时任务。",
) {
    val active: List<CronTask> get() = tasks.filter { it.active }
    operator fun get(id: String): CronTask? = tasks.firstOrNull { it.id == id }
}

/** 解析间隔写法：`30m` / `2h` / `1d`（分钟、小时、天），至少一分钟。非法返回 null */
fun parseCronInterval(text: String): Long? {
    val m = Regex("""^\s*(\d+)\s*([mhd])\s*$""", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
    val n = m.groupValues[1].toLongOrNull() ?: return null
    val unit = when (m.groupValues[2].lowercase()) {
        "m" -> 60_000L
        "h" -> 3_600_000L
        else -> 86_400_000L
    }
    val ms = n * unit
    return ms.takeIf { n > 0 && ms >= CRON_MIN_INTERVAL_MS && ms / unit == n }
}

/** 间隔的中文写法：90 分钟 → 「每 90 分钟」，整小时 / 整天换成小时 / 天 */
fun intervalText(ms: Long): String = when {
    ms % 86_400_000L == 0L -> "每 ${ms / 86_400_000L} 天"
    ms % 3_600_000L == 0L -> "每 ${ms / 3_600_000L} 小时"
    else -> "每 ${ms / 60_000L} 分钟"
}
