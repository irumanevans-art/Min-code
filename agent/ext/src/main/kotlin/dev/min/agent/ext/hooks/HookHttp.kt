package dev.min.agent.ext.hooks

import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * settings 里的两道 http hook 闸（CC settings-reference：allowedHttpHookUrls / httpHookAllowedEnvVars）：
 * 「在任意一层定义过，HTTP hook 的 URL 就必须命中合并后的允许清单」「定义过就只插值清单上的环境变量」。
 * null 表示所有层都没定义——不设这道闸。URL 匹配按整串相等处理；CC 文档没写粒度（前缀/模式），这是
 * 最保守的读法，设计文档 §9 记了这个空白。
 */
data class HttpHookPolicy(val allowedUrls: List<String>?, val allowedEnvVars: List<String>?) {
    fun allowsUrl(url: String): Boolean = allowedUrls == null || url in allowedUrls

    /** 环境变量闸：没定义就不限制；定义了就只放行清单上的名字 */
    fun allowsEnvVar(name: String): Boolean = allowedEnvVars == null || name in allowedEnvVars

    companion object {
        /** 各层清单合并（settings 的列表语义是叠加，同 permissions.allow）；全部未定义才保持未定义 */
        fun merge(layers: List<HttpHookPolicy>): HttpHookPolicy {
            fun merged(pick: (HttpHookPolicy) -> List<String>?) =
                layers.mapNotNull(pick).takeIf { it.isNotEmpty() }?.flatten()?.distinct()
            return HttpHookPolicy(merged { it.allowedUrls }, merged { it.allowedEnvVars })
        }
    }
}

/** 一批 http hook 的执行产出：真正发过请求的运行结果 + 被闸门拦下没跑的处理器说明 */
data class HttpHookBatch(val runs: List<HandlerRun>, val skipped: List<String>)

/**
 * `type:http` 的执行器：把事件 JSON 原文 POST 出去，再把 HTTP 事实翻译成 [HandlerRun]，之后的解读与
 * 合并完全复用 command 的 [interpret] / [combine]。翻译表按 CC hooks.md「HTTP response handling」：
 * - 2xx 空体 → exit 0 无输出；2xx JSON 对象体 → exit 0 stdout=体（校验失败由 interpret 报非阻断错误）
 * - 2xx 其他体 → 非阻断错误，文字不进上下文（与非 2xx 同）；非 2xx、连不上 → 非阻断错误
 * - 超时 → timedOut（PreToolUse 依设计 §5 升为 Ask，其余中性）
 * http 没有 exit 2：想阻断就回 2xx 带 decision 字段的 JSON。
 */
class HookHttpRunner(private val client: HttpHookClient) {

    suspend fun run(
        handlers: List<HookHandler>,
        stdin: JsonObject,
        env: Map<String, String>,
        policy: HttpHookPolicy,
        timeoutFor: (HookHandler) -> Int,
    ): HttpHookBatch = coroutineScope {
        val skipped = mutableListOf<String>()
        val attempted = handlers.filter { handler ->
            val url = handler.url.orEmpty()
            if (!policy.allowsUrl(url)) {
                skipped += "$url：不在 allowedHttpHookUrls，未执行"
                false
            } else true
        }
        val runs = attempted.map { handler ->
            async {
                val headers = interpolateHeaders(handler.headers, handler.allowedEnvVars, policy, env)
                val request = HttpHookRequest(handler.url.orEmpty(), headers, stdin.toString(),
                    timeoutFor(handler).coerceAtLeast(1) * 1000L)
                toRun(handler, client.send(request))
            }
        }.awaitAll()
        HttpHookBatch(runs, skipped.toList())
    }

    /** CC「HTTP response handling」的事实归档：状态、连接、超时翻译成 command 世界的退出码与通道 */
    internal fun toRun(handler: HookHandler, result: HttpHookResult): HandlerRun = when (result) {
        is HttpHookResult.TimedOut -> HandlerRun(handler, 124, "", "", timedOut = true)
        is HttpHookResult.Failed -> HandlerRun(handler, 1, "", "HTTP 请求失败：${result.error}")
        is HttpHookResult.Responded -> {
            val body = result.body
            val firstLine = body.lineSequence().firstOrNull().orEmpty()
            when {
                result.status !in 200..299 ->
                    HandlerRun(handler, 1, "", "HTTP ${result.status}" + if (firstLine.isBlank()) "" else "：$firstLine")
                body.isBlank() -> HandlerRun(handler, 0, "", "")
                isJsonObjectBody(body) -> HandlerRun(handler, 0, body, "")
                else -> HandlerRun(handler, 1, "", "响应体不是 JSON 对象：$firstLine")
            }
        }
    }

    private fun isJsonObjectBody(body: String): Boolean = try {
        HookJson.parseToJsonElement(body) is JsonObject
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        private val ENV_REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

        /**
         * 头值里的 `$VAR` / `${VAR}` 插值。CC：只有处理器 allowedEnvVars 列出的名字才解析，未列出的换成
         * 空串；settings 的 httpHookAllowedEnvVars 再设一道闸；环境里没有的按空串处理（同未列出）。
         */
        fun interpolateHeaders(
            headers: JsonObject?,
            handlerAllowed: List<String>?,
            policy: HttpHookPolicy,
            env: Map<String, String>,
        ): Map<String, String> {
            val allowed = handlerAllowed.orEmpty().toSet()
            fun resolve(value: String): String = ENV_REF.replace(value) { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (name in allowed && policy.allowsEnvVar(name)) env[name].orEmpty() else ""
            }
            return headers.orEmpty().mapValues { (_, value) ->
                resolve((value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
            }
        }
    }
}
