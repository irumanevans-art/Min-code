package dev.min.code.ui.session

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DATED_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 回执末尾的完成时刻，像终端提示符那样只给钟点。
 * 翻历史会话时光有钟点分不清是哪天，所以不是今天的补上月-日。
 */
internal fun formatReceiptClock(
    at: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (if (time.toLocalDate() == today) CLOCK else DATED_CLOCK).format(time)
}
