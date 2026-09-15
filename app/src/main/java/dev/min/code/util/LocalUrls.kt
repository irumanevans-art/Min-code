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

    private val PORT_HINT = Regex(
        """(?:listening|running|serving|bound|started).*?(?:on\s+)?(?:port\s+|:)(\d{2,5})\b""",
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
                val port = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
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
