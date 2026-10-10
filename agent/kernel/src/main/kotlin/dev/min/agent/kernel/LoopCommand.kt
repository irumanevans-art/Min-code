package dev.min.agent.kernel

/**
 * `/loop` 的纯规则（照 Claude Code 的 scheduled-tasks 页）：间隔解析、取整成 cron 步长、loop.md 的选择与截断。
 * 不碰会话、不碰文件系统：内置 skill 的正文由模型照着做，这里算的是模型不该自己算错的那部分。
 *
 * 为什么放内核：间隔的秒→分钟取整、非整步长取最近并告诉用户，是确定性的换算；让模型心算容易错，所以内核算好，
 * 结果写进 `/loop` 的「内核解析结果」一节（见 [planBlock]）。
 */
internal object LoopCommand {
    /**
     * 解析结果。[intervalSeconds] 为 null = 没给间隔（自定节奏）；[prompt] 为空串 = 没给提示词（用 loop.md 或内置维护提示）。
     */
    data class Request(val intervalSeconds: Long?, val prompt: String)

    /** 取整后的固定间隔：[cron] 是 5 字段表达式，[minutes] 是实际用的分钟数，[note] 非空时要告诉用户 */
    data class Plan(val cron: String, val minutes: Long, val note: String?)

    /** 前置写法：开头一个词，如 `5m`、`30s`、`2h`、`1d`（数字紧跟单位字母） */
    private val LEADING = Regex("""^(\d+)([smhdSMHD])(?:\s+([\s\S]*))?$""")

    /** 尾随写法：结尾的 `every 2 hours` / `every minute` 从句 */
    private val TRAILING = Regex("""\s+every\s+(?:(\d+)\s*)?(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|[smhd])\s*$""", RegexOption.IGNORE_CASE)

    /** cron 能整除成步长的分钟数（升序：取最近时并距离相同的，取较短的那个）；1440 = 一天 */
    private val STEPS = listOf(1L, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60, 120, 180, 240, 360, 480, 720, 1440)

    /** loop.md 最多取这么多字节，超出截断（文档：content beyond 25,000 bytes is truncated） */
    const val MAX_LOOP_MD_BYTES = 25_000

    fun parse(args: String): Request {
        val text = args.trim()
        LEADING.matchEntire(text)?.let { m ->
            unitSeconds(m.groupValues[1], m.groupValues[2][0])?.let { return Request(it, m.groupValues[3].trim()) }
        }
        TRAILING.find(text)?.let { m ->
            val n = m.groupValues[1].ifEmpty { "1" }
            val unit = m.groupValues[2][0].lowercaseChar()
            unitSeconds(n, unit)?.let { return Request(it, text.substring(0, m.range.first).trim()) }
        }
        return Request(null, text)
    }

    /** 数字 × 单位（s/m/h/d）换成秒；数字不是正整数、或单位不认识返回 null */
    private fun unitSeconds(number: String, unit: Char): Long? {
        val n = number.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val per = when (unit.lowercaseChar()) {
            's' -> 1L
            'm' -> 60L
            'h' -> 3600L
            'd' -> 86400L
            else -> return null
        }
        return if (n > Long.MAX_VALUE / per) null else n * per
    }

    /**
     * 秒 → 固定间隔。秒向上取整到分钟；分钟数取最近的 cron 整步长，距离相同取较短的；超过一天的按一天。
     * 有任何一处换算（向上取整、换成别的步长）就写进 [Plan.note]，让用户知道实际跑的是哪个。
     */
    fun plan(seconds: Long): Plan {
        val wanted = maxOf(1L, (seconds + 59) / 60)
        val chosen = STEPS.minBy { kotlin.math.abs(it - wanted) }
        val notes = mutableListOf<String>()
        if (seconds % 60 != 0L) notes += "$seconds 秒向上取整到 $wanted 分钟"
        if (chosen != wanted) notes += "$wanted 分钟不是可用的整步长，取最近的 $chosen 分钟"
        return Plan(cronFor(chosen), chosen, notes.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    private fun cronFor(minutes: Long): String = when {
        minutes == 1L -> "* * * * *"
        minutes < 60 -> "*/$minutes * * * *"
        minutes == 60L -> "0 * * * *"
        minutes < 1440 -> "0 */${minutes / 60} * * *"
        else -> "0 0 * * *"
    }

    /**
     * 默认提示词：项目的 `.claude/loop.md` 优先，其次 `~/.claude/loop.md`；都没有（或全是空白）返回 null，
     * 此时用内置维护提示。超过 [MAX_LOOP_MD_BYTES] 字节截断，截断只落在字符边界上。
     */
    fun defaultPrompt(project: String?, user: String?): String? {
        val text = listOf(project, user).firstOrNull { !it.isNullOrBlank() } ?: return null
        return truncateUtf8(text, MAX_LOOP_MD_BYTES)
    }

    private fun truncateUtf8(text: String, limit: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= limit) return text
        val out = StringBuilder()
        var bytes = 0
        for (cp in text.codePoints().toArray()) {
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > limit) break
            bytes += n
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /**
     * 给模型看的「内核解析结果」一节：固定间隔时给出 cron 与取整说明，没有间隔时说明用自定节奏；
     * 提示词照用户的，没有则用 loop.md（[defaultPrompt]），两者都没有就写明用内置维护提示。
     */
    fun planBlock(args: String, projectLoopMd: String?, userLoopMd: String?): String {
        val req = parse(args)
        val sb = StringBuilder("## 内核解析结果\n")
        val interval = req.intervalSeconds
        if (interval != null) {
            val p = plan(interval)
            sb.append("- 固定间隔：CronCreate 的 cron 用 `${p.cron}`，recurring 为 true。")
            p.note?.let { sb.append("（$it；把这一点告诉用户。）") }
            sb.append('\n')
        } else {
            sb.append("- 没有给间隔：自定节奏，每轮结束用 ScheduleWakeup 安排下一轮。\n")
        }
        val prompt = req.prompt.takeIf { it.isNotBlank() } ?: defaultPrompt(projectLoopMd, userLoopMd)
        if (prompt != null) {
            val source = if (req.prompt.isNotBlank()) "用户给的" else "loop.md"
            sb.append("- 提示词（$source，照用，不要改写）：\n\n```text\n$prompt\n```\n")
        } else {
            sb.append("- 没有提示词，也没有 loop.md：用内置维护提示。\n")
        }
        return sb.toString()
    }
}
