package dev.min.code.core.network

/**
 * 解析 Linux `/proc/net/tcp` 与 `/proc/net/tcp6` 的监听行。
 *
 * 格式（内核文档）：
 * ```
 * sl  local_address rem_address st ...
 *  0: 0100007F:1F90 00000000:0000 0A ...
 * ```
 * - IPv4 地址是小端十六进制（`7F000001` = 127.0.0.1 在文件里写成 `0100007F`）
 * - 端口是大端十六进制
 * - `st == 0A` 为 TCP_LISTEN
 *
 * 宿主 App 与 proot guest 共 netns，读得动这些文件时，列表就是设备上真实的监听端口。
 */
object ProcNetTcpParser {
    /** TCP_LISTEN */
    const val STATE_LISTEN = 0x0A

    data class ListenEntry(
        val port: Int,
        val localHost: String,
        val proto: String,
        val inode: Long?,
    )

    fun parseTcp(text: String): List<ListenEntry> = parse(text, ipv6 = false)

    fun parseTcp6(text: String): List<ListenEntry> = parse(text, ipv6 = true)

    fun parseBoth(tcp: String?, tcp6: String?): List<ListenEntry> {
        val out = ArrayList<ListenEntry>()
        if (tcp != null) out += parseTcp(tcp)
        if (tcp6 != null) out += parseTcp6(tcp6)
        return out
            .distinctBy { "${it.proto}/${it.localHost}/${it.port}" }
            .sortedWith(compareBy({ it.port }, { it.proto }, { it.localHost }))
    }

    private fun parse(text: String, ipv6: Boolean): List<ListenEntry> {
        val proto = if (ipv6) "tcp6" else "tcp"
        val result = ArrayList<ListenEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl")) continue
            val parts = line.split(WHITESPACE)
            // sl local rem st ... inode — 至少 10 列；sl 可能是 "0:" 或 "0"
            if (parts.size < 10) continue
            val localIdx = when {
                parts[0].endsWith(':') && parts[0].length <= 4 -> 1
                parts[0].contains(':') && parts[0].count { it == ':' } == 1 &&
                    parts[0].substringBefore(':').length <= 4 -> 0 // 极少见的 "0:addr:port" 粘连
                else -> 1
            }.let { idx ->
                // 标准布局：parts[1] = local_address
                if (parts.getOrNull(1)?.contains(':') == true) 1 else idx
            }
            val localField = parts.getOrNull(localIdx) ?: continue
            if (!localField.contains(':')) continue
            val stateField = parts.getOrNull(localIdx + 2) ?: continue
            val state = stateField.toIntOrNull(16) ?: continue
            if (state != STATE_LISTEN) continue

            val colon = localField.lastIndexOf(':')
            if (colon <= 0) continue
            val addrHex = localField.substring(0, colon)
            val portHex = localField.substring(colon + 1)
            val port = portHex.toIntOrNull(16) ?: continue
            if (port !in 1..65535) continue
            // IPv4 地址恒为 8 hex；IPv6 为 32。拒绝把 "0" 之类的 sl 残片当地址
            if (!ipv6 && addrHex.length != 8) continue
            if (ipv6 && addrHex.length !in 1..32) continue
            val host = if (ipv6) decodeIpv6(addrHex) else decodeIpv4(addrHex) ?: continue
            // local rem st tx_queue tr retrnsmt uid timeout inode
            val inode = parts.getOrNull(localIdx + 8)?.toLongOrNull()
            result += ListenEntry(port = port, localHost = host, proto = proto, inode = inode)
        }
        return result
    }

    /** `0100007F` → `127.0.0.1` */
    fun decodeIpv4(hex: String): String? {
        if (hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        val b0 = (value and 0xFF).toInt()
        val b1 = ((value shr 8) and 0xFF).toInt()
        val b2 = ((value shr 16) and 0xFF).toInt()
        val b3 = ((value shr 24) and 0xFF).toInt()
        return "$b0.$b1.$b2.$b3"
    }

    /**
     * tcp6 的 local_address 是 32 个 hex 字符，按 32-bit 小端字序排列
     * （与 IPv4 同款的 per-32bit LE）。全零 → `::`。
     */
    fun decodeIpv6(hex: String): String {
        val h = hex.padStart(32, '0').take(32)
        if (h.all { it == '0' }) return "::"
        val bytes = ByteArray(16)
        var i = 0
        while (i < 4) {
            val word = h.substring(i * 8, i * 8 + 8).toLongOrNull(16) ?: 0L
            val base = i * 4
            bytes[base] = (word and 0xFF).toByte()
            bytes[base + 1] = ((word shr 8) and 0xFF).toByte()
            bytes[base + 2] = ((word shr 16) and 0xFF).toByte()
            bytes[base + 3] = ((word shr 24) and 0xFF).toByte()
            i++
        }
        // 简化展示：每 16-bit 一组，不做完整 RFC5952 压缩（够辨认）
        val groups = (0 until 8).map { g ->
            val hi = bytes[g * 2].toInt() and 0xFF
            val lo = bytes[g * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toString(16)
        }
        // 映射常见回环 / 任意
        val joined = groups.joinToString(":")
        if (joined == "0:0:0:0:0:0:0:1") return "::1"
        if (groups.all { it == "0" }) return "::"
        return compressIpv6(groups)
    }

    private fun compressIpv6(groups: List<String>): String {
        // 找最长连续 0
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] != "0") {
                i++
                continue
            }
            var j = i
            while (j < groups.size && groups[j] == "0") j++
            val len = j - i
            if (len > bestLen) {
                bestStart = i
                bestLen = len
            }
            i = j
        }
        if (bestLen < 2) return groups.joinToString(":")
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return when {
            head.isEmpty() && tail.isEmpty() -> "::"
            head.isEmpty() -> "::$tail"
            tail.isEmpty() -> "$head::"
            else -> "$head::$tail"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
