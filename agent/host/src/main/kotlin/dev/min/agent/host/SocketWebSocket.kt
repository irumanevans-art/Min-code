package dev.min.agent.host

import dev.min.agent.spi.WEBSOCKET_MAX_MESSAGE_BYTES
import dev.min.agent.spi.WebSocketConnection
import dev.min.agent.spi.WebSocketEvent
import dev.min.agent.spi.WebSocketPort
import dev.min.agent.spi.WebSocketRefused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * WebSocket 客户端（RFC 6455），自己写在 java.net 的 Socket 上：同一份代码在桌面 JVM 与 Android 上都能跑
 * （`java.net.http.WebSocket` Android 没有；OkHttp 要多背一个依赖，也没有「连接时钉住校验过的地址」的钩子）。
 *
 * ## 安全
 * - 先解析主机名，**每一个**解析出的地址都要过 [blockedAddress]（私网 / 链路本地 / 云元数据 / 多播 / 未指定一概拒；
 *   回环放行——本机开发服务器是最常见的正当用途，且打开前本来就要人批准），然后**用那个地址连**（不再让 Socket 自己再解析一次，
 *   免得 DNS 重绑定在两次解析之间换成内网地址）。wss 的证书校验与 SNI 仍按原主机名做。
 * - 单条消息超过 [WEBSOCKET_MAX_MESSAGE_BYTES] 就不再读：按帧头声明的长度在读正文之前就判，攻击者发不出一个吃光内存的大帧。
 * - 不带扩展（不协商压缩）、不跟随重定向、握手响应只认 101；失败原因不带对端响应正文。
 *
 * [resolver] / [connectTimeoutMs] / [handshakeTimeoutMs] 是测试缝；生产用默认值。
 */
class SocketWebSocketPort(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val connectTimeoutMs: Int = 15_000,
    private val handshakeTimeoutMs: Int = 15_000,
    private val sslFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
) : WebSocketPort {

    override suspend fun open(url: String, protocols: List<String>): WebSocketConnection = withContext(Dispatchers.IO) {
        val target = parse(url)
        val addresses = try {
            resolver(target.host)
        } catch (e: java.net.UnknownHostException) {
            throw WebSocketRefused("解析不了主机「${target.host}」。")
        }
        if (addresses.isEmpty()) throw WebSocketRefused("解析不了主机「${target.host}」。")
        // 全部地址都要合格：主机名只要有一个解析结果指向内网就整个拒绝（CC：「含解析到这些地址的主机名」）
        addresses.forEach { blockedAddress(it)?.let { reason -> throw WebSocketRefused(reason) } }
        val socket = connect(addresses, target)
        try {
            socket.soTimeout = handshakeTimeoutMs
            val upgraded = if (target.secure) tls(socket, target) else socket
            val input = BufferedInputStream(upgraded.getInputStream())
            val output = upgraded.getOutputStream()
            handshake(input, output, target, protocols)
            upgraded.soTimeout = 0
            SocketConnection(upgraded, input, output)
        } catch (e: WebSocketRefused) {
            closeQuietly(socket)
            throw e
        } catch (e: IOException) {
            closeQuietly(socket)
            throw WebSocketRefused("握手失败（${e.javaClass.simpleName}）。")
        } catch (e: Throwable) {
            closeQuietly(socket)
            throw e
        }
    }

    private class Target(val host: String, val port: Int, val secure: Boolean, val requestTarget: String, val hostHeader: String)

    private fun parse(url: String): Target {
        val uri = try { URI(url) } catch (e: java.net.URISyntaxException) { throw WebSocketRefused("地址不合法。") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") throw WebSocketRefused("只收 ws:// 或 wss:// 地址。")
        val raw = uri.host ?: throw WebSocketRefused("地址缺少主机。")
        val host = raw.removeSurrounding("[", "]")
        val secure = scheme == "wss"
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = (if (':' in host) "[$host]" else host) + if (uri.port > 0 && uri.port != (if (secure) 443 else 80)) ":$port" else ""
        return Target(host, port, secure, path, hostHeader)
    }

    private fun connect(addresses: List<InetAddress>, target: Target): Socket {
        var last: IOException? = null
        for (address in addresses) {
            // 开了应用代理就经代理连同一个已审过的 IP：钉住地址的防重绑定不变，只是出口换成代理
            try {
                val socket = OutboundProxy.dial(address.hostAddress, target.port, connectTimeoutMs)
                try {
                    socket.tcpNoDelay = true
                    return socket
                } catch (e: IOException) {
                    closeQuietly(socket)
                    throw e
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw WebSocketRefused("连不上「${target.host}」（${last?.javaClass?.simpleName ?: "无可用地址"}）。")
    }

    /** TLS：在已连好的（钉住地址的）裸连接上叠一层；SNI 与主机名校验用原主机名，IP 字面量不设 SNI（走证书的 IP SAN） */
    private fun tls(raw: Socket, target: Target): Socket {
        val ssl = sslFactory().createSocket(raw, target.host, target.port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            if (!isIpLiteral(target.host)) serverNames = listOf(SNIHostName(target.host))
        }
        try {
            ssl.startHandshake()
        } catch (e: IOException) {
            closeQuietly(ssl)
            throw WebSocketRefused("TLS 握手失败（${e.javaClass.simpleName}）：证书或协议不被接受。")
        }
        return ssl
    }

    private fun handshake(input: InputStream, output: java.io.OutputStream, target: Target, protocols: List<String>) {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
        val request = buildString {
            append("GET ").append(target.requestTarget).append(" HTTP/1.1\r\n")
            append("Host: ").append(target.hostHeader).append("\r\n")
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n")
            if (protocols.isNotEmpty()) append("Sec-WebSocket-Protocol: ").append(protocols.joinToString(", ")).append("\r\n")
            append("User-Agent: min-agent\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val head = try { readHead(input) } catch (e: java.net.SocketTimeoutException) { throw WebSocketRefused("握手超时。") }
        val lines = head.split("\r\n")
        val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(lines.first())?.groupValues?.get(1)?.toInt()
            ?: throw WebSocketRefused("对端不是 WebSocket 服务器（响应不是 HTTP）。")
        if (status in 300..399) throw WebSocketRefused("握手被重定向（HTTP $status），不跟随；请换成最终地址。")
        if (status != 101) throw WebSocketRefused("握手失败（HTTP $status）。")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (headers["upgrade"]?.lowercase() != "websocket" || headers["connection"]?.lowercase()?.contains("upgrade") != true) {
            throw WebSocketRefused("握手失败：响应没有升级到 websocket。")
        }
        val expected = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)).let { Base64.getEncoder().encodeToString(it) }
        if (headers["sec-websocket-accept"] != expected) throw WebSocketRefused("握手失败：Sec-WebSocket-Accept 校验不过。")
        // 我们没提议任何扩展：对端回了扩展就是违规（压缩帧我们不会解）
        if ("sec-websocket-extensions" in headers) throw WebSocketRefused("握手失败：对端启用了我们没提议的扩展。")
        headers["sec-websocket-protocol"]?.let { chosen ->
            if (chosen !in protocols) throw WebSocketRefused("握手失败：对端选了我们没提议的子协议「${chosen.take(60)}」。")
        }
    }

    /** 读到空行为止的响应头，最多 [MAX_HEAD_BYTES]；逐字节读，不多吞后面的帧 */
    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0 // 已经连续匹配了「\r\n\r\n」的几个字节
        while (true) {
            val b = input.read()
            if (b < 0) throw WebSocketRefused("握手失败：对端提前断开了连接。")
            out.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return String(out.toByteArray(), 0, out.size() - 4, Charsets.ISO_8859_1)
            if (out.size() > MAX_HEAD_BYTES) throw WebSocketRefused("握手失败：响应头过长。")
        }
    }

    /** 读线程把事件放进有界通道：通道满了读线程就阻塞，TCP 反压回对端，不会在内存里堆消息 */
    private class SocketConnection(
        private val socket: Socket,
        private val input: InputStream,
        private val output: java.io.OutputStream,
    ) : WebSocketConnection {
        private val events = Channel<WebSocketEvent>(EVENT_BUFFER)
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()
        @Volatile private var terminal: WebSocketEvent? = null

        init {
            Thread({ readLoop() }, "ws-reader").apply { isDaemon = true; start() }
        }

        override suspend fun next(): WebSocketEvent = try {
            // 先把通道里已经到的事件读完，结束事件排在它们后面；通道关了之后再调就一直返回结束事件
            events.receive()
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            terminal ?: WebSocketEvent.Failed("连接已结束。")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 是调用方自己被取消就原样抛出（连接随后由它关）；否则是连接被 close() 弃掉了通道
            kotlin.coroutines.coroutineContext.ensureActive()
            terminal ?: WebSocketEvent.Failed("连接已关闭。")
        }

        /** 调用方不要了：关 socket，并弃掉通道——读线程若正卡在往满了的通道里送，靠这个醒来退出 */
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(socket)
                events.cancel()
            }
        }

        private fun emit(event: WebSocketEvent) {
            // 读线程里阻塞地送（有界通道的反压）；连接被关了（接收方取消）送不出去就算了
            try {
                runBlocking { events.send(event) }
            } catch (_: Exception) {
            }
        }

        /** 连接自己结束了：结束事件排在已到的事件后面送出，通道关上（不弃，没读的事件还要给消费者），再关 socket */
        private fun finish(event: WebSocketEvent) {
            terminal = event
            emit(event)
            events.close()
            closed.set(true)
            closeQuietly(socket)
        }

        private fun readLoop() {
            val fragments = ByteArrayOutputStream()
            var fragmentOpcode = -1
            var fragmentBytes = 0L
            try {
                while (true) {
                    val b0 = readByte()
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0F
                    if (b0 and 0x70 != 0) return protocolError(1002, "协议错误：使用了保留位（没协商扩展）。")
                    val b1 = readByte()
                    if (b1 and 0x80 != 0) return protocolError(1002, "协议错误：服务器发来了带掩码的帧。")
                    var length = (b1 and 0x7F).toLong()
                    if (length == 126L) length = (readByte() shl 8 or readByte()).toLong()
                    else if (length == 127L) {
                        length = 0
                        repeat(8) { length = (length shl 8) or readByte().toLong() }
                        if (length < 0) return protocolError(1002, "协议错误：帧长度非法。")
                    }
                    if (opcode >= 8) {
                        if (!fin || length > 125) return protocolError(1002, "协议错误：控制帧不能分片或超过 125 字节。")
                        val payload = readFully(length.toInt())
                        when (opcode) {
                            OP_CLOSE -> {
                                val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                                val reason = if (payload.size > 2) payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8) else ""
                                // 回一个关闭帧（RFC 要求回应），尽力而为
                                ignoringIo { writeFrame(OP_CLOSE, if (payload.size >= 2) payload.copyOf(2) else ByteArray(0)) }
                                return finish(WebSocketEvent.Closed(code, reason.take(200)))
                            }
                            OP_PING -> ignoringIo { writeFrame(OP_PONG, payload) }
                            OP_PONG -> Unit
                            else -> return protocolError(1002, "协议错误：未知的控制帧。")
                        }
                        continue
                    }
                    // 数据帧：0 = 续帧，1 = 文本，2 = 二进制
                    if (opcode == 0) {
                        if (fragmentOpcode < 0) return protocolError(1002, "协议错误：没有起头的续帧。")
                    } else if (opcode == 1 || opcode == 2) {
                        if (fragmentOpcode >= 0) return protocolError(1002, "协议错误：上一条消息还没结束就来了新消息。")
                        fragmentOpcode = opcode
                        fragmentBytes = 0
                        fragments.reset()
                    } else return protocolError(1002, "协议错误：未知的数据帧。")
                    val total = fragmentBytes + length
                    // 在读正文之前按声明的长度判：超限就不读了
                    if (total > WEBSOCKET_MAX_MESSAGE_BYTES) {
                        ignoringIo { writeFrame(OP_CLOSE, byteArrayOf((1009 shr 8).toByte(), (1009 and 0xFF).toByte())) }
                        return finish(WebSocketEvent.TooLarge(total))
                    }
                    if (fragmentOpcode == 1) fragments.write(readFully(length.toInt())) else skip(length)
                    fragmentBytes = total
                    if (fin) {
                        val event = if (fragmentOpcode == 1) {
                            val text = decodeStrict(fragments.toByteArray())
                                ?: return protocolError(1007, "文本消息不是合法的 UTF-8。")
                            WebSocketEvent.Text(text)
                        } else WebSocketEvent.Binary(fragmentBytes)
                        fragmentOpcode = -1
                        fragments.reset()
                        emit(event)
                    }
                }
            } catch (e: EOFException) {
                finish(WebSocketEvent.Failed("连接被对端断开（没有收到关闭帧）。"))
            } catch (e: IOException) {
                // 自己关的（取消、结束）不算失败：terminal 已经有了或者没人在等
                if (!closed.get()) finish(WebSocketEvent.Failed("读连接失败（${e.javaClass.simpleName}）。"))
            }
        }

        private fun protocolError(code: Int, detail: String) {
            ignoringIo { writeFrame(OP_CLOSE, byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())) }
            finish(WebSocketEvent.Failed(detail))
        }

        private inline fun ignoringIo(block: () -> Unit) {
            try { block() } catch (_: IOException) { }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            return b
        }

        private fun readFully(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return buf
        }

        private fun skip(n: Long) {
            var left = n
            val scratch = ByteArray(8192)
            while (left > 0) {
                val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (r < 0) throw EOFException()
                left -= r
            }
        }

        /** 客户端发出的帧必须带掩码（RFC 6455 §5.3） */
        private fun writeFrame(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xFF) }
                else -> error("控制帧不会这么大")
            }
            header.write(mask)
            val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            synchronized(writeLock) {
                output.write(header.toByteArray())
                output.write(masked)
                output.flush()
            }
        }

        private fun decodeStrict(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val EVENT_BUFFER = 256
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10

        private fun closeQuietly(socket: Socket) {
            try { socket.close() } catch (_: IOException) { }
        }

        private fun isIpLiteral(host: String): Boolean = ':' in host || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

        /**
         * 这个地址能不能连；不能就给原因，能就 null。拒绝：未指定（0.0.0.0 / ::）、链路本地（169.254/16 含 AWS / GCP / Azure 的元数据地址
         * 169.254.169.254，fe80::/10）、私网（10/8、172.16/12、192.168/16、fc00::/7 含 AWS 的 fd00:ec2::254）、运营商级 NAT（100.64/10，
         * 含阿里云元数据 100.100.100.200）、192.0.0.0/24（含 Oracle 元数据 192.0.0.192）、多播，以及把 IPv4 内网地址嵌在里面的
         * NAT64（64:ff9b::/96）。回环放行（见类注释）。
         */
        fun blockedAddress(address: InetAddress): String? {
            val bytes = address.address
            fun reject(what: String) = "地址 ${address.hostAddress} 属于$what，不能连接。"
            if (address.isAnyLocalAddress) return reject("未指定地址")
            if (address.isLoopbackAddress) return null
            if (address.isLinkLocalAddress) return reject("链路本地 / 云元数据范围")
            if (address.isSiteLocalAddress) return reject("私网范围")
            if (address.isMulticastAddress) return reject("多播范围")
            if (address is Inet4Address) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 100 && (b1 and 0xC0) == 0x40) return reject("运营商级 NAT / 云元数据范围（100.64.0.0/10）")
                if (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) return reject("IETF 保留 / 云元数据范围（192.0.0.0/24）")
                if (b0 == 0) return reject("未指定地址范围")
            }
            if (address is Inet6Address) {
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return reject("私网范围（fc00::/7）")
                // NAT64 / IPv4 兼容地址：末 4 字节是内嵌的 IPv4，按 IPv4 再判一遍
                val nat64 = bytes.take(12) == listOf(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }
                val compat = bytes.take(12).all { it == 0.toByte() } && bytes.drop(12).any { it != 0.toByte() }
                if (nat64 || compat) {
                    val embedded = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    if (!embedded.isLoopbackAddress) blockedAddress(embedded)?.let { return reject("内嵌了内网地址的 IPv6 地址") }
                }
            }
            return null
        }
    }
}
