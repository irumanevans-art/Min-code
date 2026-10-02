package dev.min.agent.cli

import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.UserQuestion

/*
 * 终端里回答 AskUserQuestion 的最简交互：逐题列出编号选项，读一行——
 * 编号（多选用逗号分隔）、直接打字（当作「其他」）、或直接回车（这组问题都不回答）。
 * 解析是纯函数，便于单测；读写终端在 ConsoleUi 里。
 */

/** 一行输入的含义 */
internal sealed interface ConsoleAnswer {
    data class Answer(val answer: QuestionAnswer) : ConsoleAnswer

    /** 直接回车：整组问题都不回答（拒答） */
    data object Skip : ConsoleAnswer

    /** 看起来是编号却不合法（越界、单选给了多个）：提示后重新输入 */
    data class Retry(val reason: String) : ConsoleAnswer
}

private val NUMBER_LIST = Regex("""^\d+(\s*[,，、\s]\s*\d+)*$""")

internal fun parseConsoleAnswer(question: UserQuestion, line: String): ConsoleAnswer {
    val text = line.trim()
    if (text.isEmpty()) return ConsoleAnswer.Skip
    // 只有编号才当作选择；混了别的字（如「2 个都要」）就是用户自己写的话
    if (!NUMBER_LIST.matches(text)) return ConsoleAnswer.Answer(QuestionAnswer(other = text))
    val picks = text.split(',', '，', '、', ' ', '\t').filter { it.isNotBlank() }.map { it.toInt() }.distinct()
    val range = 1..question.options.size
    picks.firstOrNull { it !in range }?.let { return ConsoleAnswer.Retry("没有第 $it 项，请输入 1–${question.options.size}") }
    if (!question.multiSelect && picks.size > 1) return ConsoleAnswer.Retry("这题只能选一项")
    // 按选项顺序给 label，与界面卡片一致
    val labels = question.options.filterIndexed { i, _ -> (i + 1) in picks }.map { it.label }
    return ConsoleAnswer.Answer(QuestionAnswer(selected = labels))
}

internal fun renderQuestion(question: UserQuestion): String = buildString {
    append("  ? [").append(question.header).append("] ").append(question.question)
    append(if (question.multiSelect) "（可多选）" else "（单选）").append('\n')
    question.options.forEachIndexed { i, option ->
        append("    ").append(i + 1).append(". ").append(option.label)
        if (option.description.isNotBlank()) append(" —— ").append(option.description)
        append('\n')
    }
    append("  输入编号")
    if (question.multiSelect) append("（多个用逗号分隔）")
    append("，或直接打字作为「其他」；直接回车 = 这组问题都不回答：")
}
