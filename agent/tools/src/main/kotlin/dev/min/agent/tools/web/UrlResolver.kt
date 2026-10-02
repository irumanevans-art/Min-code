package dev.min.agent.tools.web

/*
 * 手写的相对地址解析：只做字符串扫描，不引库、不碰网络。
 * WebFetch 拿到的 baseUrl 是页面自己的地址，页面里的链接和图片十有八九是相对地址，不解析的话模型看到的全是碎片。
 */

internal object UrlResolver {

    /**
     * 把 [ref] 按 [baseUrl] 解析成绝对地址。[baseUrl] 为 null、[ref] 本身是绝对地址（自带 scheme，如 http: mailto: javascript:）
     * 或 baseUrl 连 scheme://host 都看不出来时，原样返回。
     */
    fun resolve(baseUrl: String?, ref: String): String {
        val r = ref.trim()
        if (baseUrl == null || r.isEmpty() || hasScheme(r)) return r
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd <= 0) return r
        val scheme = baseUrl.substring(0, schemeEnd) + ":"
        val rest = baseUrl.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val host = if (slash < 0) rest else rest.substring(0, slash)
        // 后面所有拼 host 的分支都要补上 "//"，不然会产出 "https:e.com/x" 这种坏地址
        val authority = "//$host"
        val pathAndMore = if (slash < 0) "" else rest.substring(slash)
        // 相对路径只对着 path 拼合，baseUrl 自带的 query/fragment 不参与
        val path = pathAndMore.substringBefore('?').substringBefore('#')
        val dir = if (path.endsWith("/")) path else path.substringBeforeLast('/') + "/"
        return when {
            r.startsWith("//") -> scheme + r
            r.startsWith("/") -> scheme + authority + r
            r.startsWith("#") -> scheme + authority + path + r
            else -> scheme + authority + mergeRelative(dir, r)
        }
    }

    /** ref 自己带 scheme（http:、mailto:、data:…）就算绝对地址；至少要有一个字母再跟冒号才算 */
    private fun hasScheme(ref: String): Boolean {
        if (ref.isEmpty() || !ref[0].isLetter()) return false
        var i = 1
        while (i < ref.length && (ref[i].isLetterOrDigit() || ref[i] == '+' || ref[i] == '-' || ref[i] == '.')) i++
        return i >= 2 && i < ref.length && ref[i] == ':'
    }

    /** 目录 + 相对段：处理 ./ 与 ../，其他段原样接上 */
    private fun mergeRelative(dir: String, ref: String): String {
        val segments = ArrayDeque(dir.split('/').filter { it.isNotEmpty() })
        val parts = ref.split('/')
        for ((index, part) in parts.withIndex()) {
            when {
                part == "." -> {}
                part == ".." -> segments.removeLastOrNull()
                part.isEmpty() && index != parts.lastIndex -> {}
                else -> segments.addLast(part)
            }
        }
        val joined = segments.joinToString("/")
        return "/" + joined + if (ref.endsWith("/") && !joined.endsWith("/")) "/" else ""
    }
}
