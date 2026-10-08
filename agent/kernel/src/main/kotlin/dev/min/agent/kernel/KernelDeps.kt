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
    /** 远程 MCP 服务器（Streamable HTTP / 旧 SSE）用的 HTTP 端口（MCP设计 §1）；null = 只能连 stdio 服务器 */
    val mcpHttp: dev.min.agent.spi.HttpTransport? = null,
    /** 检查点的快照仓库（检查点与rewind设计 §6）。默认不可用：不建检查点，rewind 只能回退对话 */
    val snapshots: dev.min.agent.spi.WorkspaceSnapshots = dev.min.agent.spi.WorkspaceSnapshots.Unavailable,
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
    /** 凭证是宿主的信任边界，不能由会话配置、分支或工具输入覆盖。 */
    val credentialPaths: Set<GuestPath> = emptySet(),
    val canonicalPath: (GuestPath) -> GuestPath = { it },
    /**
     * guest 路径在 hook 脚本所在 shell 里的写法（docs/agent/detail/hooks设计.md §2）：Android 的 proot 里就是原样；
     * 桌面 CLI 在本机 shell 里跑，要按 DesktopPaths 映射成本机路径
     */
    val hookShellPath: (GuestPath) -> String = { it.value },
    /** 这个工作区里项目层（project / local）的 hooks 能不能跑：工作区信任由宿主记（App 的设置、CLI 的参数），默认不信任 */
    val trustProjectHooks: (GuestPath) -> Boolean = { false },
    val memoryLimits: MemoryLimits = MemoryLimits(),
    val fastModel: ModelRef? = null,
    /**
     * 模型回退链（`fallbackModel` / `--fallback-model`，已由 [FallbackChain.resolve] 去重截断）：主模型重试用尽仍过载、
     * 或「不可用」时按顺序换下一个，只管当前回合。纯运行时选项：不进日志、不随重开会话恢复（CC：
     * `--fallback-model` 不随 resume 恢复）。链上每一项都要能被 [models] 解析，解析不了的跳过并给一条 Note
     */
    val fallbackModels: List<ModelRef> = emptyList(),
    /** 重开会话时建议「从摘要续接」的阈值（[resumeAdvice]）；照 CC：空闲 > 1 小时且上下文 > 100,000 token */
    val resumeThresholds: ResumeThresholds = ResumeThresholds(),
    val maxWebSearches: Int = 200,
    /**
     * 根会话一个回合里的模型请求次数上限（CLI 的 `--max-turns`，没给时读 `CLAUDE_CODE_MAX_TURNS`）。
     * 纯运行时选项：不进日志、不改前缀——它只属于这一次进程运行，重开会话不该带着它。
     * 只对根会话生效；子 agent 用来历里的 [dev.min.agent.spi.SubagentOrigin.maxTurns]，两层互不干扰
     */
    val rootMaxTurns: Int? = null,
    /**
     * `--json-schema` 的结构化输出方案（null = 没开）。同为运行时选项；只给根会话（子 agent 与分身
     * 不经 StructuredOutput 交卷——最终答案属于主对话，CC 的结构化输出也只在 -p 的主会话上）
     */
    val structuredOutput: StructuredOutputPlan? = null,
    /**
     * 环境变量的读取口。内核不直接读进程环境：宿主决定给它什么（桌面 CLI 给 `System.getenv`），
     * 内核测试给固定的表，不随跑测试的机器变。现在只读 `CLAUDE_CODE_DISABLE_AUTO_MEMORY`
     */
    val env: (String) -> String? = { null },
    /**
     * 宿主有意对 [env] 藏起来的变量名（桌面 CLI 自己读模型 key 的那个变量）。[env] 对它们返回 null；
     * 这里单列出来，是为了在 http hook 的 allowedEnvVars 点名它时告诉人「为什么插成了空串」
     */
    val hiddenEnv: Set<String> = emptySet(),
    /** 检查点整树快照的预算（检查点与rewind设计 §3.4）；Android 宿主给自己的一份 */
    val checkpointBudget: dev.min.agent.spi.SnapshotBudget = dev.min.agent.spi.SnapshotBudget.DESKTOP,
    /**
     * 首份 MCP 快照在第一回合开头最多等多久（从开始连接算）。交互时用户打字的工夫服务器在连，5 秒够；
     * `-p` 跑批一上来就发请求、没人等，CLI 给 30 秒，免得慢服务器的工具整次运行都缺席（2026-10-06 拍板）
     */
    val mcpFirstSnapshotWaitMs: Long = 5_000,
    /** MCP 握手与单次请求的超时（CC 的 `MCP_TIMEOUT` 默认 30 秒）；Android 上 proot 里冷启动 npx 慢，App 给 120 秒 */
    val mcpConnectTimeoutMs: Long = 30_000,
    val mcpServerPath: (GuestPath) -> String = { it.value },
    val canAnswerMcpElicitation: Boolean = true,
    val canAnswerMcpUrlElicitation: Boolean = false,
    val mcpElicitationTimeoutMs: Long = 5 * 60_000,
    val mcpTokenStore: dev.min.agent.spi.McpTokenStore = dev.min.agent.spi.McpTokenStore.Unavailable,
    val mcpAuthorizer: dev.min.agent.spi.McpAuthorizer = dev.min.agent.spi.McpAuthorizer.Unavailable,
    val gitPath: (GuestPath) -> String = { it.value },
    val pluginDirs: List<GuestPath> = emptyList(),
    val pluginInstalledPath: (String) -> GuestPath = { GuestPath(it) },
    val pluginTrust: (GuestPath, GuestPath) -> Boolean = { root, cwd ->
        val realRoot = canonicalPath(root).value
        val realCwd = canonicalPath(cwd).value.trimEnd('/')
        trustProjectHooks(cwd) && (realRoot == realCwd || realRoot.startsWith("$realCwd/"))
    },
    /** 默认审查按当前会话供应商查目录，不能复用可能跨供应商的提取模型。 */
    val providerFastModel: (String) -> ModelRef? = { null },
    val autoModeReviewer: dev.min.agent.spi.AutoModeReviewer? = null,
    val modelTimeoutMs: Long = 600_000,
    val maxBudgetUsd: Double? = null,
    val reviewerModels: ((dev.min.agent.spi.AutoModeReviewer) -> ResolvedModel)? = null,
    val workflowEngine: dev.min.agent.spi.WorkflowEngine? = null,
    /** 通用宿主默认 16，手机组合根指定 4；环境设置可在 1–256 内覆盖。 */
    val workflowConcurrency: Int = 16,
    val workflowApprovals: dev.min.agent.spi.WorkflowApprovals = dev.min.agent.spi.WorkflowApprovals.SessionOnly,
) {
    init {
        require(maxWebSearches > 0) { "搜索会话上限必须是正整数。" }
        require(workflowConcurrency in 1..256)
        require(modelTimeoutMs > 0)
        require(maxBudgetUsd == null || maxBudgetUsd.isFinite() && maxBudgetUsd > 0)
    }
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
