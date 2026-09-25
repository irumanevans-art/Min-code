package dev.min.code.ui.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 一个有序列表的编号：从 [start] 起逐项 +1，分隔符沿用原文（`.` 或 `)`）。
 *
 * CommonMark 只认首项的数字作起始，后面各项原文写的是几都不管（`1. 1. 1.` 渲染成 1 2 3），
 * 所以这里只读首项的 LIST_NUMBER，其余按位置递增。分隔符换了（`.` ↔ `)`）解析器会另起一个列表，
 * 同一个列表里的分隔符一定一致。
 */
internal data class OrderedListNumbering(val start: Int, val delimiter: Char, val count: Int) {
    /** 第 [position] 项（从 0 数）的序号文本。任务框项也占一个号，和 CommonMark 数项的方式一致 */
    fun marker(position: Int): String = "${start + position}$delimiter"

    /** 位数最多的那个序号 = 最后一项（起始数非负，只增不减）；序号列按它量宽 */
    val widestMarker: String get() = marker((count - 1).coerceAtLeast(0))

    companion object {
        fun of(list: ASTNode, text: String): OrderedListNumbering {
            val items = list.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
            val raw = items.firstOrNull()
                ?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                ?.let { text.substring(it.startOffset, it.endOffset).trim() }
                .orEmpty()
            val delimiter = raw.lastOrNull()?.takeIf { it == '.' || it == ')' } ?: '.'
            // CommonMark 限 1–9 位数字，Int 装得下；解析不出来就退回从 1 数
            val start = raw.trimEnd('.', ')').toIntOrNull() ?: 1
            return OrderedListNumbering(start, delimiter, items.size)
        }
    }
}
