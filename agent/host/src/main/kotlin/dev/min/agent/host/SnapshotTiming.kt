package dev.min.agent.host

/** 宿主诊断入口不改变 SPI；各分段互不包含，总时间还包括调度与构造清单。 */
data class SnapshotTiming(val nanos: Map<String, Long>, val totalNanos: Long, val contentReads: Int) {
    override fun toString(): String = nanos.entries.joinToString(" ") { "${it.key}Ms=${it.value / 1_000_000.0}" } +
        " totalMs=${totalNanos / 1_000_000.0} contentReads=$contentReads"
}

internal class SnapshotTimer {
    val nanos = linkedMapOf("traversalStat" to 0L, "guestResolve" to 0L, "ignore" to 0L,
        "readHash" to 0L, "objects" to 0L, "index" to 0L, "manifestSync" to 0L, "lock" to 0L)
    var contentReads = 0
    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { nanos[stage] = nanos.getValue(stage) + System.nanoTime() - start }
    }
    fun result(total: Long) = SnapshotTiming(nanos.toMap(), total, contentReads)
}
