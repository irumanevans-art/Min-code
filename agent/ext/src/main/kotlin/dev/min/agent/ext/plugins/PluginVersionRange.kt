package dev.min.agent.ext.plugins

internal fun comparePluginVersions(a: String, b: String): Int {
    val x = a.removePrefix("v").substringBefore('+').split('-', limit = 2)
    val y = b.removePrefix("v").substringBefore('+').split('-', limit = 2)
    val left = x[0].split('.').map(String::toLong); val right = y[0].split('.').map(String::toLong)
    for (i in 0..2) left[i].compareTo(right[i]).takeIf { it != 0 }?.let { return it }
    if (x.size == 1 || y.size == 1) return when { x.size == y.size -> 0; x.size == 1 -> 1; else -> -1 }
    val p = x[1].split('.'); val q = y[1].split('.')
    for (i in 0 until minOf(p.size, q.size)) {
        val m = p[i].toLongOrNull(); val n = q[i].toLongOrNull()
        val c = when { m != null && n != null -> m.compareTo(n); m != null -> -1; n != null -> 1; else -> p[i].compareTo(q[i]) }
        if (c != 0) return c
    }
    return p.size.compareTo(q.size)
}

/** 依赖的约束按数字段与预发布比较；缓存的内容散列不能被误当作作者的预发布版本。 */
internal fun pluginVersionSatisfies(version: String?, range: String): Boolean {
    if (version == null) return false
    val parsed = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(version) ?: return false
    data class V(val parts: List<Long>, val pre: String = "")
    if ((1..3).any { parsed.groupValues[it].toLongOrNull() == null || parsed.groupValues[it].length > 1 && parsed.groupValues[it].startsWith('0') }) return false
    val actual = V((1..3).map { parsed.groupValues[it].toLong() }, parsed.groupValues[4])
    fun compare(a: V, b: V): Int {
        for (i in 0..2) a.parts[i].compareTo(b.parts[i]).takeIf { it != 0 }?.let { return it }
        if (a.pre.isEmpty() || b.pre.isEmpty()) return when { a.pre == b.pre -> 0; a.pre.isEmpty() -> 1; else -> -1 }
        val x = a.pre.split('.'); val y = b.pre.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val xn = x[i].toLongOrNull(); val yn = y[i].toLongOrNull()
            val c = when { xn != null && yn != null -> xn.compareTo(yn); xn != null -> -1; yn != null -> 1; else -> x[i].compareTo(y[i]) }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
    fun partial(text: String): Pair<V, Int> {
        val pieces = text.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        require(pieces.size <= 3 && pieces.all { it in setOf("*", "x", "X") || it.toLongOrNull() != null }) { "has an invalid version requirement" }
        val length = pieces.takeWhile { it.toLongOrNull() != null }.size
        require(pieces.drop(length).all { it in setOf("*", "x", "X") } && pieces.take(length).all { it.toLong() >= 0 && (it.length == 1 || !it.startsWith('0')) }) { "has an invalid version requirement" }
        return V((0..2).map { pieces.getOrNull(it)?.toLongOrNull() ?: 0 }, text.substringAfter('-', "").substringBefore('+')) to length
    }
    fun next(v: V, index: Int) = V(v.parts.mapIndexed { i, n -> when { i < index -> n; i == index -> n + 1; else -> 0 } })
    require(range.length <= 2048) { "has version requirements too complex to intersect" }
    return range.split("||").any { raw ->
        val branch = raw.trim()
        if (actual.pre.isNotEmpty() && !Regex("(\\d+)\\.(\\d+)\\.(\\d+)-").findAll(branch).any { match -> (1..3).map { match.groupValues[it].toLongOrNull() } == actual.parts }) return@any false
        val hyphen = Regex("^(\\S+)\\s+-\\s+(\\S+)$").matchEntire(branch)
        if (hyphen != null) {
            val (lo, _) = partial(hyphen.groupValues[1]); val (hi, n) = partial(hyphen.groupValues[2])
            return@any compare(actual, lo) >= 0 && if (n == 3) compare(actual, hi) <= 0 else compare(actual, next(hi, maxOf(0, n - 1))) < 0
        }
        val tokens = branch.replace(Regex("([<>~=^]+)\\s+"), "$1").split(Regex("\\s+")).filter { it.isNotEmpty() }
        tokens.all { token ->
            val match = Regex("^(<=|>=|<|>|=|~|\\^)?(.*)$").matchEntire(token)!!
            val op = match.groupValues[1]; val body = match.groupValues[2]
            if (body.isEmpty() || body in setOf("*", "x", "X")) return@all true
            val (bound, n) = partial(body); val cmp = compare(actual, bound)
            when(op) {
                ">=" -> cmp >= 0
                ">" -> if (n == 3) cmp > 0 else compare(actual, next(bound, maxOf(0, n - 1))) >= 0
                "<" -> cmp < 0
                "<=" -> if (n == 3) cmp <= 0 else compare(actual, next(bound, maxOf(0, n - 1))) < 0
                "~" -> cmp >= 0 && compare(actual, next(bound, if (n <= 1) 0 else 1)) < 0
                "^" -> cmp >= 0 && compare(actual, next(bound, when { bound.parts[0] != 0L || n == 1 -> 0; bound.parts[1] != 0L || n == 2 -> 1; else -> 2 })) < 0
                else -> if (n == 3) cmp == 0 else cmp >= 0 && compare(actual, next(bound, maxOf(0, n - 1))) < 0
            }
        }
    }
}
