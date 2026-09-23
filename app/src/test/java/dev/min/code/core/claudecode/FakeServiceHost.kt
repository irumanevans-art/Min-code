package dev.min.code.core.claudecode

import dev.min.code.core.service.AgentServiceHost
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 假的进程表：起的服务一律停在 Starting，由测试用 [settle] 决定它撑没撑过就绪窗口。
 * 真的进程表要起 proot，单测里起不来。
 */
internal class FakeServiceHost : AgentServiceHost {
    override val services = MutableStateFlow<List<LocalService>>(emptyList())

    /** 收到的每一条托管命令 */
    val started = CopyOnWriteArrayList<String>()

    /** 非空时 startFromAgent 直接失败，失败原因就是它 */
    @Volatile
    var failWith: String? = null

    override suspend fun startFromAgent(
        command: String,
        cwdGuest: String,
        port: Int?,
        label: String?,
        sourceSessionKey: String?,
    ): Result<String> {
        started += command
        failWith?.let { return Result.failure(IllegalStateException(it)) }
        val id = "svc${started.size}"
        services.update {
            it + LocalService(
                id = id,
                label = label ?: command,
                command = command,
                cwdGuest = cwdGuest,
                port = port,
                status = LocalServiceStatus.Starting,
                startedAtEpochMs = 0,
                sourceSessionKey = sourceSessionKey,
            )
        }
        return Result.success(id)
    }

    fun settle(
        id: String,
        status: LocalServiceStatus,
        exitCode: Int? = null,
        stopReason: LocalServiceStopReason? = null,
        logTail: String = "",
    ) = services.update { list ->
        list.map {
            if (it.id == id) it.copy(status = status, exitCode = exitCode, stopReason = stopReason, logTail = logTail) else it
        }
    }
}
