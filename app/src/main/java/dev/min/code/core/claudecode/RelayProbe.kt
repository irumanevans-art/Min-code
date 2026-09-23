package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.settings.isInsecureBaseUrl
import dev.min.code.core.settings.joinClaudeApi
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

    /**
     * 地址通得上，但这家没实现 `/v1/models`（常见于 DeepSeek Anthropic 兼容基址）。
     *
     * **不是连不上。** 以前 404 被收进 [Unreachable]，界面就写成「连不上：HTTP 404」，
     * 用户会以为 key/地址坏了；其实会话照常用，只是测活没法从模型列表端点确认。
     */
    data object NoModelsEndpoint : RelayProbeResult

    /** 连不上 / 超时 / 对方返回了别的错。[reason] 直接给人看 */
    data class Unreachable(val reason: String) : RelayProbeResult

    /** 还没填 key，没什么可探的 */
    data object NoToken : RelayProbeResult
}

suspend fun probeRelay(baseUrl: String, token: String): RelayProbeResult = withContext(Dispatchers.IO) {
    if (token.isBlank()) return@withContext RelayProbeResult.NoToken
    try {
        val body = fetchRelayModelsPayload(baseUrl, token)
        RelayProbeResult.Reachable(countModels(body))
    } catch (e: RelayHttpStatusException) {
        classifyProbeHttpStatus(e.code)
    } catch (e: Exception) {
        Log.w(TAG, "probe failed for ${relayModelsUrl(baseUrl)}", e)
        RelayProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
    }
}

/** 测活遇到非 2xx 时怎么归类。抽出来是为了单测能钉死「404 ≠ 连不上」 */
internal fun classifyProbeHttpStatus(code: Int): RelayProbeResult = when (code) {
    401, 403 -> RelayProbeResult.Unauthorized
    // 和 fetchRelayModelIds 对齐：没模型列表 ≠ 连不上
    404, 405 -> RelayProbeResult.NoModelsEndpoint
    else -> RelayProbeResult.Unreachable("HTTP $code")
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

/** `/v1/models` 的完整 URL。[joinClaudeApi] 会剥掉 base 末尾的 /v1，避免拼成 /v1/v1/models */
internal fun relayModelsUrl(baseUrl: String): String = joinClaudeApi(baseUrl, "/v1/models") + "?limit=1000"

/**
 * `GET /v1/models` 的公共取数：URL 拼接 + Bearer 优先、401/403 回退 x-api-key。
 * 会话侧的模型目录（[ClaudeCodeManager.fetchRelayModels]）、探活 [probeRelay]、
 * 供应商编辑页的 [fetchRelayModelIds] 三家共用 —— 鉴权策略变了只改这一处。
 * token 为空的语义（抛 vs 返回 NoToken）与失败分类是各调用方自己的事，这里一律抛出。
 */
internal suspend fun fetchRelayModelsPayload(baseUrl: String, token: String): String =
    withContext(Dispatchers.IO) {
        val url = relayModelsUrl(baseUrl)
        // CLI 拿 ANTHROPIC_AUTH_TOKEN 发的是 Bearer；有的中转站只认 x-api-key，401 就换一种再试
        try {
            relayHttpGet(url, mapOf("Authorization" to "Bearer $token"))
        } catch (e: RelayHttpStatusException) {
            if (e.code == 401 || e.code == 403) relayHttpGet(url, mapOf("x-api-key" to token)) else throw e
        }
    }

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

/**
 * 「这家都卖哪些模型」——供应商编辑页那颗取模型的按钮。
 *
 * 和探活、和会话里的模型目录走同一个端点同一套鉴权回退（本文件上面那两个函数）。
 * 分出来是因为**失败的种类不一样**：探活只要知道通不通，这里要把「这家没实现这个端点」
 * 和「key 不对」分开说——前者让人手填模型 id 就行，后者得回去改 key。
 */
sealed interface RelayModelsResult {
    data class Ok(val ids: List<String>) : RelayModelsResult
    data object Unauthorized : RelayModelsResult

    /** 404 / 405：这家没有这个端点。手填模型 id 照样能用 */
    data class NotSupported(val code: Int) : RelayModelsResult
    data class Failed(val reason: String) : RelayModelsResult
    data object NoToken : RelayModelsResult
}

suspend fun fetchRelayModelIds(baseUrl: String, token: String): RelayModelsResult =
    withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext RelayModelsResult.NoToken
        try {
            val ids = parseRelayModelIds(fetchRelayModelsPayload(baseUrl, token))
            if (ids.isEmpty()) RelayModelsResult.Failed("empty") else RelayModelsResult.Ok(ids)
        } catch (e: RelayHttpStatusException) {
            when (e.code) {
                401, 403 -> RelayModelsResult.Unauthorized
                404, 405 -> RelayModelsResult.NotSupported(e.code)
                else -> RelayModelsResult.Failed("HTTP ${e.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "model list failed for ${relayModelsUrl(baseUrl)}", e)
            RelayModelsResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

/**
 * 从 `/v1/models` 的响应里把 id 抠出来。
 *
 * 认三种形状：`{"data":[{"id":…}]}`（OpenAI / Anthropic）、`{"models":[…]}`（有的中转）、
 * 以及数组里直接放字符串的。认得宽一点是有道理的：这是个只读端点，认错了最多列表是空的，
 * 认不出来却会让一家能用的中转看上去坏了。
 */
internal fun parseRelayModelIds(body: String): List<String> = runCatching {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
    val obj = json as? kotlinx.serialization.json.JsonObject
    val array = (obj?.get("data") ?: obj?.get("models") ?: json) as? kotlinx.serialization.json.JsonArray
        ?: return@runCatching emptyList()
    array.mapNotNull { item ->
        when (item) {
            is kotlinx.serialization.json.JsonPrimitive -> item.content.takeIf { it.isNotBlank() }
            is kotlinx.serialization.json.JsonObject ->
                (item["id"] ?: item["name"] ?: item["model"])
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.takeIf { it.isNotBlank() }
            else -> null
        }
    }.distinct()
}.getOrDefault(emptyList())
