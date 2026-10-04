package dev.min.agent.host

import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL

/**
 * hooks `type:http` 的宿主实现。用 HttpURLConnection（桌面与 Android 都有），与 [UrlConnectionTransport]
 * 同一套取消办法：阻塞读打断不了协程取消，另起看门协程在取消时断开连接。
 *
 * 超时归 [HttpHookResult.TimedOut]（连接与读超时都抛 [SocketTimeoutException]），其余 IOException 归
 * [HttpHookResult.Failed]。不跟随重定向：重定向到别的主机会把头里的凭证带过去（同模型请求的取舍）。
 * 头里可能有凭证：异常信息与结果只含状态码与说明文字。
 */
class JvmHttpHookClient(
    private val connectTimeoutMs: Int = 10_000,
    /** 响应体最多读这么多字节，多的丢掉并报告 truncated：一个失控的服务不能把手机内存读满 */
    private val maxBodyBytes: Int = 1 shl 20,
) : HttpHookClient {

    /**
     * 协程取消以外的一切异常都归 [HttpHookResult.Failed]：URL 写错、不是 http(s)、头值含换行这类配置问题
     * 不能冒到回合里把回合打成失败（SessionStart 上冒出来还会让会话建不起来）。
     * 异常信息可能带着 URL（查询串里常有令牌）或头值，所以配置类问题用自己的说明，不转抄异常信息。
     */
    override suspend fun send(request: HttpHookRequest): HttpHookResult =
        catchingNonCancellation { withContext(Dispatchers.IO) { exchange(request) } }
            .getOrElse { e -> HttpHookResult.Failed(if (e is IOException) e.message ?: e.javaClass.simpleName else e.javaClass.simpleName) }

    private suspend fun exchange(request: HttpHookRequest): HttpHookResult {
        val url = try {
            URL(request.url)
        } catch (e: MalformedURLException) {
            return HttpHookResult.Failed("URL 不合法（需要 http:// 或 https:// 开头的完整地址）")
        }
        if (url.protocol.lowercase() !in SCHEMES) return HttpHookResult.Failed("只支持 http / https 的 URL，不支持 ${url.protocol}")
        val connection = url.openConnection() as? HttpURLConnection
            ?: return HttpHookResult.Failed("这个 URL 打不开 HTTP 连接")
        try {
            val body = request.body.toByteArray(Charsets.UTF_8)
            connection.apply {
                requestMethod = "POST"
                connectTimeout = minOf(connectTimeoutMs.toLong(), request.timeoutMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                readTimeout = request.timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
            }
            for ((name, value) in request.headers) {
                if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                    return HttpHookResult.Failed("请求头「$name」的名字或值含非法字符（值里不能有换行）")
                }
                connection.setRequestProperty(name, value)
            }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            return coroutineScope {
                val watchdog = launch {
                    try { awaitCancellation() } finally { connection.disconnect() }
                }
                try {
                    connection.outputStream.use { it.write(body) }
                    val status = connection.responseCode
                    // 多读一个字节，才分得清「刚好等于上限」和「超出」
                    val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.use { it.readNBytes(maxBodyBytes + 1) } ?: ByteArray(0)
                    watchdog.cancel()
                    val truncated = bytes.size > maxBodyBytes
                    HttpHookResult.Responded(status, bytes.copyOf(minOf(bytes.size, maxBodyBytes)).toString(Charsets.UTF_8), truncated)
                } catch (e: SocketTimeoutException) {
                    watchdog.cancel()
                    HttpHookResult.TimedOut
                } catch (e: IOException) {
                    // 看门协程因取消断开连接后，阻塞调用会以 IOException 冒出来；那是取消，不是 hook 失败
                    currentCoroutineContext().ensureActive()
                    watchdog.cancel()
                    HttpHookResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val SCHEMES = setOf("http", "https")

        /** RFC 9110 的 token：头名只能由这些字符组成 */
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
