package dev.min.agent.kernel

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role

/*
 * `/goal` 评估器看到的对话（CC：「条件加上到目前为止的对话」）。取模型视图（与发给主模型的同源：压缩后就是摘要 + 之后的消息），
 * 去掉工具结果的大块正文，再按字数截断。纯函数，单测直接断言。
 */

/** 评估器的输入上限（字符数）。评估模型是小模型，按约 3 万词元的保守线估（中文约 1 字 ≈ 1 词元，英文更省）；超过就截断，规则见 [goalTranscript] */
internal const val GOAL_TRANSCRIPT_CHARS = 120_000

/** 工具结果在评估器视图里保留的开头字数：结论多半在开头；全文评估器看不到，它被告知「只留了开头」（[goalEvaluationPrompt]） */
internal const val GOAL_TOOL_RESULT_KEEP = 400

/** 工具调用的入参保留的字数：说明调了什么就够了，大段 content 不需要 */
internal const val GOAL_TOOL_INPUT_KEEP = 200

/**
 * 把模型视图渲染成评估器读的对话文本。每条消息一块（角色 + 正文），块与块之间空一行。
 *
 * 超过 [budgetChars] 时的截断：保留第一条用户消息（完成条件通常就在它里面）与尽量多的最近内容，中间用一行标明省略了几条。
 * 第一条本身超长时截它的开头；最近的那条也放不下时只留它的末尾（结论在最后）。
 */
internal fun goalTranscript(messages: List<ModelMessage>, budgetChars: Int = GOAL_TRANSCRIPT_CHARS): String {
    val blocks = messages.map(::renderBlock)
    if (blocks.isEmpty()) return ""
    val all = blocks.joinToString("\n\n")
    if (all.length <= budgetChars) return all
    val head = blocks.first().take(budgetChars / 2)
    var room = budgetChars - head.length - OMITTED_MARK.length - 4
    val tail = ArrayDeque<String>()
    for (block in blocks.drop(1).asReversed()) {
        if (block.length > room) {
            // 最近的一条也放不下：留它的末尾，结论通常在最后
            if (tail.isEmpty() && room > 0) tail.addFirst(block.takeLast(room))
            break
        }
        room -= block.length + 2
        tail.addFirst(block)
    }
    val omitted = blocks.size - 1 - tail.size
    return buildString {
        append(head)
        if (omitted > 0) append("\n\n").append("…（中间省略了 $omitted 条消息）")
        tail.forEach { append("\n\n").append(it) }
    }
}

/** 截断时的省略标记（只用来估长度；真正的标记在 [goalTranscript] 里按省略条数写） */
private const val OMITTED_MARK = "…（中间省略了 999 条消息）"

private fun renderBlock(message: ModelMessage): String {
    val who = if (message.role == Role.User) "用户" else "助手"
    val body = message.parts.mapNotNull(::renderPart).joinToString("\n")
    return "[$who]\n$body"
}

private fun renderPart(part: Part): String? = when (part) {
    is TextPart -> part.text
    is ToolCallPart -> "（调用工具 ${part.name}：${part.input.toString().take(GOAL_TOOL_INPUT_KEEP)}）"
    is ToolResultPart -> {
        val text = part.content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val label = if (part.isError) "工具报错" else "工具结果"
        when {
            text.length <= GOAL_TOOL_RESULT_KEEP -> "（$label：$text）"
            else -> "（$label：${text.take(GOAL_TOOL_RESULT_KEEP)}…省略 ${text.length - GOAL_TOOL_RESULT_KEEP} 字）"
        }
    }
    is ImagePart -> "（图片）"
    else -> null
}
