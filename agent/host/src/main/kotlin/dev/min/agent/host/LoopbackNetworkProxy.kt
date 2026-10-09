package dev.min.agent.host

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.InputStream
import java.net.*
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 每次命令有自己的随机凭据；连接不能借用别的会话的审批。HTTPS 只转发字节，不解密。 */
class LoopbackNetworkProxy(
    private val approvalTimeoutMs: Long = 65_000,
    private val socketTimeoutMs: Int = 15_000,
    private val maxConnections: Int = 32,
    private val connect: (NetworkTarget) -> Socket = { target -> Socket().apply { connect(InetSocketAddress(target.domain, target.port), 10_000) } },
) : NetworkProxy, AutoCloseable {
    private val leases = ConcurrentHashMap<Lease, SessionId>()
    private val slots = Semaphore(maxConnections)
    private val closed = AtomicBoolean()
    init { require(approvalTimeoutMs > 0 && socketTimeoutMs > 0 && maxConnections > 0) }
    @Synchronized override fun open(session: SessionId, decide: suspend (NetworkTarget) -> NetworkVerdict): NetworkLease {
        check(!closed.get())
        check(leases.size < 128) { "网络代理命令租约超过上限" }
        val lease = Lease(decide)
        leases[lease] = session
        if (closed.get()) { lease.close(); error("网络代理已关闭") }
        lease.start()
        return lease
    }
    @Synchronized override fun closeSession(session: SessionId) { leases.entries.filter { it.value == session }.forEach { it.key.close() } }
    @Synchronized override fun close() { closed.set(true); leases.keys.toList().forEach { it.close() } }

    private inner class Lease(val decide: suspend (NetworkTarget) -> NetworkVerdict) : NetworkLease {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0), 32) }
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val stopped = AtomicBoolean()
        private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private val credential = "Basic " + Base64.getEncoder().encodeToString("$token:".toByteArray(Charsets.UTF_8))
        override val env: Map<String, String> = proxyEnvironment("http://$token@127.0.0.1:${server.localPort}")
        fun start() { scope.launch {
            try {
                while (isActive) {
                    val client = server.accept()
                    client.soTimeout = socketTimeoutMs
                    if (!slots.tryAcquire()) { client.use { reply(it, "代理连接数超过上限") }; continue }
                    sockets += client
                    launch {
                        try { handle(client) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (!client.isClosed) try { reply(client, when (e) {
                            is UnknownHostException -> "无法解析目标域名"
                            is SocketTimeoutException -> "目标连接超时"
                            is java.io.IOException -> "目标连接失败"
                            else -> "网络代理请求无效"
                        }) } catch (_: java.io.IOException) { } }
                        finally { sockets -= client; client.close(); slots.release() }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: java.io.IOException) { close() }
        } }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            server.close(); sockets.toList().forEach { it.close() }; scope.cancel(); leases.remove(this)
        }
        private suspend fun handle(client: Socket) {
            val input = client.getInputStream()
            val head = readHead(input)
            // 不带凭据的同机程序不能触发审批，更不能借用已批准的会话。
            if (head.values("proxy-authorization") != listOf(credential)) { reply(client, "代理凭据无效"); return }
            val target = targetOf(head)
            val decision = withTimeoutOrNull(approvalTimeoutMs) { decide(target) }
                ?: NetworkVerdict(NetworkOutcome.Deny, NetworkSource.Timeout, "网络批准超时")
            if (decision.outcome != NetworkOutcome.Allow) { reply(client, decision.reason); return }
            val upstream = connect(target)
            sockets += upstream
            try {
                // 连接建立可能晚于关闭会话，不能把迟到的套接字留在已回收的租约中。
                if (stopped.get()) return
                // 慢接口、git 打包和 SSE 可以长时间不发字节；已批准连接由命令租约负责回收。
                upstream.soTimeout = 0
                if (head.method == "CONNECT") {
                    client.soTimeout = 0
                    client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                    coroutineScope {
                        launch { input.copyTo(upstream.getOutputStream()); upstream.shutdownOutput() }
                        launch { upstream.getInputStream().copyTo(client.getOutputStream()); client.shutdownOutput() }
                    }
                } else {
                    val uri = if (head.path.startsWith("http://")) URI(head.path) else null
                    val path = uri?.let { (it.rawPath.ifEmpty { "/" }) + (it.rawQuery?.let { q -> "?$q" } ?: "") } ?: head.path
                    val out = upstream.getOutputStream()
                    val headers = head.headers.filterNot { it.first.lowercase() in setOf("proxy-authorization", "proxy-connection", "connection") }
                    out.write(("${head.method} $path HTTP/1.1\r\n" + headers.joinToString("") { "${it.first}: ${it.second}\r\n" } + "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    copyBody(head, input, out)
                    out.flush()
                    upstream.getInputStream().copyTo(client.getOutputStream())
                }
            } finally { sockets -= upstream; upstream.close() }
        }
    }

    private data class Head(val method: String, val path: String, val headers: List<Pair<String, String>>) {
        fun values(key: String) = headers.filter { it.first.equals(key, true) }.map { it.second }
    }
    private fun readHead(input: InputStream): Head {
        val request = line(input, 8192).split(' ')
        require(request.size == 3 && request[2] in setOf("HTTP/1.0", "HTTP/1.1") && Regex("[A-Z]+").matches(request[0]))
        val headers = mutableListOf<Pair<String, String>>()
        var bytes = 0
        while (true) {
            val line = line(input, 8192)
            bytes += line.length + 2
            require(bytes <= 32768 && headers.size < 128)
            if (line.isEmpty()) break
            require(':' in line && !line.first().isWhitespace())
            val key = line.substringBefore(':')
            val value = line.substringAfter(':').trim()
            require(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+").matches(key) && value.none { it.code < 32 && it != '\t' })
            headers += key to value
        }
        val head = Head(request[0], request[1], headers)
        require(head.values("host").size == 1 && head.values("content-length").size <= 1 && head.values("transfer-encoding").size <= 1)
        require(head.values("content-length").isEmpty() || head.values("transfer-encoding").isEmpty())
        require(head.values("transfer-encoding").isEmpty() || head.values("transfer-encoding").single().equals("chunked", true))
        head.values("content-length").firstOrNull()?.let { require(it.toLongOrNull()?.let { n -> n in 0..MAX_BODY } == true) }
        return head
    }
    private fun targetOf(head: Head): NetworkTarget {
        fun authority(value: String, defaultPort: Int): NetworkTarget {
            val uri = URI("http://$value")
            require(uri.userInfo == null && uri.rawPath.isEmpty() && uri.query == null && uri.fragment == null)
            val host = requireNotNull(uri.host).removeSurrounding("[", "]").lowercase().trimEnd('.')
            val port = if (uri.port < 0) defaultPort else uri.port
            require(host.isNotEmpty() && port in 1..65535)
            return NetworkTarget(host, port)
        }
        val host = authority(head.values("host").single(), if (head.method == "CONNECT") 443 else 80)
        if (head.method == "CONNECT") {
            val target = authority(head.path, 443)
            require(target == host)
            return target
        }
        if (head.path.startsWith("http://")) {
            val uri = URI(head.path)
            require(uri.userInfo == null && uri.fragment == null)
            require(authority(uri.rawAuthority, 80) == host)
        } else require(head.path.startsWith('/') && !head.path.startsWith("//"))
        return host
    }
    private fun copyBody(head: Head, input: InputStream, out: java.io.OutputStream) {
        if (head.values("transfer-encoding").isEmpty()) {
            copyExactly(input, out, head.values("content-length").firstOrNull()?.toLong() ?: 0)
            return
        }
        var total = 0L
        while (true) {
            val sizeLine = line(input, 128)
            val size = sizeLine.substringBefore(';').toLongOrNull(16) ?: error("无效的 HTTP 分块")
            require(size >= 0 && size <= MAX_BODY - total)
            total += size
            out.write((size.toString(16) + "\r\n").toByteArray())
            if (size == 0L) {
                // 不转发可覆盖 Host 的 trailer；仅接收有界的尾部。
                var trailers = 0
                while (true) { val tail = line(input, 8192); trailers += tail.length + 2; require(trailers < 32768); if (tail.isEmpty()) break }
                out.write("\r\n".toByteArray()); return
            }
            copyExactly(input, out, size)
            require(line(input, 2).isEmpty()); out.write("\r\n".toByteArray())
        }
    }
    private fun copyExactly(input: InputStream, out: java.io.OutputStream, length: Long) {
        var remaining = length
        val buf = ByteArray(8192)
        while (remaining > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt()); require(n > 0); out.write(buf, 0, n); remaining -= n }
    }
    private fun line(input: InputStream, limit: Int): String {
        val text = StringBuilder()
        while (text.length <= limit) {
            val b = input.read(); require(b >= 0)
            if (b == 13) { require(input.read() == 10); return text.toString() }
            require(b != 10); text.append(b.toChar())
        }
        error("HTTP 头过长")
    }
    private fun reply(socket: Socket, reason: String) {
        val body = "网络请求被拒绝：$reason\n".toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply { write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()); write(body); flush() }
    }
    companion object {
        private const val MAX_BODY = 64L * 1024 * 1024
        fun proxyEnvironment(http: String, socks: String? = null): Map<String, String> = buildMap {
            for (key in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) { val value = if (key == "ALL_PROXY") socks ?: http else http; put(key, value); put(key.lowercase(), value) }
            put("NO_PROXY", "localhost,127.0.0.1,::1"); put("no_proxy", "localhost,127.0.0.1,::1")
        }
    }
}
