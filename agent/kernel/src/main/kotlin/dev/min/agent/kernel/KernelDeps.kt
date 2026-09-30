package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.Search
import dev.min.agent.spi.Tool
import dev.min.agent.spi.WorkspaceFs
import java.time.ZoneId

/** 一个模型：能力描述 + 说它那种线协议的客户端 */
class ResolvedModel(val profile: ModelProfile, val client: ModelClient)

fun interface ModelResolver {
    /** 找不到就抛异常：会话设置里的模型必须在目录里 */
    fun resolve(ref: ModelRef): ResolvedModel
}

/** 宿主提供的端口。Android 宿主：宿主侧读写 rootfs、ripgrep、proot；桌面宿主：本机文件与进程 */
class HostPorts(
    val fs: WorkspaceFs,
    val search: Search,
    val exec: Exec,
    val artifacts: (SessionId) -> ArtifactSink,
)

/** 内核的全部外部依赖，由宿主在组合根里一次性给齐（docs/agent/02-总体架构.md §2：构造注入，不做运行时发现） */
class KernelDeps(
    val clock: Clock,
    val ids: IdGenerator,
    val models: ModelResolver,
    val tools: List<Tool<*>>,
    val host: HostPorts,
    /** 写进系统提示的平台描述，例 `Ubuntu 24.04 (proot on Android)` */
    val platform: String,
    /** 会话日期按这个时区算 */
    val zone: ZoneId,
    val retry: RetryPolicy = RetryPolicy(),
    /** 一批并发安全的工具最多同时跑几个 */
    val maxParallelTools: Int = 8,
)

/**
 * 模型请求失败后的退避。只有供应商说可以重试的错误（网络、限流、过载、5xx）才重试；
 * 有 retry-after 就听它的，否则指数退避。不加随机抖动：内核里的随机性要可控，恢复测试才能复现。
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
) {
    fun delayMs(attempt: Int, retryAfterMs: Long?): Long {
        retryAfterMs?.let { return it.coerceIn(0, maxDelayMs) }
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
    }
}
