package dev.min.code.core.session

/**
 * 工具卡上最多留多少字符的结果，两个引擎共用这一个数。
 *
 * 8K 是 Claude 侧一直在用的值（原 `ClaudeCodeManager.MAX_RESULT_CHARS`）：展开态只画前
 * 24 行（`ToolResultText`），8K 覆盖那几十行绰绰有余；再多只是攥在内存里、每次重组都
 * 按行切一遍的负担。Codex 以前没有上限 —— 一条刷屏的命令能把整份输出一直攥到 completed。
 */
const val MAX_TOOL_RESULT_CHARS = 8 * 1024

/**
 * 按共用规则把 [text] 落到卡上：**留开头** [max] 个字符（和 Claude 侧一贯的 `take` 一致），
 * 超了就在 [ChatItem.ToolCall.resultTotalChars] 记下原文长度，界面据此补一行「已截断，共 N 字符」。
 *
 * 以前是静默 `take`：卡片看起来像命令就输出了这么多，流式的 Codex 卡更糟 ——
 * 命令还在跑，卡片却不再长，像是卡住了。
 */
fun ChatItem.ToolCall.withClippedResult(text: String?, max: Int = MAX_TOOL_RESULT_CHARS): ChatItem.ToolCall =
    copy(
        result = text?.let { src ->
            val clipped = src.take(max)
            // 截断别落在 UTF-16 代理对中间：末尾落单的高代理退一位，emoji 不显示成替换符
            if (src.length > max) clipped.dropDanglingHighSurrogate() else clipped
        },
        resultTotalChars = text?.length?.toLong()?.takeIf { it > max },
    )

/** 末尾若是落单的高代理（被截断在 emoji/生僻字中间），退掉它 */
private fun String.dropDanglingHighSurrogate(): String {
    if (isEmpty() || !Character.isHighSurrogate(this[length - 1])) return this
    return substring(0, length - 1)
}

/**
 * 流式命令输出的累积器，和 [withClippedResult] 同一条规则（留头、记总长）。
 *
 * 为什么不是一个 StringBuilder 每来一段就 `toString()`：那样每段增量都把整份输出复制一遍，
 * 总成本 O(n²)，内存也没有上限。这里只攒到 [max]，到顶之后增量只加计数、不再复制，
 * 快照字符串原样复用 —— 每段增量的成本与已攒的总量无关。
 */
class ClippedOutput(private val max: Int = MAX_TOOL_RESULT_CHARS) {
    private val head = StringBuilder()
    private var snapshot = ""
    private var totalChars = 0L

    fun append(delta: String) {
        totalChars += delta.length
        val room = max - head.length
        if (room <= 0 || delta.isEmpty()) return
        val end = minOf(room, delta.length)
        // 同 [withClippedResult]：不把代理对从中间攒进去
        val piece = if (end == delta.length) delta else delta.substring(0, end).dropDanglingHighSurrogate()
        head.append(piece)
        snapshot = head.toString()
    }

    /** 把当前攒到的输出落到 [card] 上 */
    fun applyTo(card: ChatItem.ToolCall): ChatItem.ToolCall =
        card.copy(result = snapshot, resultTotalChars = totalChars.takeIf { it > max })
}
