package dev.min.code.ui.session

import dev.min.code.ui.files.sanitizeFileName

/**
 * 导出会话时建议的文件名：`min-session-<标题>.jsonl`，没有正经标题时用 id 前 8 位。
 *
 * 「正经标题」只算 CLI 拟过的名、`/rename` 或人手改过的（[titled]）。没拟过名的会话，
 * 列表上显示的是第一条消息的截断——那是对话内容，可能是一段私密的话或者一整句报错，
 * 不该被写进文件名、留在系统文件管理器和最近文件列表里。
 * 清字符和截断复用文件页导出 zip 的那一份 [sanitizeFileName]。
 */
fun sessionExportFileName(title: String?, titled: Boolean, sessionId: String): String {
    val fallback = sessionId.take(ID_PREFIX_LENGTH)
    val stem = if (titled && !title.isNullOrBlank()) {
        sanitizeFileName(title).takeIf { it != EMPTY_STEM || title.trim() == EMPTY_STEM } ?: fallback
    } else {
        fallback
    }
    return "$PREFIX$stem$EXTENSION"
}

private const val PREFIX = "min-session-"
private const val EXTENSION = ".jsonl"
private const val ID_PREFIX_LENGTH = 8

/** [sanitizeFileName] 清完是空串时给的占位；撞上它说明标题里没有一个能用的字符，退回 id */
private const val EMPTY_STEM = "export"
