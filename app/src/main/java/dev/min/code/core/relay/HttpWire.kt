package dev.min.code.core.relay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 手写的 HTTP/1.1 收发原语，App 内几个本机服务共用。
 *
 * ## 为什么不用现成的 HTTP 框架
 *
 * 这几个服务只在 `127.0.0.1` 上对**自己人**说话：机内协议路由（[LocalRelayServer]）
 * 面对的是 rootfs 里的 CLI，设备 MCP server 面对的也是同一个 CLI。请求形状固定、
 * 并发量个位数、没有静态资源也没有路由树——为这点事拖进 Ktor / Netty 是几 MB 的
 * APK 体积和一整套生命周期，换不回任何东西。
 *
 * ## 为什么提出来
 *
 * 这套原语本来长在 [LocalRelayServer] 里。设备 MCP server 要起第二个本机服务时，
 * 摆在面前的是「复制一份六十行的 HTTP 解析」还是「提出来共用」——复制的那份会和
 * 原件各自演化，某一天只有一边修了 `Content-Length` 的边界，而两边看起来都对。
 *
 * ## 局限（故意的）
 *
 * - 不支持 `Transfer-Encoding: chunked` 的**请求**体：本机的两个调用方都带 Content-Length。
 * - 不支持 keep-alive，每个响应都 `Connection: close`。并发靠线程池，不靠连接复用。
 * - [readRequest] 一次把整个请求体读进内存。本机调用没有上传大文件这回事。
 */
internal data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    // data class 对 ByteArray 生成的 equals 比的是引用，留着会在将来某次
    // "为什么这两个请求不相等" 上浪费一小时。这里用不到相等语义，直接不给
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 单个请求体的上限。正常请求（整段对话的 JSON）离它还有一个量级；挡的是共享回环上别的 App 发个大 Content-Length 把堆打爆 */
internal const val MAX_REQUEST_BODY_BYTES = 32 * 1024 * 1024

/** 单行（请求行/头行）的上限，防的是不断行灌字节的慢攻击 */
internal const val MAX_HTTP_LINE_BYTES = 8 * 1024

/**
 * 读一个完整请求。
 *
 * @return 请求行读不出来（对端直接断开）、或 Content-Length 超上限时返回 null；
 *   头行超上限抛 [IOException]，由调用方连接级的 catch 收口（日志 + 断连）
 */
internal fun readRequest(input: InputStream): HttpRequest? {
    val first = readHttpLine(input, MAX_HTTP_LINE_BYTES) ?: return null
    val parts = first.split(' ')
    if (parts.size < 2) return null
    val method = parts[0]
    val path = parts[1].substringBefore('?')
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readHttpLine(input, MAX_HTTP_LINE_BYTES) ?: break
        if (line.isEmpty()) break
        val i = line.indexOf(':')
        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
    }
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length > MAX_REQUEST_BODY_BYTES) return null
    val body = if (length > 0) {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        buf
    } else {
        ByteArray(0)
    }
    return HttpRequest(method, path, headers, body)
}

/**
 * 读一行，吃掉 CRLF。头部按 RFC 是 ASCII，body 另按 Content-Length 读，不走这里。
 * [maxBytes] 限制单行字节数，超过即抛 [IOException]——不能返回 null：那会被当成
 * 「连接结束」，请求会带着截断的头部继续解析。SSE 读取（readSse）不经过这里，
 * 大体积的事件行不受影响。
 */
internal fun readHttpLine(input: InputStream, maxBytes: Int = Int.MAX_VALUE): String? {
    val buf = ByteArrayOutputStream()
    while (true) {
        val c = input.read()
        if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
        if (c == '\n'.code) break
        if (c != '\r'.code) buf.write(c)
        if (buf.size() > maxBytes) throw IOException("http line exceeds $maxBytes bytes")
    }
    return buf.toString(Charsets.US_ASCII.name())
}

/**
 * 写一个响应。
 *
 * @param stream 非空时流式转发（上游 SSE），此时不写 Content-Length
 */
internal fun writeResponse(
    output: OutputStream,
    code: Int,
    contentType: String,
    body: ByteArray,
    stream: InputStream? = null,
    cors: Boolean = false,
) {
    val status = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        502 -> "Bad Gateway"
        else -> "Error"
    }
    val head = buildString {
        append("HTTP/1.1 $code $status\r\n")
        append("Content-Type: $contentType\r\n")
        if (stream == null) append("Content-Length: ${body.size}\r\n")
        append("Connection: close\r\n")
        if (cors) {
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
        }
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.US_ASCII))
    if (stream != null) {
        stream.copyTo(output)
    } else {
        output.write(body)
    }
    output.flush()
}
