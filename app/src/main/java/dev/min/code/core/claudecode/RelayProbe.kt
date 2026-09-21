package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.settings.isInsecureBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "RelayProbe"

/**
 * 「这条供应商现在通不通」。
 *
 * 走的是和 [ClaudeCodeManager.refreshRelayModels] 同一个端点（`GET /v1/models`）、
 * 同一套鉴权重试 —— 会话里问「你卖哪些模型」和供应商页问「你还活着吗」本来就是一件事，
 * 各写一份的话，其中一份迟早会在某次改动里落下。
 *
 * **只报告，不阻断。** 和 [isInsecureBaseUrl] 是同一条规矩：不少中转站压根没实现这个
 * 端点，探不通不等于不能用；切换、保存一律照常。
 */
sealed interface RelayProbeResult {
    /** 通了。[models] 是它报出来的模型数，-1 表示响应解析不出来（但地址和 key 是对的） */
    data class Reachable(val models: Int) : RelayProbeResult

    /** 地址在、key 不对。两种鉴权方式都试过了 */
    data object Unauthorized : RelayProbeResult

    /** 连不上 / 超时 / 对方返回了别的错。[reason] 直接给人看 */
    data class Unreachable(val reason: String) : RelayProbeResult

    /** 还没填 key，没什么可探的 */
    data object NoToken : RelayProbeResult
}

suspend fun probeRelay(baseUrl: String, token: String): RelayProbeResult = withContext(Dispatchers.IO) {
    if (token.isBlank()) return@withContext RelayProbeResult.NoToken
    val url = "${baseUrl.trimEnd('/')}/v1/models?limit=1000"
    try {
        // CLI 拿 ANTHROPIC_AUTH_TOKEN 发的是 Bearer；有的中转站只认 x-api-key，401 就换一种再试
        val body = try {
            relayHttpGet(url, mapOf("Authorization" to "Bearer $token"))
        } catch (e: RelayHttpStatusException) {
            if (e.code == 401 || e.code == 403) {
                relayHttpGet(url, mapOf("x-api-key" to token))
            } else {
                throw e
            }
        }
        RelayProbeResult.Reachable(countModels(body))
    } catch (e: RelayHttpStatusException) {
        if (e.code == 401 || e.code == 403) {
            RelayProbeResult.Unauthorized
        } else {
            RelayProbeResult.Unreachable("HTTP ${e.code}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "probe failed for $url", e)
        RelayProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
    }
}

/**
 * 数一下 `data` 数组里有几项。
 *
 * 不复用 `parseRelayModels` —— 那个要把每一项转成完整的 [ClaudeCodeManager.RelayModel]
 * （会话页要拿去填模型选择器）。这里只要一个数字，而且**它多认几种形状**：
 * 有的中转站把列表放在 `models` 而不是 `data` 下。数不出来给 -1，界面照样说「通了」，
 * 只是不显示数量 —— 探活的结论是"连得上"，不该因为一个字段名对不上就变成"连不上"。
 */
private fun countModels(body: String): Int = runCatching {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
    val obj = json as? kotlinx.serialization.json.JsonObject ?: return@runCatching -1
    val array = (obj["data"] ?: obj["models"]) as? kotlinx.serialization.json.JsonArray
    array?.size ?: -1
}.getOrDefault(-1)

internal class RelayHttpStatusException(val code: Int, url: String) : RuntimeException("HTTP $code for $url")

/**
 * 中转站那几个只读端点共用的 GET。
 *
 * 超时给得比默认宽（15s 连 / 20s 读）：这些地址常常在海外，或者前面压着一层自建反代。
 */
internal fun relayHttpGet(url: String, headers: Map<String, String>): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 20_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("anthropic-version", "2023-06-01")
    headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw RelayHttpStatusException(code, url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
