package dev.min.agent.spi

import java.net.URI
import java.util.Locale

/** 在工具校验与宿主跳转处共用，防止跳转绕过初始 URL 的限制。 */
object WebUrl {
    fun normalize(value: String): String {
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("url 必须是完整的 HTTP 或 HTTPS URL。") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "url 必须是完整的 HTTP 或 HTTPS URL。" }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        require(!host.isNullOrBlank()) { "url 必须包含有效主机名。" }
        require(host != "localhost" && !host.endsWith(".localhost") && '.' in host) {
            "WebFetch 不能访问 localhost 或不含点的主机名，请用 Bash 的 curl 访问本地服务。"
        }
        require(uri.userInfo == null) { "url 不能包含用户名或密码。" }
        // 使用原始 authority/path 保留百分号编码，避免签名 URL 被二次编码。
        return "https:" + value.substring(value.indexOf(':') + 1).substringBefore('#')
    }

    fun host(url: String): String = URI(url).host.lowercase(Locale.ROOT).trimEnd('.')
}
