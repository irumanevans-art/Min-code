package dev.min.code.core.engine.min

import dev.min.agent.host.CachedWebPort
import dev.min.agent.host.JvmHttpHookClient
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ModelResolver
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Clock
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpec
import dev.min.agent.spi.Search
import dev.min.agent.spi.SearchBackend
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.WebPort
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.WorkspaceSnapshots
import dev.min.agent.spi.SnapshotBudget
import dev.min.agent.tools.AgentToolOf
import dev.min.agent.tools.AskUserQuestionTool
import dev.min.agent.tools.BashTool
import dev.min.agent.tools.EditTool
import dev.min.agent.tools.EnterPlanModeTool
import dev.min.agent.tools.ExitPlanModeTool
import dev.min.agent.tools.GlobTool
import dev.min.agent.tools.GrepTool
import dev.min.agent.tools.NotebookEditTool
import dev.min.agent.tools.ReadTool
import dev.min.agent.tools.SendMessageTool
import dev.min.agent.tools.SkillTool
import dev.min.agent.tools.TaskCreateTool
import dev.min.agent.tools.TaskGetTool
import dev.min.agent.tools.TaskListTool
import dev.min.agent.tools.TaskUpdateTool
import dev.min.agent.tools.TaskStopTool
import dev.min.agent.tools.WebFetchTool
import dev.min.agent.tools.WriteTool
import dev.min.agent.tools.web.HtmlPageConverter
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一「代」宿主端口：文件系统、搜索、proot 进程共用同一次挂载快照（M2-A「同源挂载」原则：
 * 否则设备存储开关一变，Read 看到的和 Bash 里 `cat` 看到的就是两个世界）。
 * 开关变了换一代，[MinHostPorts] 保证每次调用只用一代、新一代只在空闲时接上（设计 10.2 拍板 5）。
 */
class MinHostGeneration(
    val fs: RootfsFileSystem,
    val search: JvmSearch,
    val exec: Exec,
    val mountKey: String,
    /** 长寿进程（MCP stdio 服务器）：与 [exec] 同一份挂载快照。默认不可用，测试替身不用管 */
    val processes: ProcessPort = ProcessPort.Unavailable,
)

/**
 * 三个端口都是「每次调用现取当前一代」的转发：内核的 [KernelDeps] 一个进程只建一次，
 * 而挂载快照会因设置变化而换代，所以不能把某一代的实例直接塞进去。
 */
class MinHostPorts(private val current: () -> MinHostGeneration, tasks: MinBackgroundTasks, clock: Clock) {
    fun canonicalPath(path: GuestPath): GuestPath = current().fs.canonical(path)
    val fs: WorkspaceFs = object : WorkspaceFs {
        override suspend fun stat(path: GuestPath): FileStat? = current().fs.stat(path)
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = current().fs.read(path, offset, limit)
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = current().fs.writeAtomic(path, bytes)
        override suspend fun list(dir: GuestPath): List<DirEntry> = current().fs.list(dir)
        override fun scopeOf(path: GuestPath): PathScope = current().fs.scopeOf(path)
    }
    val search: Search = object : Search {
        override fun grep(query: GrepQuery): Flow<GrepHit> = current().search.grep(query)
        override fun glob(query: GlobQuery): Flow<GuestPath> = current().search.glob(query)
    }
    val exec: Exec = MinExec(current, tasks, clock)

    /**
     * 每次 spawn 取当前一代；返回的 [dev.min.agent.spi.LiveProcess] 自己握着进程与回收手段，换代后留在起它的那一代，
     * 关停不再经过这里（所以不需要像 [MinExec] 那样登记属主）。
     */
    val processes: ProcessPort = object : ProcessPort {
        override suspend fun spawn(spec: ProcessSpec): LiveProcess = current().processes.spawn(spec)
    }
    fun artifacts(session: SessionId): ArtifactSink = object : ArtifactSink {
        override suspend fun put(name: String, bytes: ByteArray): String {
            val path = artifactPath(session, name)
            current().fs.writeAtomic(path, bytes)
            return path.value
        }
    }
}

/**
 * 附件的 guest 路径。名字的校验与桌面宿主共用 [dev.min.agent.spi.ArtifactNames.validate]（允许 `tool-results/…` 这类子目录，
 * 每段只收安全字符、不许 `.` / `..`）。以前这里整个名字按一段校验，带子目录的名字一律抛异常——超预算的工具结果、MCP 返回的图都存不下来
 */
internal fun artifactPath(session: SessionId, name: String): GuestPath =
    GuestPath("/root/.min/artifacts/${session.value}/${dev.min.agent.spi.ArtifactNames.validate(name)}")

/**
 * 转发到当前一代的 proot 进程，并登记转入后台的命令。杀进程要回到**起它的那一代**：
 * 换代后旧一代的 [dev.min.code.core.engine.min.ProotExec] 还握着那个进程，新一代里没有它，按 id 找不到就杀不掉。
 */
internal class MinExec(
    private val current: () -> MinHostGeneration,
    private val tasks: MinBackgroundTasks,
    @Suppress("unused") private val clock: Clock,
) : Exec {
    private val owner = ConcurrentHashMap<String, Exec>()

    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
        val exec = current().exec
        val result = exec.run(command, options)
        if (result is ExecResult.Backgrounded) {
            owner[result.handle.id] = exec
            tasks.started(result.handle, command.script)
        }
        return result
    }

    override suspend fun kill(handle: ExecHandle) {
        (owner[handle.id] ?: current().exec).kill(handle)
        tasks.stopped(handle.id)
    }

    // 存活探测转发给 ProotExec：不转发的话端口默认「说不了」，内核退回 kill -0 轮询。句柄要问起它的那一代：
    // 换代后新一代的运行表里没有旧句柄，会把还在跑的后台命令报成「不在了」
    override suspend fun isAlive(handle: ExecHandle): Boolean? = (owner[handle.id] ?: current().exec).isAlive(handle)

    // 退出码在起它的那一代手里：换代后新一代查不到旧句柄，与 kill 同理要回到 owner
    override suspend fun exitOf(handle: ExecHandle): Int? = (owner[handle.id] ?: current().exec).exitOf(handle)
    override fun currentPid(): Long = current().exec.currentPid()
    override suspend fun isPidAlive(pid: Long): Boolean? = current().exec.isPidAlive(pid)
}

/**
 * 工具表（设计 5.1）：M2-A 的七个文件 / 命令工具，加子 agent 三件套（在 `:agent:tools`，不在 host 模块——设计原文写错了位置）、
 * 三个交互工具，再是任务清单四件与 Skill。顺序固定、新工具只往末尾加：工具说明是稳定前缀的一部分，换序会让提示缓存整段失效。
 * 自定义子 agent 类型（`.claude/agents/`）这一步还没接：App 没有 `:agent:ext` 依赖，Agent 工具只列内置三类。
 */
fun minTools(): List<Tool<*>> = listOf(
    ReadTool, WriteTool, EditTool, NotebookEditTool, GlobTool, GrepTool, BashTool,
    AgentToolOf(emptyList()), SendMessageTool, TaskStopTool,
    AskUserQuestionTool, EnterPlanModeTool, ExitPlanModeTool,
    TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool,
    SkillTool,
    dev.min.agent.tools.EnterWorktreeTool, dev.min.agent.tools.ExitWorktreeTool,
    // M5：agent 团队与定时任务（只往末尾追加）
    dev.min.agent.tools.TeamCreateTool, dev.min.agent.tools.TeamDisbandTool,
    dev.min.agent.tools.CronCreateTool, dev.min.agent.tools.CronDeleteTool, dev.min.agent.tools.CronListTool, dev.min.agent.tools.ScheduleWakeupTool,
    // 联网：WebFetch 始终在表里（走宿主侧 HTTP，权限是 `WebFetch(domain:…)` 规则）；WebSearch 要配了搜索后端才有，
    // 随设置开关进出，由 [minToolsWithDevice] 的门控接在表的最末尾
    WebFetchTool,
)

object MinKernelDeps {
    val CHECKPOINT_BUDGET = SnapshotBudget(15_000, 60_000, 20_000, 256L * 1024 * 1024)
    /** 平台描述写进系统提示，也是前缀的一部分 */
    const val PLATFORM = "Ubuntu 24.04 (proot on Android)"

    /** 用户主目录：记忆文件（`~/.claude/CLAUDE.md`）、settings 规则从这里读 */
    val MEMORY_HOME = GuestPath("/root")

    fun build(
        models: ModelResolver,
        ports: MinHostPorts,
        clock: Clock = Clock { System.currentTimeMillis() },
        ids: IdGenerator = IdGenerator { "${it}_${UUID.randomUUID().toString().replace("-", "")}" },
        zone: ZoneId = ZoneId.systemDefault(),
        tools: List<Tool<*>> = minTools(),
        snapshots: WorkspaceSnapshots = WorkspaceSnapshots.Unavailable,
        mcpTokenStore: dev.min.agent.spi.McpTokenStore = dev.min.agent.spi.McpTokenStore.Unavailable,
        mcpAuthorizer: dev.min.agent.spi.McpAuthorizer = dev.min.agent.spi.McpAuthorizer.Unavailable,
        /** 项目层 hooks 与项目 `.mcp.json` 共用的工作区信任；默认不信任 */
        trustProjectHooks: (GuestPath) -> Boolean = { false },
        /** MCP 的 url 模式提问：界面有卡片让人看清网址、点了才打开才为真，否则内核直接回 decline */
        canAnswerMcpUrlElicitation: Boolean = false,
        workflowsEnabled: () -> Boolean = { true },
        workflowApprovals: dev.min.agent.spi.WorkflowApprovals = dev.min.agent.spi.WorkflowApprovals.SessionOnly,
        /** auto 审查的默认模型：同一个供应商档的目录小模型，没有返回 null（内核再退回会话模型自己） */
        providerFastModel: (String) -> dev.min.agent.protocol.ModelRef? = { null },
        /**
         * WebFetch 的网页端口：宿主侧 HTTP（不经 proot，与远程 MCP 同理），页面转换用桌面同一份 [HtmlPageConverter]，
         * 缓存 15 分钟、总截止 5 分钟（[CachedWebPort] 的默认值，与桌面 CLI 的默认一致）。测试换成脚本化传输，别真联网
         */
        web: WebPort = CachedWebPort(HtmlPageConverter),
        /**
         * WebSearch 的搜索后端。由调用方做成「每次搜索现取设置」的转发（见 [ConfiguredSearchBackend]）：
         * [KernelDeps] 一个进程只建一次，而用户随时会在设置里改后端
         */
        searchBackend: SearchBackend? = null,
    ): KernelDeps = KernelDeps(
        networkProxy = dev.min.agent.host.LoopbackNetworkProxy(maxConnections = 16),
        clock = clock, ids = ids, models = models, tools = tools,
        host = HostPorts(
            ports.fs, ports.search, ports.exec, { ports.artifacts(it) }, httpHooks = JvmHttpHookClient(), processes = ports.processes,
            snapshots = snapshots,
            // 远程 MCP 服务器走宿主侧的 HTTP（不经 proot）；stdio 服务器经 processes 进 proot
            mcpHttp = dev.min.agent.host.UrlConnectionTransport(),
            web = web, searchBackend = searchBackend,
        ),
        platform = PLATFORM, zone = zone, memoryHome = MEMORY_HOME,
        checkpointBudget = CHECKPOINT_BUDGET,
        // proot 里冷启动 npx 一类的服务器常超过 CC 的 30 秒默认
        mcpConnectTimeoutMs = 120_000,
        mcpTokenStore = mcpTokenStore,
        mcpAuthorizer = mcpAuthorizer,
        trustProjectHooks = trustProjectHooks,
        canonicalPath = ports::canonicalPath,
        canAnswerMcpUrlElicitation = canAnswerMcpUrlElicitation,
        workflowEngine = dev.min.agent.workflow.QuickJsWorkflowEngine(),
        workflowConcurrency = 4,
        workflowsEnabled = workflowsEnabled,
        workflowApprovals = workflowApprovals,
        providerFastModel = providerFastModel,
    )
}
