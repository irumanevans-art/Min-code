package dev.min.agent.testkit

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import java.io.IOException

/** 可以「拔电源」的存储：[kill] 之后任何追加都失败，模拟进程死亡（死了的进程什么都写不进去） */
class KillableStore(private val delegate: SessionStore) : SessionStore {
    @Volatile
    private var dead = false

    fun kill() {
        dead = true
    }

    override suspend fun create(header: LogHeader): SessionLog = Killable(delegate.create(header))
    override suspend fun open(id: SessionId): SessionLog? = delegate.open(id)?.let(::Killable)
    override suspend fun list(): List<LogHeader> = delegate.list()

    private inner class Killable(private val log: SessionLog) : SessionLog by log {
        override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
            if (dead) throw IOException("进程已死")
            return log.append(events, durability)
        }
    }
}
