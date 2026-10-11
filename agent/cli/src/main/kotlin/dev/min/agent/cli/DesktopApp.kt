package dev.min.agent.cli

import dev.min.agent.host.JvmHttpHookClient
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.HubLimits
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionBranchRefused
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionEndReasons
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.StructuredOutputPlan
import dev.min.agent.tools.AgentType
import dev.min.agent.tools.AgentToolOf
import dev.min.agent.kernel.AgentCatalog
import dev.min.agent.ext.config.ClaudeConfig
import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.config.fallbackModelSetting
import dev.min.agent.ext.config.availableModelsSetting
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.catalog.ModelAliases
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.Secret
import dev.min.agent.tools.web.HtmlPageConverter
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
import dev.min.agent.tools.CronCreateTool
import dev.min.agent.tools.CronDeleteTool
import dev.min.agent.tools.CronListTool
import dev.min.agent.tools.ScheduleWakeupTool
import dev.min.agent.tools.TeamCreateTool
import dev.min.agent.tools.TeamDisbandTool
import dev.min.agent.host.CachedWebPort
import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.spi.SnapshotBudget
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

/**
 * 交给内核的环境变量读取口（[dev.min.agent.kernel.KernelDeps.env]）：读进程环境，但 [hidden]（CLI 自己读模型 key 的变量）一律返回 null。
 * [read] 默认读真实进程环境，测试可替换。
 */
internal fun hookEnvReader(hidden: Set<String>, read: (String) -> String? = System::getenv): (String) -> String? =
    { name -> if (name in hidden) null else read(name) }

/**
 * 桌面宿主的工具表。顺序就是发给模型的顺序，属于稳定前缀：只往末尾追加，不重排、不删除
 * （docs/agent/07-工程规范.md 第 10 条），否则所有会话的缓存前缀一起失效。
 */
internal fun desktopTools(
    customAgents: List<AgentType> = emptyList(),
    searchConfigured: Boolean = false,
    /** false = `-p` 一次性运行：不给团队与定时的 6 个工具（见 [M5_TOOLS]） */
    interactive: Boolean = true,
    /** false = `CLAUDE_CODE_DISABLE_CRON`：定时任务的 4 个工具（CronCreate / CronDelete / CronList / ScheduleWakeup）也不提供 */
    cronEnabled: Boolean = true,
    /** true = `--bare`（CC :72）：只留原文点名的 Bash、读文件、改文件。NotebookEdit 算改文件——CC 的 Edit(…) 路径规则同样罩住它，保留 */
    bare: Boolean = false,
): List<Tool<*>> =
    // bare 档的 Bash 没有后台任务（CC headless 页：none run，到超时就停），由实例自己管 schema / 描述 / 前台预算
    if (bare) listOf(ReadTool, WriteTool, EditTool, NotebookEditTool, BashTool(allowBackground = false)) else
    listOf(
    ReadTool, WriteTool, EditTool, NotebookEditTool, GlobTool, GrepTool, BashTool(), dev.min.agent.tools.LspTool,
    AskUserQuestionTool, EnterPlanModeTool, ExitPlanModeTool,
    AgentToolOf(customAgents), SendMessageTool, TaskStopTool,
    TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool,
    SkillTool,
    WebFetchTool,
) + (if (searchConfigured) listOf(WebSearchTool) else emptyList()) + listOf(dev.min.agent.tools.EnterWorktreeTool, dev.min.agent.tools.ExitWorktreeTool) +
    // 后台任务：watch 与按 id 取输出（TaskStop 在表的前部，已能停后台命令与 watch）。`-p` 也给：watch 期限最长 10 分钟，回合一结束进程就退
    listOf(dev.min.agent.tools.MonitorTool, dev.min.agent.tools.TaskOutputTool) +
    // M5：agent 团队与定时任务（只往末尾追加）。`-p` 不给：Claude Code 的 `-p` 没有队友，进程一退定时任务永不触发，
    // 模型在 `-p` 里建了团队 / 定时任务，回合一结束进程就走了，成员被丢、任务根本不会触发，还会向用户承诺不存在的事
    (if (!interactive) emptyList() else if (cronEnabled) M5_TOOLS else M5_TOOLS.filterNot { it.name in CRON_TOOL_NAMES }) +
    // 结束会话（EndConversation）：只在交互的根会话里（CC：-p、--bare、子 agent 都没有）；子 agent 的过滤在内核的 availableTools
    (if (interactive) listOf(dev.min.agent.tools.EndConversationTool) else emptyList()) +
    // 系统通知（PushNotification）：桌面没有通知栏，打一行到标准错误（与内核的别的提示同一条路）
    listOf(dev.min.agent.tools.PushNotificationTool) +
    // 能发消息的对象（ListAgents）：只列本会话的子 agent 与团队成员
    listOf(dev.min.agent.tools.ListAgentsTool) +
    // 把文件发给用户（SendUserFile）：桌面打印路径（见下面的 userFileSink）
    listOf(dev.min.agent.tools.SendUserFileTool) +
    // 内置 /code-review 的审查结果列表（只往末尾追加）；bare 档的五件套不含它，bare 也不发现 skills
    listOf(dev.min.agent.tools.ReportFindingsTool) +
    // 本机预览（Artifact）与草稿（SendFeedback）：只往末尾追加。桌面没有预览位与草稿队列，见下面的 previewSink / feedbackSink
    listOf(dev.min.agent.tools.ArtifactTool, dev.min.agent.tools.SendFeedbackTool)

/** 团队与定时调度的工具（只在交互会话里提供） */
private val M5_TOOLS: List<Tool<*>> = listOf(TeamCreateTool, TeamDisbandTool, CronCreateTool, CronDeleteTool, CronListTool, ScheduleWakeupTool)

private val CRON_TOOL_NAMES = setOf("CronCreate", "CronDelete", "CronList", "ScheduleWakeup")

internal val DESKTOP_TOOLS: List<Tool<*>> = desktopTools()

/**
 * 全部内置工具的名字（不论这次启动配没配搜索、是不是交互、有没有定时）：`--tools` 据此报未知名。
 * 名字不在这里 = 不是 Min 的内置工具（写错了，或是 MCP 工具——后者 `--tools` 不管）
 */
internal val ALL_BUILTIN_TOOL_NAMES: Set<String> by lazy { desktopTools(searchConfigured = true).map { it.name }.toSet() }

/**
 * `--tools`（CC :136）：内置工具表按点名收窄；null = 预设，不收窄。表序不变（稳定前缀，见 [desktopTools]）。
 * 只收窄内置工具，MCP 工具不经这里
 */
internal fun selectTools(all: List<Tool<*>>, named: Set<String>?, restricted: Boolean = false): List<Tool<*>> {
    val byName = if (named == null) all else all.filter { it.name in named }
    if (!restricted) return byName
    // --restricted（CC :123）：去掉的几个只有逐个点名才放回；default 预设不算点名
    return byName.filter { it.name !in RESTRICTED_REMOVED_TOOLS || named != null && it.name in named }
}

/**
 * CC `--restricted` 去掉的内置工具（原文：「the built-in tools that run commands or code, and WebFetch」）：
 * Bash 与 Monitor（跑 shell 命令）、WebFetch。Workflow（跑 JavaScript）不在这张表里，由内核在 availableTools 里去掉。
 * NotebookEdit 只改笔记本单元格、不执行，照原文不算。
 */
internal val RESTRICTED_REMOVED_TOOLS: Set<String> = setOf("Bash", "Monitor", "WebFetch")

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

    /** 这次启动的工作目录（宿主路径）：信任确认与 /cd 之类的口子用 */
    internal val cwd: Path get() = args.cwd

    /**
     * 工作区信任账本（`~/.min-agent/workspace-trust.txt`）。交互首次确认的「信任」记在这里；`-p` 只读不写。
     * 为什么只在组合根里持有：内核的 trustProjectHooks 与插件信任都从 [isWorkspaceTrusted] 读同一个答案。
     */
    private val workspaceTrust = WorkspaceTrustLedger(home.resolve("workspace-trust.txt"))

    /**
     * 这个进程里信任的 home 目录：CC 的口径，在主目录里接受的信任只管这一次运行，不写盘（否则每个项目都等于信任了）。
     */
    private var homeTrustedThisRun = false

    /** 这个目录当前算不算信任：`--trust`、账本里信任过（自己或祖先）、或本进程里在 home 目录接受过 */
    internal fun isWorkspaceTrusted(): Boolean =
        args.trust || homeTrustedThisRun || workspaceTrust.decision(args.cwd.toString()) == true

    /**
     * 用户在信任确认里选了「信任」。主目录只记本进程；其余目录记账本，下次不再问。
     */
    internal fun acceptWorkspaceTrust() {
        if (isHomeDirectory(args.cwd)) homeTrustedThisRun = true else workspaceTrust.recordTrusted(args.cwd.toString())
    }

    private fun isHomeDirectory(dir: Path): Boolean =
        dir.toAbsolutePath().normalize() == Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
    private val store = FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })
    private val mcpTokens = FileMcpTokenStore(home.resolve("mcp-oauth.json"))
    internal val mcpAuthorizer = DesktopMcpAuthorizer(args.noBrowser)

    suspend fun loginMcp(server: String, runtime: SessionRuntime, noBrowser: Boolean) {
        mcpAuthorizer.noBrowser=args.noBrowser || noBrowser
        try { hub().loginMcp(server,runtime.sessionId) } finally { mcpAuthorizer.noBrowser=args.noBrowser }
    }
    suspend fun logoutMcp(server: String,runtime: SessionRuntime) = hub().logoutMcp(server,runtime.sessionId)
    internal val snapshots = CasWorkspaceSnapshots(home, { paths.fs }, paths.guestOfHost(claudeHome))

    private val selection = resolveModel(args, Catalog.bundled())
    private val provider = selection.provider
    private val profile = selection.profile
    private val keyEnv = selection.keyEnv
    /**
     * 这次启动的定制档位（`--bare` / `--safe-mode` 与两个环境变量的归一，[resolveCustomizations]）：
     * deps 交给内核，config() / hub() 的加载点读同一份
     */
    private val customizations = resolveCustomizations(args.bare, args.safeMode)
    /** 这次启动读哪些设置：`--settings` 的一份（CC :128）。内核与 config() 读同一份 */
    private val settingsInputs = dev.min.agent.ext.config.SettingsInputs(cli = args.settings, restricted = args.restricted, addDirs = args.addDirs.map(paths::guestOfHost))
    private val fastSelection = args.fastModel?.let {
        resolveModel(args.copy(model = it, contextWindow = null, maxOutput = null), Catalog.bundled())
    }
    /** 回退链：读完 settings 才知道（[hub] 里填），之前是空的 */
    private var fallback = FallbackModels.None
    /** availableModels：读完 settings 才知道（[hub] 里填）；null = 不限制。托管层的名单只能从这里来，低层拼不进去 */
    private var modelListing: dev.min.agent.ext.config.AvailableModelsSetting? = null

    /**
     * availableModels 的判定（切模型、子 agent、顾问、审查、回退链都用它）：别名先解析成这家供应商的型号再比，
     * 解析不了（这家没有这一档）当不在名单里。没有名单就放行。
     */
    internal fun modelListed(ref: dev.min.agent.protocol.ModelRef): Boolean {
        val listing = modelListing ?: return true
        val concrete = try { ModelAliases.resolve(ref, Catalog.bundled()).ref } catch (_: IllegalArgumentException) { return false }
        return dev.min.agent.ext.config.AvailableModels.allows(concrete.modelId, listing.entries)
    }
    private val searchBackend = desktopSearchBackend(args)
    private var hubInstance: SessionHub? = null

    /** stream-json 的 init 行要列工具名；跟 Hub 用同一份工具表 */
    private var toolNamesCache: List<String> = emptyList()

    /**
     * 第一次要用时才建：先按 cwd 读一遍 Claude Code 配置，把 `.claude/agents/` 的自定义子 agent 交给 Hub、列进 Agent 工具的说明。
     * 一个 CLI 进程只服务一个项目目录，所以读一次就够；权限规则与记忆由内核在每个会话创建时各自快照。
     */
    internal suspend fun hub(): SessionHub = hubInstance ?: run {
        val config = config()
        // --restricted 拒绝 bypassPermissions（CC :123）：设置里写了 defaultMode bypassPermissions 也在启动时报错。
        // 说明：Min 目前不把 permissions.defaultMode 用作会话模式（模式只由 --mode 定），所以这里是显式的启动错误，
        // 运行中的切换由内核的锁拦（KernelDeps.settingsInputs.restricted → disableBypassPermissionsMode）
        if (args.restricted && config.settings.merged.permissions.defaultMode == "bypassPermissions") {
            throw UsageException("--restricted 不允许 bypassPermissions：设置里的 permissions.defaultMode 写了它，去掉它或改掉")
        }
        modelListing = config.settings.merged.availableModelsSetting()
        // 启动的主模型不在名单里就不启动（Min 的 --model 必填，没有默认可以替换；CC 在这种情况下换成默认模型并警告）
        if (!modelListed(selection.profile.ref)) throw UsageException("--model ${selection.profile.ref.modelId} 不在 availableModels 里（${if (modelListing?.managed == true) "托管策略" else "设置"}），换一个模型")
        fallback = resolveFallbackModels(args, Catalog.bundled(), config.settings.merged.fallbackModelSetting(), selection, allowed = ::modelListed)
        fallback.notes.forEach { System.err.println(it) }
        validateBudgetPricing()
        val catalog = AgentCatalog.of(config.agents)
        val interactive = args.prompt == null
        val cronEnabled = !cronDisabledBy(System::getenv)
        // --bare：工具表收窄到 CC 点名的那几个（其余档位全表）
        val tools = selectTools(desktopTools(config.agents.map { AgentType(it.name, it.description) }, searchBackend != null, interactive, cronEnabled,
            bare = customizations == dev.min.agent.kernel.Customizations.Bare), args.tools, restricted = args.restricted)
        toolNamesCache = tools.map { it.name }
        val limits = HUB_LIMITS.copy(
            teamMaxConcurrent = teamMaxConcurrentFrom(config.settings.merged.raw("teamMaxConcurrent"), System.err::println),
            cronMaxBudgetUsd = cronMaxBudgetUsdFrom(config.settings.merged.raw("cronMaxBudgetUsd"), System.err::println),
            cronEnabled = cronEnabled,
        )
        // 本机其他进程的会话：登记表与收件目录放在用户目录下（只本用户可读写）。只有交互会话挂这个：它的收件要靠心跳取，
        // `-p` 跑一次就退出，登记了却没人取会让发件人误以为送到了。退出时 shutdown 会注销本进程的会话；
        // 进程被杀的，别的进程看见它的登记进程已死，顺手清掉
        val peers = if (interactive) dev.min.agent.kernel.FilePeerTransport(home.resolve("peers")) else null
        SessionHub(store, deps(tools), scope, limits, catalog.definitions, peers = peers).also {
            hubInstance = it
            // 交互会话开着的时候定时任务才有意义；`-p` 跑一次就退出，不挂定时器。定时任务的开关在引擎里查（CronEngine 的 enabled），
            // 心跳不跟它走：`/goal` 的 check-in 也挂在这同一个心跳上，定时任务关着时它照常叫醒
            if (interactive) it.startScheduling(dev.min.agent.kernel.InProcessScheduler(scope))
        }
    }

    /** stream-json 的 init 行用：`供应商/模型 id` */
    internal fun modelLabel(): String = "${provider.id}/${profile.ref.modelId}"

    /** stream-json 的 initialize 应答用：磁盘上可用的输出样式名（与 `/output-style` 列的同一份） */
    internal suspend fun availableStyleNames(): List<String> = outputStyles().values.map { it.name }

    /**
     * stream-json 的 `set_model` 控制请求：`null` / `default` = 启动时的主模型；别的只能是这次启动配置过的模型
     * （主模型、`--fast-model`、`--fallback-model` 各项）——桌面 CLI 只给这几个备了凭证与客户端，换别的会在下一个请求才炸。
     * 对不上返回 null，调用方回错误应答
     */
    internal fun switchableModel(raw: String?): ModelRef? {
        if (raw == null || raw == "default") return profile.ref
        val ref = try { ModelAliases.resolve(ModelRef(provider.id, raw), Catalog.bundled()).ref } catch (_: IllegalArgumentException) { return null }
        val configured = listOfNotNull(profile.ref, fastSelection?.profile?.ref) + fallback.selections.values.map { it.profile.ref }
        return configured.firstOrNull { it == ref }
    }

    /** stream-json 的助手消息 / 流事件里 `message.model` 用：不带供应商前缀的模型 id（Anthropic 消息里的 model 就是这个形状） */
    internal fun modelId(): String = profile.ref.modelId

    /** stream-json init 行用：已装载插件的名字与根路径（没装载 / safe 档 = 空） */
    internal fun loadedPlugins(): List<Pair<String, String>> =
        pluginBundle?.batch?.plugins.orEmpty().map { it.namespace to it.root }

    /**
     * `--max-budget-usd` fail-closed 的启动面：设了上限，这次运行**可能用到的模型**就都得有 USD 价格——
     * 主模型、回退链、`--fast-model`、点名的 `--auto-mode-reviewer`，一个没有就启动报错（说清是哪个）。
     * 没价格的模型跑起来才发现的话，那笔请求核对不了，上限就成了空话；请求发出前的预检（ModelBilling）
     * 兜底拦剩下的（比如 hook 点名的目录外模型）
     */
    private fun validateBudgetPricing() {
        if (args.maxBudgetUsd == null) return
        val unpriced = linkedSetOf<ModelRef>()
        if (profile.pricing?.currency != "USD") unpriced += profile.ref
        fastSelection?.let { if (it.profile.pricing?.currency != "USD") unpriced += it.profile.ref }
        for (backup in fallback.selections.values) {
            if (backup.profile.pricing?.currency != "USD") unpriced += backup.profile.ref
        }
        args.autoModeReviewer?.let { setting ->
            val resolved = resolveReviewerSelection(dev.min.agent.spi.AutoModeReviewer(dev.min.agent.ext.config.parseReviewerRef(setting)), Catalog.bundled(), selection)
            if (resolved.profile.pricing?.currency != "USD") unpriced += resolved.profile.ref
        }
        if (unpriced.isNotEmpty()) {
            throw UsageException(
                "设了 --max-budget-usd，但这些模型没有 USD 价格：${unpriced.joinToString("、") { it.modelId }}。" +
                    "目录里查不到可靠价格的模型量不出 USD 花费，上限核对不了；换有 USD 定价的模型，或去掉上限。")
        }
    }

    internal fun toolNames(): List<String> = toolNamesCache

    private var configInstance: ClaudeConfig? = null

    /** 启动时读一遍的 Claude Code 配置：Hub 用它的子 agent，斜杠命令用它的 skills 与 commands（同一份，进程内不重读） */
    private var pluginBundle: dev.min.agent.ext.plugins.PluginBundle? = null
    suspend fun config(): ClaudeConfig {
        configInstance?.let { return it }
        val base = ClaudeConfigLoader.load(paths.fs, paths.guestOfHost(args.cwd), userHome, inputs = settingsInputs)
        // --bare / --safe-mode：skills / 命令 / 子 agent 定义（含托管层）这次启动不发现（CC :72/:125 的跳过清单；
        // safe 原文的托管保留清单里也没有它们）。settings 照常读——权限规则、模型等键不在清单里
        // --bare 只留 --add-dir 目录里的 skills（CLI reference：「Skills in a directory you pass with --add-dir still load」），命令与子 agent 不留
        val definitions = if (customizations == dev.min.agent.kernel.Customizations.Full) base
            else if (customizations == dev.min.agent.kernel.Customizations.Bare) base.copy(skills = base.skills.filter { it.source == dev.min.agent.ext.config.ConfigSource.AddDir }, commands = emptyList(), agents = emptyList())
            else base.copy(skills = emptyList(), commands = emptyList(), agents = emptyList())
        // bare 下插件还有一条入口：CC headless 页把 `--plugin-dir` 列为 bare 模式传上下文的方式，所以只装载命令行点名的，
        // 已安装 / 设置启用的照旧不发现（信任口径走 PluginBundle 里现成的 sideload 规则：托管锁定、${'$'}{user_config} 等原样生效）。
        // safe 原文没有这个例外，维持全部不加载
        val plugins = if (customizations == dev.min.agent.kernel.Customizations.Safe) null
        else dev.min.agent.ext.plugins.PluginBundle.load(paths.fs, paths.guestOfHost(args.cwd), userHome, base.settings.merged,
            args.pluginDirs.map(paths::guestOfHost), canonical = { desktopCanonicalCredentialPath(paths, it) },
            trust = { root -> pluginTrusted(root, paths.guestOfHost(args.cwd)) }, secrets = dev.min.agent.ext.plugins.PluginSecretStore(mcpTokens),
            installedPath = { paths.guestOfHost(java.nio.file.Path.of(it)) }, shellPath = { it.value },
            installed = customizations == dev.min.agent.kernel.Customizations.Full,
            // --restricted（Min 的解释）：安装索引不是来源，只剩托管 / --settings 启用与 --plugin-dir
            installIndex = !args.restricted,
        )
        pluginBundle = plugins
        return definitions.copy(skills = definitions.skills + plugins?.skills.orEmpty(), commands = definitions.commands + plugins?.commands.orEmpty(),
            agents = definitions.agents + plugins?.agents.orEmpty(), notes = definitions.notes + plugins?.notes.orEmpty()).also { configInstance = it }
    }

    private fun pluginTrusted(root: GuestPath, cwd: GuestPath): Boolean {
        if (!isWorkspaceTrusted()) return false
        val realRoot = desktopCanonicalCredentialPath(paths, root).value
        val realCwd = desktopCanonicalCredentialPath(paths, cwd).value.trimEnd('/')
        return realRoot == realCwd || realRoot.startsWith("$realCwd/")
    }

    /** 斜杠命令展开用的会话事实（CLAUDE_SESSION_ID 等变量）；cwd 取模型看到的 guest 路径 */
    fun skillVars(runtime: SessionRuntime) = SkillVars(
        sessionId = runtime.sessionId.value,
        projectDir = paths.guestOfHost(args.cwd).value,
        effort = runtime.state.value.settings.effort?.name?.lowercase(),
    )

    /** 会话冻结清单里定义的正文（斜杠展开用）：与模型路径同一条读取（SkillFiles.readBody），不读实时配置 */
    internal suspend fun readSkillBody(path: GuestPath): String? =
        dev.min.agent.spi.catchingNonCancellation {
            pluginBundle?.readBody(path) ?: dev.min.agent.ext.skills.SkillFiles.readBody(paths.fs, path)
        }.getOrNull()

    /** /output-style 用：磁盘上的样式（managed + 用户 + 项目链，近者胜），与内核会话创建时同一套读法 */
    suspend fun outputStyles(): Map<String, dev.min.agent.ext.styles.OutputStyleDef> {
        config()
        return dev.min.agent.ext.styles.OutputStyleFiles.available(paths.fs, paths.guestOfHost(args.cwd), userHome, includeUser = !args.restricted) + pluginBundle?.styles.orEmpty()
    }

    /** /output-style 写回的位置：项目 `.claude/settings.local.json`（CC 同一个键，互通） */
    fun settingsLocalFile(): java.nio.file.Path = args.cwd.resolve(".claude").resolve("settings.local.json")

    fun settingsUserFile(): Path = claudeHome.resolve(".claude").resolve("settings.json")
    fun pluginMarketplace() = desktopMarketplace(args.cwd, isWorkspaceTrusted(), claudeHome)

    fun validateReviewer(reviewer: dev.min.agent.spi.AutoModeReviewer?) {
        reviewer?.let { resolveReviewerSelection(it, Catalog.bundled(), selection) }
    }

    private fun advisorSelection(ref: ModelRef): ModelSelection {
        val selected = resolveAdvisorSelection(ref, Catalog.bundled(), selection)
        if (System.getenv(selected.keyEnv).isNullOrBlank()) throw UsageException("没有顾问凭证：请设置环境变量 ${selected.keyEnv}")
        return selected
    }

    fun validateAdvisor(ref: ModelRef) { advisorSelection(ref) }

    private val userHome get() = paths.guestOfHost(claudeHome)

    /** 凭证只从环境变量读、只进请求头；这里不打印、不落盘。回退链上别家的凭证也只认它自己的那个变量 */
    private val secrets = Secrets { providerId ->
        val env = if (providerId == provider.id) keyEnv else fallback.selections.values.firstOrNull { it.provider.id == providerId }?.keyEnv
            ?: "${providerId.uppercase(java.util.Locale.ROOT)}_API_KEY"
        System.getenv(env)?.takeIf { it.isNotBlank() }?.let(::Secret)
    }

    fun checkKey() {
        if (System.getenv(keyEnv).isNullOrBlank()) throw UsageException("没有凭证：请设置环境变量 $keyEnv")
    }

    suspend fun records(sessionId: SessionId, fromSeq: Long): List<EventRecord> =
        requireNotNull(store.peek(sessionId)).read(fromSeq).toList()

    /**
     * 遥测（OpenTelemetry 的本地等价）：默认关，环境变量开了才返回导出器。
     * 为什么从 fromSeq 开始：只导出这个进程里发生的事，不把以前的会话在这里重放一遍。配错了的地方照样打到 stderr。
     */
    suspend fun telemetryTail(runtime: SessionRuntime): dev.min.agent.otel.OtelTail? {
        val config = dev.min.agent.otel.OtelConfig.fromEnv(System::getenv)
        if (config.enabled) config.problems.forEach { System.err.println("遥测设置有问题：$it") }
        if (!config.active) return null
        val log = requireNotNull(store.peek(runtime.sessionId)) { "遥测读不到会话日志" }
        val exporter = dev.min.agent.otel.OtelExporter(config, UrlConnectionTransport())
        return dev.min.agent.otel.OtelTail(log, config, exporter, fromSeq = runtime.state.value.lastSeq + 1)
    }

    private fun deps(tools: List<Tool<*>>): KernelDeps {
        // Exec 端口为整个 Hub 共用，句柄的随机 id 保证日志互不覆盖。
        val logDir = Files.createDirectories(home.resolve("exec-logs"))
        val transport = UrlConnectionTransport()
        fun clientFor(p: ProviderDescriptor) = when (p.wire) {
            Wire.AnthropicMessages -> AnthropicMessagesClient(p, transport, secrets)
            Wire.OpenAIChat -> OpenAIChatClient(p, transport, secrets)
            Wire.OpenAIResponses -> OpenAIResponsesClient(p, transport, secrets)
            else -> throw UsageException("CLI 尚不支持协议 ${p.wire}")
        }
        val client = clientFor(provider)
        val fallbackClients = fallback.selections.values.map { it.provider }.distinctBy { it.id }
            .associate { it.id to if (it.id == provider.id) client else clientFor(it) }
        val bundledCatalog = Catalog.bundled()
        val keyEnvs = setOfNotNull(keyEnv, fastSelection?.keyEnv) + fallback.selections.values.map { it.keyEnv } +
            bundledCatalog.providers.map { "${it.id.uppercase(java.util.Locale.ROOT)}_API_KEY" }
        // 内核读环境变量都走这一个口（CLAUDE_CODE_MAX_TURNS、hook 的插值……）：模型 key 的变量对内核是 null
        val hiddenEnv: Set<String> = keyEnvs
        val envReader = hookEnvReader(hiddenEnv)
        return KernelDeps(
            networkProxy = dev.min.agent.host.LoopbackNetworkProxy(),
            clock = { System.currentTimeMillis() },
            ids = IdGenerator { prefix -> "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(20)}" },
            models = { requested ->
                // 别名（sonnet / opus …）先解析成这家供应商的型号，再找会话需要的那一个（子 agent、顾问、审查都可能写别名）
                val ref = ModelAliases.resolve(requested, bundledCatalog).ref
                // 回退链按写下的样子（可能是别名）记，所以用 requested 找备用；ref 只用来认主模型与快速模型
                val backup = fallback.selections[requested]
                val selected = if (ref == profile.ref) profile else fastSelection?.profile?.takeIf { it.ref == ref } ?: backup?.profile
                checkNotNull(selected) { "桌面 CLI 没有配置会话需要的模型 $ref" }
                // 备用模型没有凭证就当用不了（内核会跳过它）；不放它去发一个注定 401 的请求再让回合以鉴权失败收场
                if (backup != null && ref != profile.ref && System.getenv(backup.keyEnv).isNullOrBlank()) {
                    throw IllegalStateException("没有凭证：请设置环境变量 ${backup.keyEnv}")
                }
                ResolvedModel(selected, if (backup != null) fallbackClients.getValue(backup.provider.id) else client)
            },
            // availableModels 的强制：切模型、子 agent、顾问、审查都经过内核，这里给同一个判定
            modelAllowed = ::modelListed,
            tools = tools,
            // SendUserFile：桌面没有可推送的客户端，打印文件在磁盘上的路径（终端里能直接打开）
            userFileSink = object : dev.min.agent.spi.UserFileSink {
                override suspend fun deliver(file: dev.min.agent.spi.UserFile): Boolean {
                    System.err.println("[文件] " + (file.caption?.let { "$it：" }.orEmpty()) + paths.hostOf(file.path))
                    return true
                }
            },
            // Read 的 pages：PDFBox 拆出所选页，作为文档块交给模型（见 PdfBoxPages）
            pdfPages = PdfBoxPages,
            // 本机预览（Artifact）：桌面没有预览位，打印文件在磁盘上的路径（终端里能直接打开）
            previewSink = object : dev.min.agent.spi.PreviewSink {
                override suspend fun show(file: dev.min.agent.spi.PreviewFile): Boolean {
                    System.err.println("[预览] " + (file.title?.let { "$it：" }.orEmpty()) + paths.hostOf(file.path))
                    return true
                }
            },
            // 反馈草稿（SendFeedback）：桌面没有草稿队列，导出诊断包 zip 到本机目录并打印路径；什么都不发送。
            // 导出走 FeedbackBundle，凭证在这里就去掉，不靠用户记得检查
            feedbackSink = object : dev.min.agent.spi.FeedbackSink {
                override suspend fun queue(draft: dev.min.agent.spi.FeedbackDraft): Boolean {
                    val dir = home.resolve("feedback").resolve("drafts").toFile().apply { mkdirs() }
                    val zip = java.io.File(dir, "draft-" + System.currentTimeMillis() + ".zip")
                    zip.writeBytes(dev.min.agent.tools.FeedbackBundle.build(draft, draft.sessionLog, "Min desktop CLI", System.getenv(), emptyMap()))
                    System.err.println("[反馈草稿] " + zip.absolutePath + "（未发送）")
                    return true
                }
            },
            // 桌面没有通知栏：PushNotification 打一行到标准错误（与别的提示同一个流）
            pushSink = object : dev.min.agent.spi.PushSink {
                override suspend fun push(title: String?, message: String): Boolean {
                    System.err.println("[通知] " + (title?.let { "$it：" }.orEmpty()) + message)
                    return true
                }
            },
            host = HostPorts(
                fs = paths.fs,
                search = JvmSearch(paths.fs),
                exec = LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir)),
                processes = LocalProcessPort(
                    paths::hostOf, home.resolve("proc-launch"),
                    secretEnvNames = desktopSecretEnvNames(*keyEnvs.toTypedArray()),
                ),
                web = CachedWebPort(HtmlPageConverter, cacheTtlMs = args.webCacheTtlMs, deadlineMs = args.webDeadlineMs),
                webSockets = dev.min.agent.host.SocketWebSocketPort(),
                // 远程 MCP 服务器（Streamable HTTP / 旧 SSE）走同一个宿主 HTTP 实现
                mcpHttp = dev.min.agent.host.UrlConnectionTransport(),
                searchBackend = searchBackend,
                httpHooks = JvmHttpHookClient(),
                snapshots = snapshots,
                artifacts = { sessionId ->
                    val artifactsDir = home.resolve("sessions").resolve(sessionId.value).resolve("artifacts")
                    object : ArtifactSink {
                        override suspend fun put(name: String, bytes: ByteArray): String {
                            // 与 Android 宿主同一道校验：名字里带着供应商给的调用 id，不查就可能写出附件目录
                            val file = artifactsDir.resolve(dev.min.agent.spi.ArtifactNames.validate(name))
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
            credentialPaths = desktopCredentialPaths(paths, home),
            canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
            // hook 脚本在本机 shell 里跑：guest 路径换成本机 shell 认得的写法（与 LocalExec 的映射一致）
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { isWorkspaceTrusted() },
            fastModel = fastSelection?.profile?.ref,
            providerFastModel = { id -> bundledCatalog.provider(id)?.fastModel?.let { dev.min.agent.protocol.ModelRef(id, it) } },
            autoModeReviewer = args.autoModeReviewer?.let { dev.min.agent.spi.AutoModeReviewer(dev.min.agent.ext.config.parseReviewerRef(it)) },
            modelTimeoutMs = args.modelTimeoutMs,
            maxBudgetUsd = args.maxBudgetUsd,
            reviewerModels = { reviewer ->
                val selected = resolveReviewerSelection(reviewer, bundledCatalog, selection)
                ResolvedModel(selected.profile, clientFor(selected.provider))
            },
            advisorModels = { ref ->
                val selected = advisorSelection(ref)
                ResolvedModel(selected.profile, clientFor(selected.provider))
            },
            fallbackModels = fallback.chain,
            maxWebSearches = args.maxWebSearches,
            // --max-turns 优先，没给时读 CLAUDE_CODE_MAX_TURNS（CC：不是正整数就启动时报错退出）。只属于这次运行，不进日志
            rootMaxTurns = resolveMaxTurns(args.maxTurns, envReader),
            // --json-schema（只在 -p 模式有值，CliArgs 已校验）：同样是这次运行的选择，不进日志
            structuredOutput = args.jsonSchema?.let(::StructuredOutputPlan),
            workflowEngine = dev.min.agent.workflow.QuickJsWorkflowEngine(),
            workflowApprovals = dev.min.agent.host.FileWorkflowApprovals(home.resolve("workflow-approvals").toFile()),
            // CLI 自己读模型 key 的变量不交给内核：http hook 的 allowedEnvVars 点名它也只能插成空串（凭证不进 hook 请求）
            env = envReader,
            hiddenEnv = hiddenEnv,
            checkpointBudget = SnapshotBudget.DESKTOP,
            // -p 一上来就发请求、没人在打字：多等一会儿慢服务器，免得它的工具整次运行都缺席（交互仍用默认 5 秒）
            mcpFirstSnapshotWaitMs = if (args.prompt != null) 30_000 else 5_000,
            canAnswerMcpElicitation = args.prompt == null && args.approve != ApprovalAnswer.No,
            canAnswerMcpUrlElicitation = args.prompt == null && args.approve != ApprovalAnswer.No,
            mcpTokenStore = mcpTokens,
            mcpAuthorizer = mcpAuthorizer,
            mcpServerPath = { paths.hostOf(it).toString() },
            lspGuestPath = { uri -> paths.guestOfHost(java.nio.file.Path.of(java.net.URI(uri))) },
            gitPath = { paths.hostOf(it).toString() },
            pluginDirs = args.pluginDirs.map(paths::guestOfHost),
            pluginInstalledPath = { paths.guestOfHost(java.nio.file.Path.of(it)) },
            pluginTrust = { root, cwd -> pluginTrusted(root, cwd) },
            // 这次启动的定制档位（--bare / --safe-mode）：内核的各加载点从这里读，子会话共用同一个 deps 自然跟随
            customizations = customizations,
            settingsInputs = settingsInputs,
        )
    }

    suspend fun openSession(): SessionRuntime {
        val resumeId = args.resume?.let { resolveResume(it) }
            ?: if (args.continueMostRecent) latestRootSession() ?: throw UsageException("当前目录还没有可接着的会话。") else null
        if (resumeId != null) {
            if (!args.forkSession) {
                // 用户主动接着用的会话才算 resume：SessionStart(source = resume) 在这里跑（hooks设计 §7）
                val opened = hub().open(resumeId, resumedByUser = true)
                    ?: throw UsageException("找不到会话 ${resumeId.value}")
                refuseEndedForPrint(opened)
                return opened
            }
            // --fork-session：读原会话（这不算用户 resume），在分支上跑这次的 -p；原会话一个字不动。
            // SessionStart(resume) 跑在分支上——用户接着用的是分支
            hub().open(resumeId) ?: throw UsageException("找不到会话 ${resumeId.value}")
            val branch = hub().branch(resumeId)
            val opened = checkNotNull(hub().open(branch.sessionId, resumedByUser = true))
            refuseEndedForPrint(opened)
            return opened
        }
        // S1 的 create/open 已把 Hub 接到运行时；委派时自动 watch，ToolExecutor 按回合/调用取 AgentPort。
        // --name 建会话时命名（同目录重名时内核自动加 -2 变体）；headless 标记进 SessionCreated：--continue 由此跳过 -p 跑的会话（CC :78）
        val runtime = hub().create(paths.guestOfHost(args.cwd), profile.ref, args.mode, args.effort, name = args.name, headless = args.prompt != null)
        if (args.worktree) {
            val result = runtime.enterWorktree(args.worktreeName)
            if (result.isError) throw UsageException(result.content.filterIsInstance<dev.min.agent.protocol.TextPart>().joinToString("") { it.text }, exitCode = 1)
        }
        return runtime
    }

    /**
     * `--resume <x>` 的 x：长得像会话 id 且日志存在就当 id；否则按名字找当前目录下最近的根会话
     * （CC 的 --resume <name> 同一条路）。两者都不是就报错退出
     */
    private suspend fun resolveResume(x: String): SessionId {
        if (SESSION_ID_SHAPE.matches(x)) {
            val asId = SessionId(x)
            if (store.peek(asId) != null) return asId
        }
        val wanted = dev.min.agent.kernel.normalizeSessionName(x) ?: x
        return hub().findSessionByName(paths.guestOfHost(args.cwd), wanted)
            ?: throw UsageException("「$x」既不是会话 id，也不是当前目录里命名过的会话（名字用 --name 设、/rename 改）。")
    }

    /** 交互模式 /branch：从当前会话分出一个新根会话（忙 / 找不到时抛 [SessionBranchRefused]，终端打印原因） */
    suspend fun branchSession(runtime: SessionRuntime): SessionRuntime = hub().branch(runtime.sessionId)

    /**
     * 交互模式 /fork（CC：把当前会话复制成一个新的后台会话，自己留在这里继续）。这里的「后台」是本进程里的另一个根会话：
     * 复制出来、不切过去、不跑回合。要去看它就 /resume 它。与 /branch 的区别只在切不切过去。
     */
    suspend fun forkCopy(runtime: SessionRuntime): SessionRuntime = hub().branch(runtime.sessionId)

    /** 交互模式 /clear：另起一个空会话（同目录、同模型与模式；--name 的名字不带过来，CC 同款）。 */
    suspend fun newSession(name: String?): SessionRuntime =
        hub().create(paths.guestOfHost(args.cwd), profile.ref, args.mode, args.effort, name = name, headless = false)

    /**
     * 交互模式 /resume <id 或名字>：打开另一个会话并切过去（用户主动接着用，跑 SessionStart(resume)）。
     * 找不到时抛 [UsageException]，终端打印原因。
     */
    suspend fun resumeInSession(x: String): SessionRuntime {
        val id = resolveResume(x)
        return hub().open(id, resumedByUser = true) ?: throw UsageException("找不到会话 ${id.value}")
    }

    /**
     * `-p` 不能接着一个已经结束（模型用 EndConversation 结束过）的会话：报错退出 1，脚本不会把它当成成功（CC tools-reference）。
     * 交互打开同一个会话不受影响，锁住的是输入。
     */
    private fun refuseEndedForPrint(runtime: SessionRuntime) {
        if (args.prompt != null && runtime.state.value.ended) throw UsageException(CONVERSATION_ENDED_NOTICE, exitCode = 1)
    }

    /**
     * --continue：当前目录最近一次根会话。按日志头两条事件筛（cwd 相同 + 第二条不是 subagent_origin），
     * 并跳过 `-p` / 无头跑的（SessionCreated.headless；CC :78 说 --continue 不接 -p / SDK 会话——
     * 跑批的会话不该被人一次回车接着聊）
     */
    private suspend fun latestRootSession(): SessionId? {
        val cwd = paths.guestOfHost(args.cwd)
        for (header in store.list().asReversed()) {
            val log = store.peek(header.sessionId) ?: continue
            val head = log.read().take(2).toList()
            val created = head.firstOrNull()?.event as? dev.min.agent.spi.SessionCreated ?: continue
            if (created.cwd != cwd) continue
            if (created.headless) continue
            if (head.getOrNull(1)?.event is dev.min.agent.spi.SubagentOrigin) continue
            return header.sessionId
        }
        return null
    }

    /**
     * CLI 退出（hooks设计 §7）：SessionEnd（reason = prompt_input_exit，CC 对「用户在输入提示前退出」的取值）
     * 在根会话卸下之前跑；停不下来的回合先停（shutdown），日志保留。
     * 卸下即登记完检查点引用，随后在 IO 上清理快照仓库；返回的 Job 由 CLI 最外层等完再取消进程 scope
     */
    suspend fun exitSession(runtime: SessionRuntime): Job {
        hub().shutdown(runtime.sessionId, SessionEndReasons.PROMPT_INPUT_EXIT)
        // 进程马上要退出：MCP 服务器不能等「无人使用两分钟」，现在就按礼貌顺序关掉
        hub().closeMcpConnections()
        return scope.launch(Dispatchers.IO) {
            catchingNonCancellation { snapshots.gc(cleanupPeriodDays = 30) }
                .onFailure { System.err.println("检查点仓库清理失败，将在下次关闭会话时重试。") }
        }
    }

    suspend fun cleanupWorktree(runtime: SessionRuntime, input: java.io.BufferedReader, out: java.io.PrintStream) {
        val binding = runtime.state.value.worktree ?: return
        if (args.prompt != null || binding.pinned) return
        hub().stopTree(runtime.sessionId)
        val clean = runtime.worktreeHasWork() == false
        val automatic = clean && runtime.state.value.name == null && binding.owned
        val remove = if (automatic) true else {
            out.println("Worktree ${binding.path} (${binding.branch ?: "hook-managed"})。输入 remove 删除，或回车 keep 保留：")
            input.readLine()?.trim() == "remove"
        }
        val result = runtime.exitWorktree(remove)
        if (result.isError) {
            result.content.filterIsInstance<dev.min.agent.protocol.TextPart>().forEach { out.println(it.text) }
            out.println("已保留 worktree。")
        }
    }

    private fun platformDescription(): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        return if (os.startsWith("Windows")) "$os (commands run in Git Bash; drives are /c, /d, …)" else os
    }

    private companion object {
        val HUB_LIMITS = HubLimits(maxDepth = 3, maxConcurrentChildRequests = 8)

        /** 会话 id 的形状（与 FileSessionStore 的 SAFE_ID 同一套）：先判形状再查存在，免得名字里的怪字符被当路径拼 */
        val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
