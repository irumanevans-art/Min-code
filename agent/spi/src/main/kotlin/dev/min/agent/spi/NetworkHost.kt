package dev.min.agent.spi

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

fun normalizeNetworkHost(raw: String): String? {
        val host = raw.removeSurrounding("[", "]").trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty() || host.any { it.isWhitespace() || it in "/\\@?#%" }) return null
        if (':' in host) {
            if (!Regex("[0-9a-f:.]+").matches(host)) return null
            return try { InetAddress.getByName(host).hostAddress } catch (_: java.net.UnknownHostException) { null }
        }
        // JVM 与系统解析器对八进制、短 IPv4 和整数地址解释不一；规则和连接都只接受完整十进制。
        val labels = host.split('.')
        if (labels.all { it.all(Char::isDigit) || it.startsWith("0x") && it.drop(2).all { c -> c in "0123456789abcdef" } }) {
            if (labels.size != 4 || labels.any { !it.all(Char::isDigit) || it.length > 1 && it.startsWith('0') || it.toIntOrNull() !in 0..255 }) return null
            return labels.joinToString(".") { it.toInt().toString() }
        }
        return try { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) } catch (_: IllegalArgumentException) { null }
    }
