package dev.min.agent.host

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.NetworkLease
import dev.min.agent.spi.NetworkOutcome
import dev.min.agent.spi.NetworkSource
import dev.min.agent.spi.NetworkVerdict
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Base64

/**
 * 用户在设置里填的上游代理（照 Telegram 的「应用内代理」：只管本应用的流量，不开 VPN、不动系统网络）。
 * [toString] 打码：账号密码不能跟着日志或崩溃信息出去。
 */
data class UpstreamProxy(
    val type: Type,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    enum class Type { HTTP, SOCKS5 }

    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "代理地址不合法" }
        require(port in 1..65535) { "代理端口不合法" }
        // RFC 1929：用户名、密码各占一个长度字节
        require(username.toByteArray(Charsets.UTF_8).size <= 255 && password.toByteArray(Charsets.UTF_8).size <= 255) { "代理账号或密码过长" }
    }

    val hasCredentials: Boolean get() = username.isNotEmpty()

    override fun toString(): String = "UpstreamProxy($type, $host:$port, auth=${if (hasCredentials) "***" else "none"})"
}

/**
 * 本进程所有出网的唯一代理开关。三条路各接一处：
 *
 * - `HttpURLConnection`（安装器、中转、更新、Min 引擎的模型 / MCP / hook 请求）：[install] 装的全局 [ProxySelector]
 *   只回答 http / https，回环地址一律直连；账号密码由全局 [Authenticator] 按代理的地址端口应答。
 * - 自己开 socket 的（Min 命令的网络租约、WebSocket 工具）：调 [dial]，HTTP CONNECT / SOCKS5 握手都在这里，
 *   SOCKS5 把**域名**交给代理去解析——国内本地 DNS 常被污染，先在本机解析再连 IP 等于代理白开。
 * - proot 里的进程（Claude Code、Codex、终端、npm、git）：[guestEnv] 指向本机一个带随机凭据的 HTTP 代理桥，
 *   由 `ProotShellRunner` 注入。要这层桥是因为 Claude Code 只认 HTTP(S)_PROXY、不支持 SOCKS，
 *   而且真账号密码不该进 guest 的环境变量；随机凭据挡住同机别的 App 借道。
 *
 * 桥一旦起来就活到进程结束、端口和凭据不变：已经在跑的会话手里是旧 env，换代理 / 关代理时桥按 [current] 现拨，
 * 关掉后它就直连，不会把正在跑的会话断网。
 */
object OutboundProxy {
    @Volatile var current: UpstreamProxy? = null
        private set

    /** 给 proot 进程的代理环境；没开代理时为空。只在宿主内存里，不进设置、事件或模型上下文 */
    @Volatile var guestEnv: Map<String, String> = emptyMap()
        private set

    private var bridge: LoopbackNetworkProxy? = null
    private var bridgeLease: NetworkLease? = null
    private var hooksInstalled = false

    @Synchronized fun install(proxy: UpstreamProxy?) {
        current = proxy
        if (proxy == null) { guestEnv = emptyMap(); return }
        installJvmHooks()
        val lease = bridgeLease ?: run {
            val created = LoopbackNetworkProxy(maxConnections = BRIDGE_MAX_CONNECTIONS, connect = { dial(it.domain, it.port, CONNECT_TIMEOUT_MS) })
            bridge = created
            created.open(SessionId(BRIDGE_SESSION)) { ALLOW }.also { bridgeLease = it }
        }
        guestEnv = lease.env
    }

    /**
     * 连到 [host]:[port]，开着代理就经 [via] 握手出去。返回的 socket 已建好隧道、`soTimeout` 为 0，调用方自己定读超时。
     * 回环地址永远直连：本机的中转、设备 MCP、代理桥本身都在 127.0.0.1。
     */
    fun dial(host: String, port: Int, timeoutMs: Int, via: UpstreamProxy? = current): Socket {
        val socket = Socket(Proxy.NO_PROXY)
        try {
            if (via == null || isLoopback(host)) {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                return socket
            }
            socket.connect(InetSocketAddress(via.host, via.port), timeoutMs)
            socket.soTimeout = timeoutMs
            when (via.type) {
                UpstreamProxy.Type.HTTP -> httpConnect(socket, via, host, port)
                UpstreamProxy.Type.SOCKS5 -> socks5Connect(socket, via, host, port)
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    /**
     * 设置页的「测试」：经 [proxy] 打通到 [host]:[port] 的隧道，再在隧道里和目标做完 TLS 握手才算通，返回耗时毫秒；不通抛 [IOException]。
     * 只看隧道不够：代理回「已连上」之后不转发数据、或被中间设备掐断的情况不少见。Telegram 的测速同样是经代理跑一个真实协议往返
     * （MTProto ping），不是只看代理握手。[tls] 只给测试替身关掉（本地回显服务器不会说 TLS）
     */
    fun probe(proxy: UpstreamProxy, host: String = PROBE_HOST, port: Int = 443, timeoutMs: Int = PROBE_TIMEOUT_MS, tls: Boolean = true): Long {
        val started = System.nanoTime()
        dial(host, port, timeoutMs, proxy).use { tunnel ->
            if (tls) {
                tunnel.soTimeout = timeoutMs
                val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(tunnel, host, port, true) as javax.net.ssl.SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                try {
                    ssl.use { it.startHandshake() }
                } catch (e: IOException) {
                    throw IOException("代理隧道已建立，但和 $host 的 TLS 握手失败（${e.javaClass.simpleName}）", e)
                }
            }
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    fun isLoopback(host: String): Boolean {
        val h = host.removeSurrounding("[", "]").lowercase()
        return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "0:0:0:0:0:0:0:1" || IPV4.matchEntire(h)?.groupValues?.get(1) == "127"
    }

    private fun installJvmHooks() {
        if (hooksInstalled) return
        hooksInstalled = true
        // 关代理时交还原来的选择器：系统 Wi-Fi 代理之类原有行为不变
        val fallback = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val proxy = current
                val scheme = uri.scheme?.lowercase()
                // 只接 http / https：java.net.Socket 也会拿 socket:// 来问，那条由 [dial] 自己管，这里再给就成了代理套代理
                if (proxy == null || (scheme != "http" && scheme != "https") || uri.host == null || isLoopback(uri.host)) {
                    return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
                val address = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
                return listOf(Proxy(if (proxy.type == UpstreamProxy.Type.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS, address))
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                fallback?.connectFailed(uri, sa, ioe)
            }
        })
        Authenticator.setDefault(object : Authenticator() {
            // 普通网站的 401 也会问到这里，只答代理自己的提问，账号密码不能给到目标站。
            // HTTP 代理的提问是 RequestorType.PROXY；SOCKS 的在 JDK 里是 SERVER 类型、协议名 SOCKS5，只能按协议名认
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val proxy = current?.takeIf { it.hasCredentials } ?: return null
                val asked = when (proxy.type) {
                    UpstreamProxy.Type.HTTP -> requestorType == RequestorType.PROXY
                    UpstreamProxy.Type.SOCKS5 -> requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true
                }
                if (!asked || requestingPort != proxy.port) return null
                return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
            }
        })
    }

    private fun httpConnect(socket: Socket, proxy: UpstreamProxy, host: String, port: Int) {
        val authority = (if (':' in host) "[$host]" else host) + ":$port"
        val auth = if (proxy.hasCredentials) {
            "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password}".toByteArray(Charsets.UTF_8)) + "\r\n"
        } else ""
        socket.getOutputStream().apply {
            write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${auth}\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        // 逐字节读到空行为止：多读一个字节就吃掉了隧道里对端的第一个 TLS 字节
        val input = socket.getInputStream()
        val status = readLine(input).split(' ')
        val code = status.getOrNull(1)?.toIntOrNull() ?: throw IOException("代理返回的不是 HTTP 响应")
        var lines = 0
        while (readLine(input).isNotEmpty()) if (++lines > 100) throw IOException("代理响应头过长")
        when {
            code in 200..299 -> Unit
            code == 407 -> throw IOException(if (proxy.hasCredentials) "代理账号或密码不对（HTTP 407）" else "代理要求账号密码（HTTP 407）")
            else -> throw IOException("代理拒绝连接（HTTP $code）")
        }
    }

    private fun socks5Connect(socket: Socket, proxy: UpstreamProxy, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(if (proxy.hasCredentials) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
        if (readByte(input) != 5) throw IOException("代理不是 SOCKS5")
        when (readByte(input)) {
            0 -> Unit
            2 -> {
                if (!proxy.hasCredentials) throw IOException("代理要求账号密码")
                val user = proxy.username.toByteArray(Charsets.UTF_8)
                val pass = proxy.password.toByteArray(Charsets.UTF_8)
                out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
                readByte(input)
                if (readByte(input) != 0) throw IOException("代理账号或密码不对")
            }
            else -> throw IOException(if (proxy.hasCredentials) "代理不接受账号密码认证" else "代理要求账号密码")
        }
        val bare = host.removeSurrounding("[", "]")
        val address = when {
            IPV4.matches(bare) -> byteArrayOf(1) + bare.split('.').map { it.toInt().toByte() }.toByteArray()
            ':' in bare -> byteArrayOf(4) + java.net.InetAddress.getByName(bare).address  // IPv6 字面量，不走 DNS
            else -> bare.toByteArray(Charsets.UTF_8).let { name ->
                if (name.size > 255) throw IOException("域名过长")
                byteArrayOf(3, name.size.toByte()) + name
            }
        }
        out.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
        if (readByte(input) != 5) throw IOException("代理不是 SOCKS5")
        val reply = readByte(input)
        readByte(input)
        val boundLength = when (readByte(input)) { 1 -> 4; 4 -> 16; 3 -> readByte(input); else -> throw IOException("代理响应不合法") }
        repeat(boundLength + 2) { readByte(input) }
        if (reply != 0) throw IOException("代理连不上目标：${SOCKS_REPLIES[reply] ?: "错误码 $reply"}")
    }

    private fun readByte(input: InputStream): Int = input.read().also { if (it < 0) throw IOException("代理提前断开了连接") }

    private fun readLine(input: InputStream): String {
        val text = StringBuilder()
        while (text.length < 8192) {
            when (val b = readByte(input)) {
                '\n'.code -> return text.toString().removeSuffix("\r")
                else -> text.append(b.toChar())
            }
        }
        throw IOException("代理响应头过长")
    }

    private val IPV4 = Regex("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    private val ALLOW = NetworkVerdict(NetworkOutcome.Allow, NetworkSource.User, "应用代理")
    private const val BRIDGE_SESSION = "min-outbound-proxy"
    // guest 里 npm install 会并发几十条连接；默认 32 是给单条命令租约的
    private const val BRIDGE_MAX_CONNECTIONS = 128
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val PROBE_TIMEOUT_MS = 8_000
    /** 测试连接的默认目标：Min 最常连的就是它，能连上说明代理对得上用途 */
    const val PROBE_HOST = "api.anthropic.com"
    // RFC 1928 §6
    private val SOCKS_REPLIES = mapOf(
        1 to "代理服务器故障", 2 to "代理规则不允许", 3 to "网络不可达", 4 to "主机不可达",
        5 to "目标拒绝连接", 6 to "超时", 7 to "代理不支持该命令", 8 to "代理不支持该地址类型",
    )
}
