package dev.min.code.ui.session

/**
 * 时长。秒级以内给整秒，超过一分钟给 `1m32s` —— 手机上这一栏只有几十 dp 宽，
 * 写成 `92 秒` 反而要在脑子里再换算一次。
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60) return if (seconds == 0L) "${minutes}m" else "${minutes}m${seconds}s"
    val hours = minutes / 60
    return "${hours}h${minutes % 60}m"
}

/** token 数。上千收成 `1.2k`，四位数字在这一栏里只会把行挤爆 */
internal fun formatTokens(count: Int): String =
    if (count < 1000) count.toString()
    else String.format(java.util.Locale.US, "%.1fk", count / 1000.0)

/**
 * 记住最后一个非空值。给"退场动画"用：AnimatedVisibility 在收起的那几百毫秒里还要能
 * 画出刚才的内容，而状态本身这时已经空了。不是 State——它只在同一次重组里被读，
 * 不需要自己触发重组。
 */
internal class LastNonNull<T : Any>(initial: T?) {
    var value: T? = initial
    fun update(next: T?): T? {
        if (next != null) value = next
        return value
    }
}
