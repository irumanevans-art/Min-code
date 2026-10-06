package dev.min.agent.kernel

import dev.min.agent.spi.McpAuthorizer
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI

/** 两个宿主共享回调收取；浏览器和终端输入仍由各自宿主提供。 */
class LoopbackMcpAuthorizer(
    private val openPage: suspend (String) -> Boolean,
    private val manualUrl: (String) -> Unit = {},
    private val pollCallback: (() -> String?)? = null,
    private val timeoutMs: Long = 300_000,
) : McpAuthorizer {
    override suspend fun authorize(redirectPort: Int?, authorizationUrl: suspend (String) -> String): String = withTimeout(timeoutMs) {
        require(redirectPort==null || redirectPort in 1..65535) { "MCP 回调端口无效。" }
        val listener=ServerSocket()
        try {
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),redirectPort ?: 0))
            listener.soTimeout=100
            val redirect="http://127.0.0.1:${listener.localPort}/mcp/oauth/callback"
            val url=authorizationUrl(redirect)
            val opened=openPage(url)
            if(!opened) {
                if(pollCallback==null) throw IllegalStateException("没有可用的外部浏览器，MCP 登录未完成。")
                manualUrl(url)
            }
            while(true) {
                currentCoroutineContext().ensureActive()
                if(!opened) pollCallback?.invoke()?.let { return@withTimeout it }
                val callback=withContext(Dispatchers.IO) {
                    val socket=try { listener.accept() } catch(e: SocketTimeoutException) { return@withContext null }
                    socket.use {
                        socket.soTimeout=1000
                        val input=socket.getInputStream(); val bytes=ByteArrayOutputStream()
                        var end=false
                        try {
                            while(bytes.size()<8192) {
                                val b=input.read(); if(b<0) break; bytes.write(b)
                                val v=bytes.toByteArray()
                                if(v.size>=4 && v.takeLast(4)==listOf(13.toByte(),10.toByte(),13.toByte(),10.toByte())) { end=true; break }
                            }
                        } catch(e: java.io.IOException) { }
                        val line=bytes.toString(Charsets.US_ASCII).substringBefore("\r\n").split(' ')
                        val target=line.getOrNull(1)
                        val uri=try { target?.let(::URI) } catch(e: Exception) { null }
                        val accepted=end && line.firstOrNull()=="GET" && uri?.rawPath=="/mcp/oauth/callback" && !uri.isAbsolute && uri.rawAuthority==null && uri.fragment==null
                        val text=if(accepted) "请返回 Min 完成登录验证。" else "无效的 MCP 回调。"
                        val body=text.toByteArray(Charsets.UTF_8)
                        try {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 ${if(accepted) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); write(body); flush()
                            }
                        } catch(e: java.io.IOException) { }
                        if(accepted) "http://127.0.0.1:${listener.localPort}$target" else null
                    }
                }
                if(callback!=null) return@withTimeout callback
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { listener.close() }
    }
}
