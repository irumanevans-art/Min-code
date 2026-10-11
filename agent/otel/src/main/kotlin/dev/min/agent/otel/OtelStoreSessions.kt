package dev.min.agent.otel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore

/**
 * 从会话存储里取要导出的会话。两种范围：
 * - [descendantsOf]：一个根会话与它的全部子孙（CLI：只导出这次运行的会话树）；
 * - [all]：存储里所有会话（App：每个会话一棵树，父子关系靠日志头部的 parent）。
 *
 * 为什么按头部的 parent 找子孙：子 agent 的日志与父会话在同一个存储里，头部指回父会话（见 LogHeader.parent），
 * 不必读任何事件就能划出会话树。列表结果缓存 [listTtlMs]：周期导出每个节拍都问一次，不必每次都翻整个会话目录；
 * 要求 fresh（进程退出、手动导出）时重新列。
 */
object OtelStoreSessions {
    fun descendantsOf(
        store: SessionStore,
        root: SessionId,
        clock: () -> Long = System::currentTimeMillis,
        listTtlMs: Long = DEFAULT_TTL_MS,
    ): OtelSessionSource = Cached(store, clock, listTtlMs) { headers ->
        val ids = HashSet<String>().apply { add(root.value) }
        // 父会话一定先于子会话创建（头部按创建时间排序），一趟就能收齐；多趟兜住乱序
        var grew = true
        while (grew) {
            grew = false
            for (h in headers) {
                val p = h.parent?.value ?: continue
                if (p in ids && ids.add(h.sessionId.value)) grew = true
            }
        }
        ids
    }

    fun all(store: SessionStore, clock: () -> Long = System::currentTimeMillis, listTtlMs: Long = DEFAULT_TTL_MS): OtelSessionSource =
        Cached(store, clock, listTtlMs) { headers -> headers.mapTo(HashSet()) { it.sessionId.value } }

    const val DEFAULT_TTL_MS = 10_000L

    private class Cached(
        private val store: SessionStore,
        private val clock: () -> Long,
        private val ttlMs: Long,
        private val select: (List<dev.min.agent.spi.LogHeader>) -> Set<String>,
    ) : OtelSessionSource {
        private var listedAt = Long.MIN_VALUE
        private val opened = LinkedHashMap<String, SessionLog>()

        override suspend fun logs(fresh: Boolean): List<SessionLog> {
            val now = clock()
            if (fresh || listedAt == Long.MIN_VALUE || now - listedAt >= ttlMs) {
                val wanted = select(store.list())
                for (id in wanted) {
                    if (id in opened) continue
                    // 只读地打开：这个日志还在被会话追加，写者之外的人不能做崩溃修复（见 SessionStore.peek）
                    store.peek(SessionId(id))?.let { opened[id] = it }
                }
                listedAt = now
            }
            return opened.values.toList()
        }
    }
}
