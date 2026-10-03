package dev.min.agent.cli

import dev.min.agent.host.JvmHttpHookClient
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.HubLimits
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionHub
import dev.min.agent.tools.AgentType
import dev.min.agent.tools.AgentToolOf
import dev.min.agent.kernel.AgentCatalog
import dev.min.agent.ext.config.ClaudeConfig
import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.Secret
import dev.min.agent.spi.PageConverter
import dev.min.agent.spi.PageContent
import dev.min.agent.tools.web.HtmlToMarkdown
import dev.min.agent.spi.Secrets
import dev.min.agent.store.FileSessionStore
import dev.min.agent.spi.Tool
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
import dev.min.agent.tools.TaskCreateTool
import dev.min.agent.tools.TaskGetTool
import dev.min.agent.tools.TaskListTool
import dev.min.agent.tools.SkillTool
import dev.min.agent.tools.TaskStopTool
import dev.min.agent.tools.TaskUpdateTool
import dev.min.agent.tools.WriteTool
import dev.min.agent.tools.WebFetchTool
import dev.min.agent.tools.WebSearchTool
import dev.min.agent.host.CachedWebPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

/**
 * 桌面宿主的工具表。顺序就是发给模型的顺序，属于稳定前缀：只往末尾追加，不重排、不删除
 * （docs/agent/07-工程规范.md 第 10 条），否则所有会话的缓存前缀一起失效。
 */
internal fun desktopTools(customAgents: List<AgentType> = emptyList(), searchConfigured: Boolean = false): List<Tool<*>> = listOf(
    ReadTool, WriteTool, EditTool, NotebookEditTool, GlobTool, GrepTool, BashTool,
    AskUserQuestionTool, EnterPlanModeTool, ExitPlanModeTool,
    AgentToolOf(customAgents), SendMessageTool, TaskStopTool,
    TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool,
    SkillTool,
    WebFetchTool,
) + if (searchConfigured) listOf(WebSearchTool) else emptyList()

internal val DESKTOP_TOOLS: List<Tool<*>> = desktopTools()

/** host 只认识 SPI；tools 的 HTML 转换器在组合根适配并注入。 */
internal val DESKTOP_PAGE_CONVERTER = PageConverter { html, baseUrl ->
    val converted = HtmlToMarkdown.convert(html, baseUrl)
    PageContent(converted.title, converted.markdown)
}

/**
 * 桌面宿主的组合根：把内核与各个端口实现装在一起（docs/agent/02-总体架构.md §2：构造注入，一处组装）。
 * 模型能力来自随包目录，命令行可以覆盖或描述目录外的模型。
 */
internal class DesktopApp(
    private val args: CliArgs,
    private val scope: CoroutineScope,
    private val home: Path = Path.of(System.getProperty("user.home"), ".min-agent"),
    /** 找 `~/.claude/`（settings、CLAUDE.md、agents）的主目录。测试传临时目录，免得读到开发机上的真实配置 */
    private val claudeHome: Path = Path.of(System.getProperty("user.home")),
) {
    private val paths = DesktopPaths(workspace = args.cwd, scratchRoot = home.resolve("root"))
    private val store = FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })

    private val selection = resolveModel(args, Catalog.bundled())
    private val provider = selection.provider
    private val profile = selection.profile
    private val keyEnv = selection.keyEnv
    private val fastSelection = args.fastModel?.let {
        resolveModel(args.copy(model = it, contextWindow = null, maxOutput = null), Catalog.bundled())
    }
    private val searchBackend = desktopSearchBackend(args)
    private var hubInstance: SessionHub? = null

    /**
     * 第一次要用时才建：先按 cwd 读一遍 Claude Code 配置，把 `.claude/agents/` 的自定义子 agent 交给 Hub、列进 Agent 工具的说明。
     * 一个 CLI 进程只服务一个项目目录，所以读一次就够；权限规则与记忆由内核在每个会话创建时各自快照。
     */
    private suspend fun hub(): SessionHub = hubInstance ?: run {
        val config = config()
        val catalog = AgentCatalog.of(config.agents)
        val tools = desktopTools(config.agents.map { AgentType(it.name, it.description) }, searchBackend != null)
        SessionHub(store, deps(tools), scope, HUB_LIMITS, catalog.definitions).also { hubInstance = it }
    }

    private var configInstance: ClaudeConfig? = null

    /** 启动时读一遍的 Claude Code 配置：Hub 用它的子 agent，斜杠命令用它的 skills 与 commands（同一份，进程内不重读） */
    suspend fun config(): ClaudeConfig = configInstance ?: ClaudeConfigLoader.load(paths.fs, paths.guestOfHost(args.cwd), userHome).also { configInstance = it }

    /** 斜杠命令展开用的会话事实（CLAUDE_SESSION_ID 等变量）；cwd 取模型看到的 guest 路径 */
    fun skillVars(runtime: SessionRuntime) = SkillVars(
        sessionId = runtime.sessionId.value,
        projectDir = paths.guestOfHost(args.cwd).value,
        effort = runtime.state.value.settings.effort?.name?.lowercase(),
    )

    private val userHome get() = paths.guestOfHost(claudeHome)

    /** 凭证只从环境变量读、只进请求头；这里不打印、不落盘 */
    private val secrets = Secrets { providerId ->
        if (providerId == provider.id) System.getenv(keyEnv)?.takeIf { it.isNotBlank() }?.let(::Secret) else null
    }

    fun checkKey() {
        if (System.getenv(keyEnv).isNullOrBlank()) throw UsageException("没有凭证：请设置环境变量 $keyEnv")
    }

    suspend fun records(sessionId: SessionId, fromSeq: Long): List<EventRecord> =
        requireNotNull(store.open(sessionId)).read(fromSeq).toList()

    private fun deps(tools: List<Tool<*>>): KernelDeps {
        // Exec 端口为整个 Hub 共用，句柄的随机 id 保证日志互不覆盖。
        val logDir = Files.createDirectories(home.resolve("exec-logs"))
        val transport = UrlConnectionTransport()
        val client = when (provider.wire) {
            Wire.AnthropicMessages -> AnthropicMessagesClient(provider, transport, secrets)
            Wire.OpenAIChat -> OpenAIChatClient(provider, transport, secrets)
            Wire.OpenAIResponses -> OpenAIResponsesClient(provider, transport, secrets)
            else -> throw UsageException("CLI 尚不支持协议 ${provider.wire}")
        }
        return KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = IdGenerator { prefix -> "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(20)}" },
            models = { ref ->
                val selected = if (ref == profile.ref) profile else fastSelection?.profile?.takeIf { it.ref == ref }
                checkNotNull(selected) { "桌面 CLI 没有配置会话需要的模型 $ref" }
                ResolvedModel(selected, client)
            },
            tools = tools,
            host = HostPorts(
                fs = paths.fs,
                search = JvmSearch(paths.fs),
                exec = LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir)),
                processes = LocalProcessPort(
                    paths::hostOf, home.resolve("proc-launch"),
                    secretEnvNames = desktopSecretEnvNames(keyEnv, fastSelection?.keyEnv),
                ),
                web = CachedWebPort(DESKTOP_PAGE_CONVERTER, cacheTtlMs = args.webCacheTtlMs, deadlineMs = args.webDeadlineMs),
                searchBackend = searchBackend,
                httpHooks = JvmHttpHookClient(),
                artifacts = { sessionId ->
                    val artifactsDir = home.resolve("sessions").resolve(sessionId.value).resolve("artifacts")
                    object : ArtifactSink {
                        override suspend fun put(name: String, bytes: ByteArray): String {
                            val file = artifactsDir.resolve(name)
                            Files.createDirectories(file.parent)
                            Files.write(file, bytes)
                            return paths.guestOfHost(file).value
                        }
                    }
                },
            ),
            platform = platformDescription(),
            zone = ZoneId.systemDefault(),
            // 与 CC 读同一份 ~/.claude/CLAUDE.md：桌面上和 Claude Code 对照评测时两边看到的用户说明一致
            memoryHome = userHome,
            // hook 脚本在本机 shell 里跑：guest 路径换成本机 shell 认得的写法（与 LocalExec 的映射一致）
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { args.trust },
            fastModel = fastSelection?.profile?.ref,
            maxWebSearches = args.maxWebSearches,
            env = { name -> System.getenv(name) },
        )
    }

    suspend fun openSession(): SessionRuntime {
        args.resume?.let { id ->
            val sessionId = SessionId(id)
            return hub().open(sessionId)
                ?: throw UsageException("找不到会话 $id")
        }
        // S1 的 create/open 已把 Hub 接到运行时；委派时自动 watch，ToolExecutor 按回合/调用取 AgentPort。
        return hub().create(paths.guestOfHost(args.cwd), profile.ref, args.mode, args.effort)
    }

    private fun platformDescription(): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        return if (os.startsWith("Windows")) "$os (commands run in Git Bash; drives are /c, /d, …)" else os
    }

    private companion object {
        val HUB_LIMITS = HubLimits(maxDepth = 3, maxConcurrentChildRequests = 8)
    }
}
