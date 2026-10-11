package dev.min.agent.otel

/**
 * W3C trace context（traceparent 头 / TRACEPARENT 环境变量）：`00-<32 位 trace id>-<16 位 span id>-<2 位 flags>`。
 * 原文：Agent SDK 与 `-p` 会话启动每个 interaction span 时读环境里的 TRACEPARENT 与 TRACESTATE 作为父；
 * Bash 子进程继承当前工具执行 span 的 TRACEPARENT。这里只做解析与格式化，不碰环境。
 */
data class TraceParent(
    val traceId: String,
    val spanId: String,
    /** 两位十六进制；出站一律 01（已采样：我们导出了这条 span） */
    val flags: String = "01",
    /** 入站 TRACESTATE 原样带回根 span，出站不带 */
    val traceState: String? = null,
) {
    fun header(): String = "00-$traceId-$spanId-$flags"

    companion object {
        private val HEX = Regex("[0-9a-f]+")

        /**
         * 解析 [value]；不合规（版本不是 00、长度不对、非十六进制、全零 id）一律返回 null——
         * W3C 规定无效的 traceparent 要被忽略并另起新 trace，不能让坏输入把 span 挂到一个不存在的父上。
         */
        fun parse(value: String?, state: String? = null): TraceParent? {
            val parts = value?.trim()?.split('-') ?: return null
            if (parts.size != 4) return null
            val (version, trace, span, flags) = parts
            if (version != "00" || trace.length != 32 || span.length != 16 || flags.length != 2) return null
            if (!HEX.matches(trace) || !HEX.matches(span) || !HEX.matches(flags)) return null
            if (trace.all { it == '0' } || span.all { it == '0' }) return null
            return TraceParent(trace, span, flags, state?.trim()?.takeIf { it.isNotEmpty() })
        }

        fun of(traceId: String, spanId: String): TraceParent = TraceParent(traceId, spanId)
    }
}

/**
 * 回合的 trace id：有入站 TRACEPARENT（-p / SDK）时所有 interaction 都挂在它的 trace 里（原文：每个 interaction 启动时读一次），
 * 否则每个回合一条自己的 trace（由会话 id 与回合 id 哈希得出，确定、可重放）。
 * 宿主（给 Bash 子进程算 TRACEPARENT）与导出（算 span）共用这一个函数，两边才会得出同一个 id。
 */
fun traceIdFor(session: String, turn: String, inbound: TraceParent?): String = inbound?.traceId ?: SpanIds.trace(session, turn)
