package dev.min.code.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.getSystemService
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "NetworkProbe"

enum class AddressKind {
    /** 127.0.0.1 / ::1 — 仅本机浏览器 */
    Loopback,
    /** 169.254/16、fe80::/10 */
    LinkLocal,
    /** RFC1918 等可作「同一 Wi-Fi 上手机打开」的候选 */
    Lan,
    /** 公网、CGNAT 100.64/10、其它 */
    Other,
}

data class NetAddress(
    val host: String,
    val kind: AddressKind,
    val iface: String,
    val primary: Boolean = false,
)

data class ListeningPort(
    val port: Int,
    val proto: String,
    val localHost: String,
    val inode: Long? = null,
    val state: String = "LISTEN",
)

data class NetworkSnapshot(
    val addresses: List<NetAddress>,
    val ports: List<ListeningPort>,
    val portsReadable: Boolean,
    val primaryLanHost: String?,
    val collectedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val lanHosts: List<String>
        get() = addresses.filter { it.kind == AddressKind.Lan }.map { it.host }.distinct()
}

/**
 * 主机侧网络白名单探针：地址分类 +（可读时）监听端口。
 *
 * 不依赖 guest 里的 `ip`/`ss`，也不要求 `/proc/net` 对 SELinux 永远放行——
 * 端口读不到就诚实说读不到，链接仍可按用户填的端口生成。
 */
class NetworkProbe(
    private val context: Context,
) {
    fun snapshot(): NetworkSnapshot {
        val addresses = collectAddresses()
        val (ports, readable) = collectPorts()
        val primary = addresses.firstOrNull { it.primary }?.host
            ?: addresses.firstOrNull { it.kind == AddressKind.Lan }?.host
        return NetworkSnapshot(
            addresses = addresses,
            ports = ports,
            portsReadable = readable,
            primaryLanHost = primary,
        )
    }

    fun phoneUrl(port: Int, scheme: String = "http"): String? {
        if (port !in 1..65535) return null
        val host = snapshot().primaryLanHost ?: return null
        return formatUrl(scheme, host, port)
    }

    fun loopbackUrl(port: Int, scheme: String = "http"): String =
        formatUrl(scheme, "127.0.0.1", port.coerceIn(1, 65535))

    fun urlsForPort(port: Int, scheme: String = "http"): List<Pair<String, String>> {
        if (port !in 1..65535) return emptyList()
        val snap = snapshot()
        val out = ArrayList<Pair<String, String>>(4)
        out += "loopback" to formatUrl(scheme, "127.0.0.1", port)
        snap.primaryLanHost?.let { out += "lan" to formatUrl(scheme, it, port) }
        for (host in snap.lanHosts) {
            if (host == snap.primaryLanHost) continue
            out += "lan" to formatUrl(scheme, host, port)
        }
        return out.distinctBy { it.second }
    }

    private fun collectAddresses(): List<NetAddress> {
        val raw = ArrayList<Triple<String, String, InetAddress>>()

        // 1) NetworkInterface — 覆盖 wlan/eth/USB/软 AP
        runCatching {
            val en = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) {
                    // loopback 仍要：用户需要分清 127.0.0.1
                    if (!nif.isLoopback) continue
                }
                val name = nif.name ?: continue
                if (shouldSkipIface(name) && !nif.isLoopback) continue
                val addrs = nif.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet6Address && a.isLinkLocalAddress) continue // 带 scope 的 fe80 对拼 URL 没用
                    raw += Triple(name, displayHost(a), a)
                }
            }
        }.onFailure { Log.w(TAG, "NetworkInterface enum failed", it) }

        // 2) ConnectivityManager 活动链路补漏（有的 ROM 枚举不全）
        runCatching {
            val cm = context.applicationContext.getSystemService<ConnectivityManager>() ?: return@runCatching
            val network = cm.activeNetwork ?: return@runCatching
            val lp = cm.getLinkProperties(network) ?: return@runCatching
            val iface = lp.interfaceName ?: "active"
            for (la in lp.linkAddresses) {
                val a = la.address ?: continue
                if (a is Inet6Address && a.isLinkLocalAddress) continue
                val host = displayHost(a)
                if (raw.none { it.second == host }) {
                    raw += Triple(iface, host, a)
                }
            }
        }.onFailure { Log.w(TAG, "LinkProperties failed", it) }

        if (raw.none { it.third.isLoopbackAddress }) {
            raw += Triple("lo", "127.0.0.1", InetAddress.getByName("127.0.0.1"))
        }

        val classified = raw.map { (iface, host, inet) ->
            NetAddress(
                host = host,
                kind = classify(inet, host),
                iface = iface,
                primary = false,
            )
        }
            // 去重：同一 host 保留更「像网卡」的 iface
            .groupBy { it.host }
            .map { (_, group) -> group.minBy { ifacePriority(it.iface) } }
            .sortedWith(
                compareBy<NetAddress> { kindOrder(it.kind) }
                    .thenBy { ifacePriority(it.iface) }
                    .thenBy { it.host },
            )

        val primaryHost = pickPrimaryLan(classified)?.host
        return classified.map { if (it.host == primaryHost && it.kind == AddressKind.Lan) it.copy(primary = true) else it }
    }

    private fun collectPorts(): Pair<List<ListeningPort>, Boolean> {
        val tcp = readProcNet("tcp")
        val tcp6 = readProcNet("tcp6")
        if (tcp == null && tcp6 == null) return emptyList<ListeningPort>() to false
        val entries = ProcNetTcpParser.parseBoth(tcp, tcp6)
        val ports = entries.map {
            ListeningPort(
                port = it.port,
                proto = it.proto,
                localHost = it.localHost,
                inode = it.inode,
            )
        }
        return ports to true
    }

    private fun readProcNet(name: String): String? {
        val file = File("/proc/net/$name")
        return runCatching {
            if (!file.canRead()) return null
            file.readText()
        }.onFailure {
            Log.i(TAG, "cannot read /proc/net/$name: ${it.message}")
        }.getOrNull()
    }

    companion object {
        fun formatUrl(scheme: String, host: String, port: Int): String {
            val s = scheme.ifBlank { "http" }
            val h = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$s://$h:$port"
        }

        fun classify(address: InetAddress, host: String = address.hostAddress ?: ""): AddressKind {
            if (address.isLoopbackAddress || host == "127.0.0.1" || host == "::1") {
                return AddressKind.Loopback
            }
            if (address.isLinkLocalAddress) return AddressKind.LinkLocal
            if (address is Inet4Address) {
                val b = address.address
                if (b.size == 4) {
                    val a0 = b[0].toInt() and 0xFF
                    val a1 = b[1].toInt() and 0xFF
                    // RFC1918
                    if (a0 == 10) return AddressKind.Lan
                    if (a0 == 172 && a1 in 16..31) return AddressKind.Lan
                    if (a0 == 192 && a1 == 168) return AddressKind.Lan
                    // 链路本地
                    if (a0 == 169 && a1 == 254) return AddressKind.LinkLocal
                    // CGNAT — 不当作「手机同网可连」的主推
                    if (a0 == 100 && a1 in 64..127) return AddressKind.Other
                    // 模拟器 host 特有 10.0.2.15 已在 10/8 Lan
                }
            }
            if (address is Inet6Address) {
                // Unique local fc00::/7
                val b0 = address.address[0].toInt() and 0xFF
                if (b0 and 0xFE == 0xFC) return AddressKind.Lan
            }
            return AddressKind.Other
        }

        /** 纯主机字符串分类（单测 / 无 InetAddress 时） */
        fun classifyHost(host: String): AddressKind {
            val h = host.trim().removePrefix("[").removeSuffix("]")
            if (h == "127.0.0.1" || h == "::1" || h == "localhost") return AddressKind.Loopback
            if (h.startsWith("fe80:", ignoreCase = true)) return AddressKind.LinkLocal
            val v4 = h.split('.')
            if (v4.size == 4) {
                val a = v4.mapNotNull { it.toIntOrNull() }
                if (a.size == 4 && a.all { it in 0..255 }) {
                    val fake = byteArrayOf(a[0].toByte(), a[1].toByte(), a[2].toByte(), a[3].toByte())
                    return classify(InetAddress.getByAddress(fake), h)
                }
            }
            return AddressKind.Other
        }

        fun pickPrimaryLan(addresses: List<NetAddress>): NetAddress? {
            val lan = addresses.filter { it.kind == AddressKind.Lan }
            if (lan.isEmpty()) return null
            return lan.minWith(
                compareBy<NetAddress> { ifacePriority(it.iface) }
                    .thenBy { if (it.host.contains(':')) 1 else 0 } // IPv4 优先
                    .thenBy { it.host },
            )
        }

        fun ifacePriority(name: String): Int {
            val n = name.lowercase()
            return when {
                n.startsWith("wlan") || n.startsWith("wifi") -> 0
                n.startsWith("eth") -> 1
                n.startsWith("ap") || n.startsWith("softap") -> 2
                n.startsWith("en") -> 3 // en0 等
                n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> 8
                n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") -> 9
                n.startsWith("dummy") || n.startsWith("lo") -> 10
                else -> 5
            }
        }

        private fun kindOrder(kind: AddressKind): Int = when (kind) {
            AddressKind.Lan -> 0
            AddressKind.Loopback -> 1
            AddressKind.LinkLocal -> 2
            AddressKind.Other -> 3
        }

        private fun shouldSkipIface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("dummy") ||
                n.startsWith("ifb") ||
                n.startsWith("docker") ||
                n.startsWith("veth") ||
                n.startsWith("br-")
        }

        private fun displayHost(address: InetAddress): String {
            val raw = address.hostAddress ?: return address.toString()
            // 去掉 IPv6 zone id（%wlan0）
            val percent = raw.indexOf('%')
            return if (percent >= 0) raw.substring(0, percent) else raw
        }
    }
}
