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
        result = when {
            lp.isEmpty() && rp.isNotEmpty() -> 1
            rp.isEmpty() && lp.isNotEmpty() -> -1
            else -> {
                val ls = lp.split('.'); val rs = rp.split('.')
                var order = 0
                for (i in 0 until minOf(ls.size, rs.size)) {
                    val ln = ls[i].toBigIntegerOrNull(); val rn = rs[i].toBigIntegerOrNull()
                    // SemVer 的预发布数字标识按数值比较，数字标识低于非数字标识。
                    order = when {
                        ln != null && rn != null -> ln.compareTo(rn)
                        ln != null -> -1
                        rn != null -> 1
                        else -> ls[i].compareTo(rs[i])
                    }
                    if (order != 0) break
                }
                if (order == 0) ls.size.compareTo(rs.size) else order
            }
        }
    }
    result
}
