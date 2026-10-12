package dev.min.code.core.settings

import dev.min.agent.host.OutboundProxy
import dev.min.agent.host.UpstreamProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder

/** 应用内代理的类型。默认 [OFF]：直连 */
enum class ProxyKind { OFF, HTTP, SOCKS5 }

/**
 * 应用内代理（照 Telegram：只代理 Min 自己，不开 VPN、不影响手机上别的 App）。生效方式见 [OutboundProxy]。
 * 整块经 Keystore 加密后存进 DataStore（与 WebSearch 同一套），**密码只显示「已填」、不回显**。
 * 关掉（[OFF]）时服务器和账号原样留着，再打开不用重填。[toString] 打码，理由同 [WebSearchConfig]。
 */
@Serializable
data class ProxyConfig(
    val kind: ProxyKind = ProxyKind.OFF,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** 真正要用的上游；关着或没填全（地址 / 端口不合法）都是 null = 直连 */
    fun upstream(): UpstreamProxy? = when (kind) {
        ProxyKind.OFF -> null
        ProxyKind.HTTP -> build(UpstreamProxy.Type.HTTP)
        ProxyKind.SOCKS5 -> build(UpstreamProxy.Type.SOCKS5)
    }

    private fun build(type: UpstreamProxy.Type) =
        try { UpstreamProxy(type, host, port, username, password) } catch (_: IllegalArgumentException) { null }

    override fun toString(): String =
        "ProxyConfig(kind=$kind, host=${if (host.isBlank()) "unset" else "***"}, port=$port, auth=${if (username.isBlank()) "none" else "***"})"
}

/**
 * 对话框一次保存的内容。密码输入框**留空 = 保持已填的不变**（界面不回显），要清掉得点「清除」；新输入和清除同时给，以新输入为准。
 */
data class ProxyEdit(
    val kind: ProxyKind,
    val host: String,
    val port: Int,
    val username: String,
    val newPassword: String = "",
    val clearPassword: Boolean = false,
)

fun ProxyConfig.applying(edit: ProxyEdit): ProxyConfig = ProxyConfig(
    kind = edit.kind,
    host = asciiHost(edit.host.trim()),
    port = edit.port,
    username = edit.username.trim(),
    // 换了账号却没填新密码时清掉旧密码：旧密码属于旧账号，带着它去认证新账号只会得到一个莫名其妙的「密码不对」
    password = edit.newPassword.ifEmpty { if (edit.clearPassword || edit.username.trim() != username) "" else password },
)

/** 端口输入框的校验：1–65535，否则 null */
fun parseProxyPort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * 把常见的代理链接拆开填进对话框：`socks5://user:pass@host:1080`、`http://host:7890`、`socks://…`（Clash 等导出的写法），
 * 以及 Telegram 的 SOCKS5 分享链接。认不出来就 null，输入框照原样留着。
 * Telegram 的 MTProto 代理（`tg://proxy`）不是通用协议，Min 用不了，也返回 null。
 */
fun parseProxyLink(raw: String): ProxyConfig? {
    val uri = try { URI(raw.trim()) } catch (_: java.net.URISyntaxException) { return null }
    val scheme = uri.scheme?.lowercase() ?: return null
    telegramSocksQuery(uri, scheme)?.let { rawQuery ->
        val query = rawQuery.split('&').associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        // Telegram 自己（ProxySettings.fromUri）也认 host= 这个别名
        val server = query["server"] ?: query["host"] ?: return null
        return ProxyConfig(ProxyKind.SOCKS5, asciiHost(server), parseProxyPort(query["port"].orEmpty()) ?: return null,
            query["user"].orEmpty(), query["pass"].orEmpty()).takeIf { it.upstream() != null }
    }
    val kind = when (scheme) {
        // https:// 是「到代理这段也走 TLS」，Min 不支持，与其悄悄降成明文不如认不出来
        "http" -> ProxyKind.HTTP
        "socks", "socks5", "socks5h" -> ProxyKind.SOCKS5
        else -> return null
    }
    val host = uri.host?.removeSurrounding("[", "]") ?: return null
    val port = uri.port.takeIf { it > 0 } ?: if (kind == ProxyKind.HTTP) 80 else 1080
    val userInfo = uri.rawUserInfo?.split(':', limit = 2)
    return ProxyConfig(kind, host, port,
        userInfo?.getOrNull(0)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty(),
        userInfo?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()).takeIf { it.upstream() != null }
}

/**
 * Telegram 的 SOCKS5 分享链接的查询串；不是这种链接返回 null。写法照 Telegram 的 `ProxySettings.fromUri`：
 * `tg://socks?…`、不带 `//` 的 `tg:socks?…`，以及 t.me / telegram.me / telegram.dog 的 `/socks?…`
 */
private fun telegramSocksQuery(uri: URI, scheme: String): String? = when (scheme) {
    // 两种 tg 写法的 SSP 分别是 `//socks?…` 与 `socks?…`（层级 URI 的 SSP 含查询串）
    "tg" -> uri.rawSchemeSpecificPart.removePrefix("//").takeIf { it.startsWith("socks?", ignoreCase = true) }?.substringAfter('?')
    "http", "https" -> uri.rawQuery.takeIf { uri.host?.lowercase() in TELEGRAM_WEB_HOSTS && uri.rawPath.equals("/socks", ignoreCase = true) }
    else -> null
}

private val TELEGRAM_WEB_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

/** 中文等国际化域名转成 punycode（Telegram 同样先 IDN.toASCII）：系统解析器和 SOCKS5 的域名字段都只收 ASCII */
private fun asciiHost(host: String): String =
    if (host.all { it.code < 128 }) host else try { java.net.IDN.toASCII(host) } catch (_: IllegalArgumentException) { host }

/** 「测试」的结果：通了带耗时，不通带原因 */
sealed interface ProxyProbe {
    data class Ok(val millis: Long) : ProxyProbe
    data class Failed(val reason: String) : ProxyProbe
}

suspend fun probeProxy(proxy: UpstreamProxy): ProxyProbe = withContext(Dispatchers.IO) {
    try {
        ProxyProbe.Ok(OutboundProxy.probe(proxy))
    } catch (e: java.io.IOException) {
        // 系统异常的 message 只有地址端口（如 "Connection refused"），账号密码从不进异常信息
        ProxyProbe.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }
}

private val configJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal fun encodeProxyJson(config: ProxyConfig): String = configJson.encodeToString(config)

/** 解不出来当没配过：宁可让用户重填，也不能让设置页或启动炸在解析上 */
internal fun decodeProxyJson(json: String): ProxyConfig {
    if (json.isBlank()) return ProxyConfig()
    return try {
        configJson.decodeFromString<ProxyConfig>(json)
    } catch (_: IllegalArgumentException) {
        ProxyConfig()
    }
}
