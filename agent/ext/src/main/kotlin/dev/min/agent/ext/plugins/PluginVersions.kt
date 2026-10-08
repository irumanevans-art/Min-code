package dev.min.agent.ext.plugins

/** 缓存版本按数字段选择；预发布低于同版正式发布，未知格式只做稳定的字面回退。 */
internal fun <T> compareBySemanticVersion(version: (T) -> String): Comparator<T> = Comparator { a, b ->
    val left = version(a).removePrefix("v").substringBefore('+')
    val right = version(b).removePrefix("v").substringBefore('+')
    val l = left.substringBefore('-').split('.').map { it.toLongOrNull() }
    val r = right.substringBefore('-').split('.').map { it.toLongOrNull() }
    var result = 0
    if (l.any { it == null } || r.any { it == null }) result = left.compareTo(right)
    else for (i in 0 until maxOf(l.size, r.size)) {
        result = (l.getOrNull(i) ?: 0L).compareTo(r.getOrNull(i) ?: 0L)
        if (result != 0) break
    }
    if (result == 0) {
        val lp = left.substringAfter('-', "")
        val rp = right.substringAfter('-', "")
        result = when { lp.isEmpty() && rp.isNotEmpty() -> 1; rp.isEmpty() && lp.isNotEmpty() -> -1; else -> lp.compareTo(rp) }
    }
    result
}
