package dev.min.agent.host

import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
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
class JvmHttpHookClient(private val connectTimeoutMs: Int = 10_000) : HttpHookClient {

    override suspend fun send(request: HttpHookRequest): HttpHookResult = withContext(Dispatchers.IO) {
        val body = request.body.toByteArray(Charsets.UTF_8)
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = minOf(connectTimeoutMs.toLong(), request.timeoutMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            readTimeout = request.timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        try {
            coroutineScope {
                val watchdog = launch {
                    try { awaitCancellation() } finally { connection.disconnect() }
                }
                try {
                    connection.outputStream.use { it.write(body) }
                    val status = connection.responseCode
                    val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    watchdog.cancel()
                    HttpHookResult.Responded(status, body)
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
}
