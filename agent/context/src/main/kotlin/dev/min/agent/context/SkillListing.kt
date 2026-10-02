package dev.min.agent.context

import dev.min.agent.spi.SkillEntry

/**
 * 系统提示里「可调用的 skill 清单」：每个 skill 一行 `- 名字: 说明`，让模型知道有哪些 skill、什么时候该用。
 * 预算与截断照 Claude Code（文档 skills.md「Skill descriptions are cut short」、settings 的 `skillListingBudgetFraction` /
 * `skillListingMaxDescChars`；超预算时怎么挑的细节取自 2.1.286 二进制，文档没写）：
 *
 * - 说明 = `description`，有 `when_to_use` 则接 ` - when_to_use`；超过单条上限（默认 1536 字符，含 when_to_use）截断并以 `…` 结尾。
 * - 整份清单的预算 = 模型窗口（token）× 4 字符/token × 比例（默认 0.01，即窗口的 1%），至少 1 字符。
 * - 放得下就全带说明；放不下：**名字永远保留**，先给每个 skill 留出「- 名字」，剩下的预算按清单顺序逐个补说明，
 *   补得下就补、补不下的这一条跳过（后面更短的仍有机会）。Claude Code 是按「调用次数多的优先」排序；
 *   会话里的清单是创建时冻结的、没有调用统计，这里按清单顺序（managed、user、project 的 skill，然后 commands）。
 * - `disable-model-invocation: true` 的不列（文档：连说明都不进上下文）。
 *
 * 说明里的换行与连续空白压成一个空格：YAML 块标量写的说明常带换行，一条清单项必须是一行。
 * 纯函数：同一份快照 + 同一个窗口 = 同一段文字，请求才能从日志重建。
 */
object SkillListing {
    const val DEFAULT_BUDGET_FRACTION = 0.01
    const val DEFAULT_MAX_DESC_CHARS = 1536

    /** Claude Code 估预算用的「每 token 几个字符」 */
    private const val CHARS_PER_TOKEN = 4

    const val INTRO = "The following skills are available for use with the Skill tool:"

    /** 没有可列的 skill 返回 null（整段不出现，系统提示不为它多一个空段） */
    fun block(
        entries: List<SkillEntry>,
        contextWindow: Int,
        budgetFraction: Double? = null,
        maxDescChars: Int? = null,
    ): String? {
        val lines = lines(entries, contextWindow, budgetFraction, maxDescChars)
        return if (lines.isEmpty()) null else INTRO + "\n\n" + lines.joinToString("\n")
    }

    fun lines(entries: List<SkillEntry>, contextWindow: Int, budgetFraction: Double? = null, maxDescChars: Int? = null): List<String> {
        val listed = entries.filter { !it.disableModelInvocation }
        if (listed.isEmpty()) return emptyList()
        val maxChars = (maxDescChars ?: DEFAULT_MAX_DESC_CHARS).coerceAtLeast(1)
        val fraction = (budgetFraction ?: DEFAULT_BUDGET_FRACTION).takeIf { it > 0 && it <= 1 } ?: DEFAULT_BUDGET_FRACTION
        val budget = maxOf(1L, (contextWindow.toLong() * CHARS_PER_TOKEN * fraction).toLong())

        val nameOnly = listed.map { "- ${it.name}" }
        val full = listed.mapIndexed { i, e ->
            val text = describe(e, maxChars)
            if (text.isEmpty()) nameOnly[i] else "${nameOnly[i]}: $text"
        }
        val newlines = listed.size - 1
        if (full.sumOf { it.length } + newlines <= budget) return full

        var remaining = budget - nameOnly.sumOf { it.length } - newlines
        return listed.indices.map { i ->
            val extra = full[i].length - nameOnly[i].length
            if (extra <= remaining) {
                remaining -= extra
                full[i]
            } else {
                nameOnly[i]
            }
        }
    }

    private fun describe(e: SkillEntry, maxChars: Int): String {
        val joined = if (e.whenToUse.isNullOrBlank()) e.description else "${e.description} - ${e.whenToUse}"
        val flat = joined.trim().replace(Regex("\\s+"), " ")
        return if (flat.length > maxChars) flat.take(maxChars - 1) + "…" else flat
    }
}
