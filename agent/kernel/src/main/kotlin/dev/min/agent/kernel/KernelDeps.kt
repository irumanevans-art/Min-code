package dev.min.agent.kernel

import dev.min.agent.context.MemoryLimits
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.GuestPath
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
    val web: dev.min.agent.spi.WebPort = dev.min.agent.spi.WebPort.Unavailable,
    val searchBackend: dev.min.agent.spi.SearchBackend? = null,
    /**
     * `type:http` 的 hook 由宿主代发 POST（设计 §8 H3）。默认不可用：没有它时 http hook 会报「这个环境发不了请求」，
     * 不影响 command hook。
     */
    val httpHooks: dev.min.agent.spi.HttpHookClient = dev.min.agent.spi.HttpHookClient {
        dev.min.agent.spi.HttpHookResult.Failed("这个环境没有配置 http hook 的发送端口。")
    },
    /**
     * 长寿进程（MCP stdio 服务器，MCP设计 §2）。默认不可用：spawn 抛明确的错误，不影响不用它的宿主与测试。
     * 传进来的端口会被套上 [QuotaProcessPort]，对外的 [processes] 恒带配额。
     */
    processes: dev.min.agent.spi.ProcessPort = dev.min.agent.spi.ProcessPort.Unavailable,
) {
    val processes: dev.min.agent.spi.ProcessPort = QuotaProcessPort(processes)

    // 保留既有 HostPorts(fs, search, exec) { artifacts } 的组合根与测试写法。
    constructor(fs: WorkspaceFs, search: Search, exec: Exec, artifacts: (SessionId) -> ArtifactSink) :
        this(fs, search, exec, artifacts, dev.min.agent.spi.WebPort.Unavailable, null)
}

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
    /**
     * 用户主目录（guest 路径；Android 上是 `/root`）：找 `~/.claude/CLAUDE.md`、`~/.claude/rules/`，解析 `@~/…` 导入。
     * null = 不加载记忆文件（内核测试、不需要记忆的嵌入场景）；真实宿主都应该给
     */
    val memoryHome: GuestPath? = null,
    /**
     * guest 路径在 hook 脚本所在 shell 里的写法（docs/agent/detail/hooks设计.md §2）：Android 的 proot 里就是原样；
     * 桌面 CLI 在本机 shell 里跑，要按 DesktopPaths 映射成本机路径
     */
    val hookShellPath: (GuestPath) -> String = { it.value },
    /** 这个工作区里项目层（project / local）的 hooks 能不能跑：工作区信任由宿主记（App 的设置、CLI 的参数），默认不信任 */
    val trustProjectHooks: (GuestPath) -> Boolean = { false },
    val memoryLimits: MemoryLimits = MemoryLimits(),
    val fastModel: ModelRef? = null,
    val maxWebSearches: Int = 200,
    /**
     * 环境变量的读取口。内核不直接读进程环境：宿主决定给它什么（桌面 CLI 给 `System.getenv`），
     * 内核测试给固定的表，不随跑测试的机器变。现在只读 `CLAUDE_CODE_DISABLE_AUTO_MEMORY`
     */
    val env: (String) -> String? = { null },
) {
    init { require(maxWebSearches > 0) { "搜索会话上限必须是正整数。" } }
}

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
