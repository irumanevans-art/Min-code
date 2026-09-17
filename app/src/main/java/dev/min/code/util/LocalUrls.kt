package dev.min.code.util

/**
 * 本机闭环预览用的 loopback 判定与拼装。LAN 不在这里给人看。
 * 纯字符串实现，单测可在 JVM 跑（不依赖 android.net.Uri）。
 */
object LocalUrls {
    fun loopbackUrl(port: Int, scheme: String = "http"): String {
        val p = port.coerceIn(1, 65535)
        return "$scheme://127.0.0.1:$p/"
    }

    fun isLoopbackHttp(url: String): Boolean {
        val trimmed = url.trim()
        val schemeHost = SCHEME_HOST.matchEntire(trimmed) ?: SCHEME_HOST.find(trimmed) ?: return false
        val scheme = schemeHost.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = schemeHost.groupValues[2].lowercase().trim()
        return host == "127.0.0.1" ||
            host == "localhost" ||
            host == "::1" ||
            host == "[::1]" ||
            host == "0.0.0.0"
    }

    /** 把 localhost / 0.0.0.0 / ::1 归一成 127.0.0.1，便于 WebView 加载。 */
    fun normalizeLoopback(url: String): String {
        val trimmed = url.trim()
        val m = FULL_URL.find(trimmed) ?: return trimmed
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        val portPart = m.groupValues[3]
        val rest = m.groupValues[4]
        if (host != "localhost" && host != "0.0.0.0" && host != "::1" && host != "[::1]") {
            return trimmed
        }
        val port = portPart.removePrefix(":").toIntOrNull()
            ?: if (scheme == "https") 443 else 80
        val path = if (rest.isBlank()) "/" else rest
        return "$scheme://127.0.0.1:$port$path"
    }

    private val SCHEME_HOST = Regex(
        """^(https?)://(\[[^\]]+\]|[^/:]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val FULL_URL = Regex(
        """^(https?)://(\[[^\]]+\]|[^/:]+)(:\d{1,5})?([/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val LOCAL_URL_IN_TEXT = Regex(
        """https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[?::1\]?)(?::(\d{1,5}))?(?:/[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "listening on port 8765" / "Server started at :3000" / "running on 0.0.0.0:5173" 这类提示。
     *
     * 三道收紧，缺一不可 —— 一段 curl 回来的 WordPress CSS 把原来的三处宽松全踩中，
     * 预览槽弹出 `127.0.0.1:100`（`max-width:100%` 里取出来的）：
     * 1. 关键词整词（`\b`）。挡的是 `inbound` / `restarted`；**挡不住** `--wp-bound-block-color`
     *    —— 连字符是非词字符，`\bbound\b` 在那里照样成立，那条要靠下面两条挡。
     * 2. 关键词到端口之间最多 80 个非换行字符，不能跨半页 CSS 去找一个冒号。
     * 3. 裸 `:N` 只在前面是空白或 loopback 主机时算端口（`max-width:100%`、`08:24:26` 都不算），
     *    `port N` 形式照旧；数字后面不能再接 `%` `.` 数字或字母。
     */
    private val PORT_HINT = Regex(
        """\b(?:listening|running|serving|bound|started)\b[^\n]{0,80}?""" +
            """(?:\bport\s+(\d{2,5})|(?:(?<=\s)|127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1?\]|\*):(\d{2,5}))(?![%.\d\w])""",
        RegexOption.IGNORE_CASE,
    )

    /** 从工具输出里捞 loopback URL 或 "on port N"。 */
    fun findLocalPreviewUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        LOCAL_URL_IN_TEXT.findAll(text).forEach { m ->
            found += normalizeLoopback(m.value.trimEnd('.', ',', ';', ')', ']'))
        }
        if (found.isEmpty()) {
            PORT_HINT.findAll(text).forEach { m ->
                // 两个捕获组：1 = "port N"，2 = 裸 ":N"。没命中的那个是空串
                val port = (
                    m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        ?: m.groupValues.getOrNull(2)
                    )?.toIntOrNull() ?: return@forEach
                if (port in 1..65535) found += loopbackUrl(port)
            }
        }
        return found.toList()
    }

    fun extractPort(url: String): Int? {
        val m = FULL_URL.find(url.trim()) ?: return null
        return m.groupValues[3].removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
