package dev.min.agent.otel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 从会话存储里划出要导出的会话：根的子孙 / 全部；列表带缓存 */
class OtelStoreSessionsTest {
    private class FakeStore(val logs: MutableList<FakeSessionLog>) : SessionStore {
        var lists = 0
        override suspend fun create(header: LogHeader): SessionLog = error("导出端不建会话")
        override suspend fun open(id: SessionId): SessionLog? = error("导出端不用 open（会做崩溃修复），只能 peek")
        override suspend fun list(): List<LogHeader> {
            lists++
            return logs.map { it.header }
        }
        override suspend fun peek(id: SessionId): SessionLog? = logs.firstOrNull { it.header.sessionId == id }
    }

    private fun store() = FakeStore(mutableListOf(
        FakeSessionLog("root"),
        FakeSessionLog("c1", parent = "root"),
        FakeSessionLog("g1", parent = "c1"),
        FakeSessionLog("other"),
        FakeSessionLog("other-child", parent = "other"),
    ))

    private fun ids(logs: List<SessionLog>) = logs.map { it.header.sessionId.value }.sorted()

    @Test
    fun `descendantsOf 只取根会话与它的子孙`() = runBlocking {
        val source = OtelStoreSessions.descendantsOf(store(), SessionId("root"))
        assertEquals(listOf("c1", "g1", "root"), ids(source.logs(fresh = true)))
    }

    @Test
    fun `all 取存储里的全部会话`() = runBlocking {
        val source = OtelStoreSessions.all(store())
        assertEquals(listOf("c1", "g1", "other", "other-child", "root"), ids(source.logs(fresh = true)))
    }

    @Test
    fun `列表缓存：间隔内不重复翻会话目录，fresh 与过期才重新列，新会话随之出现`() = runBlocking {
        val store = store()
        var now = 0L
        val source = OtelStoreSessions.descendantsOf(store, SessionId("root"), clock = { now }, listTtlMs = 10_000)
        source.logs(fresh = false)
        source.logs(fresh = false)
        assertEquals(1, store.lists)

        store.logs += FakeSessionLog("c2", parent = "root")
        now = 5_000
        assertEquals(listOf("c1", "g1", "root"), ids(source.logs(fresh = false))) // 还在缓存期内
        assertEquals(listOf("c1", "c2", "g1", "root"), ids(source.logs(fresh = true)))
        assertEquals(2, store.lists)

        store.logs += FakeSessionLog("c3", parent = "root")
        now = 15_001 // 距上次列表（5000）已过 10001ms
        assertEquals(listOf("c1", "c2", "c3", "g1", "root"), ids(source.logs(fresh = false)))
        assertEquals(3, store.lists)
    }
}
