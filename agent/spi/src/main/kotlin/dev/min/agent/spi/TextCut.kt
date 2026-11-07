package dev.min.agent.spi

/**
 * 按 UTF-16 下标截字符串、又不把代理对劈成两半的唯一实现：工具输出的头尾预览、单行截断都走这里。
 *
 * 为什么要管：Kotlin 的 take / takeLast / substring 按 char 数，emoji 等 BMP 以外的字符占两个 char（高代理 + 低代理），
 * 截点正好落在两者之间就留下一个孤立代理。它编码成 UTF-8 时变成 `?` 或替换字符，严格的 JSON / 上游 API 还可能直接拒收整条请求
 * （Claude Code 2.1.309 修过同一个问题：长工具输出的预览从 emoji 中间截断）。
 *
 * 规则：截点落在「高代理 | 低代理」之间时，往**少保留**的方向挪一格——头部截断退一格，尾部截断进一格，
 * 所以结果长度不超过要求的上限，最多少一个 char。截点两边不是成对代理（含原文自带的孤立代理）时不动。
 */

/** 头部截断的终点：保留 `[0, end)`；[end] 落在代理对中间就退到 `end - 1`。[end] 先夹到 `[0, length]` */
fun CharSequence.safeCutEnd(end: Int): Int {
    val e = end.coerceIn(0, length)
    return if (splitsPair(e)) e - 1 else e
}

/** 尾部截断的起点：保留 `[start, length)`；[start] 落在代理对中间就进到 `start + 1`。[start] 先夹到 `[0, length]` */
fun CharSequence.safeCutStart(start: Int): Int {
    val s = start.coerceIn(0, length)
    return if (splitsPair(s)) s + 1 else s
}

/** 同 [String.take]，但不劈开代理对（可能比 [n] 少一个 char） */
fun String.safeTake(n: Int): String {
    require(n >= 0) { "n 不能为负：$n" }
    return if (n >= length) this else substring(0, safeCutEnd(n))
}

/** 同 [String.takeLast]，但不劈开代理对（可能比 [n] 少一个 char） */
fun String.safeTakeLast(n: Int): String {
    require(n >= 0) { "n 不能为负：$n" }
    return if (n >= length) this else substring(safeCutStart(length - n))
}

private fun CharSequence.splitsPair(index: Int): Boolean =
    index in 1 until length && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()
