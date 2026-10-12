package dev.min.agent.llm

import dev.min.agent.spi.ModelFailureKind

/*
 * 响应头里只给遥测用的两样：供应商的请求 id 与 `traceresponse`。编解码器把它们记在这里，流开始与失败时各取一次，
 * 经 ModelStreamEvent.Started / ModelException 交给内核落盘（OTel `gen_ai.response.id`、`traceresponse` span link）。
 */
internal class ResponseMeta(private val idHeader: String) {
    @Volatile var responseId: String? = null
        private set
    @Volatile var traceResponse: String? = null
        private set

    fun accept(headers: List<Pair<String, String>>) {
        for ((name, value) in headers) {
            when {
                name.equals(idHeader, ignoreCase = true) -> responseId = clean(value, MAX_ID)
                name.equals("traceresponse", ignoreCase = true) -> traceResponse = clean(value, MAX_TRACE_RESPONSE)
            }
        }
    }

    /** 补进失败：超时与响应头字段（已有值不覆盖，见 [ModelException.withResponse]） */
    fun annotate(e: Throwable): Throwable =
        if (e is ModelException) e.withResponse(responseId, traceResponse, timedOut = e.timedOut || e.cause.isTimeout()) else e

    private companion object {
        const val MAX_ID = 128
        const val MAX_TRACE_RESPONSE = 256

        /** 头值来自远端，进日志前要干净：去首尾空白、只留可打印 ASCII、限长；不合规或为空返回 null */
        fun clean(value: String, max: Int): String? {
            val v = value.trim()
            if (v.isEmpty() || v.length > max || v.any { it < ' ' || it > '~' }) return null
            return v
        }
    }
}

/** 这个异常（或它的 cause 链）是不是网络超时：遥测的 `api_timeout` 靠它区分 */
internal fun Throwable?.isTimeout(): Boolean {
    var t = this
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is java.net.SocketTimeoutException) return true
        t = t.cause
    }
    return false
}

/** 编解码器遇到 IOException 时统一的「网络中断」：断流 / 超时都是 Network，超时另标 [ModelException.timedOut] */
internal fun networkFailure(message: String, beforeFirstByte: Boolean, cause: java.io.IOException): ModelException =
    ModelException(ModelFailureKind.Network, message, beforeFirstByte = beforeFirstByte, cause = cause, timedOut = cause.isTimeout())

/** 官方 Anthropic 端点：主机是 anthropic.com 或其子域。第三方的 `/anthropic` 兼容中转不算 */
internal fun ProviderDescriptor.isAnthropicFirstParty(): Boolean {
    val host = try {
        java.net.URI(baseUrl.trim()).host
    } catch (e: java.net.URISyntaxException) {
        null
    } ?: return false
    return wire == Wire.AnthropicMessages && (host == "anthropic.com" || host.endsWith(".anthropic.com"))
}
