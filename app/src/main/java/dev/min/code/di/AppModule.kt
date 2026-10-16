package dev.min.code.di

import dev.min.code.AppScope
import dev.min.code.BuildConfig
import dev.min.code.core.engine.AgentEngine
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.claude.ClaudeCodeEngine
import dev.min.code.core.engine.codex.CodexEngine
import dev.min.code.core.engine.min.MinAgentEngine
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import dev.min.code.core.claudecode.readShellOutput
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.dsl.bind
import dev.min.code.core.browser.GuestOpenInbox
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ClaudeSubscription
import dev.min.code.core.device.AgentDisplaySession
import dev.min.code.core.device.DeviceController
import dev.min.code.core.device.DeviceMcpRegistrar
import dev.min.code.core.device.DeviceMcpServer
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.rootfs.workspacesBaseDir
import dev.min.code.privileged.MinAdbManager
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.privileged.PrivilegedStarter
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.currentBindMounts
import dev.min.code.core.service.SessionSupervisor
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.ProviderBackup
import dev.min.code.core.settings.ProviderPresetSource
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.files.WorkspaceDetailVM
import dev.min.code.ui.session.ClaudeConfigVM
import dev.min.code.ui.session.MaintenanceVM
import dev.min.code.ui.session.SessionListVM
import dev.min.code.ui.session.SessionVM
import dev.min.code.ui.session.WorkspaceToolsVM
import dev.min.code.ui.providers.ProvidersVM
import dev.min.code.ui.settings.CompanionVM
import dev.min.code.ui.settings.SettingsVM
import dev.min.code.ui.setup.SetupVM
import dev.min.code.ui.terminal.WorkspaceTerminalSessionManager
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

val appModule = module {
    single { AppScope() }
    // 前后台与离开时长：自动会话回顾、进后台关闲置 MCP 连接用
    single { dev.min.code.core.service.AppForeground(get<AppScope>()) }
    single { SettingsStore(get(), get<AppScope>()) }
    // 机内协议路由：只在当前供应商的方言不是原生时才起来，见 RelayController
    single { RelayController(get()) }
    // 无状态（proot 补丁的互斥走 RootfsPatcher 里按 linuxDir 键的全局锁），
    // 会话 / 本地服务 / 终端三条路径共用一个，路径差异全部由 WorkspaceShellContext 表达
    single { ProotShellRunner(nativeLibraryDir = File(get<android.content.Context>().applicationInfo.nativeLibraryDir)) }

    single {
        val context: android.content.Context = get()
        val settings: SettingsStore = get()
        WorkspaceManager(
            baseDir = workspacesBaseDir(context),
            // 参数类型是接口 WorkspaceShellRunner，而 single 只按 ProotShellRunner 登记：
            // 不写明类型，Koin 会去找接口的定义，找不到就在 App 启动时崩（编译期看不出来）
            shellRunner = get<ProotShellRunner>(),
            // 每次起 proot 现算，和四条直构路径共用 currentBindMounts，别在这里再写一份。
            // 设置还没读到过（冷启动最初的一瞬）时 snapshot 是 null，按"没开"处理 ——
            // 少挂一次的代价是这次会话看不到 /sdcard，多挂一次的代价是权限被收回后还摆着它
            bindMounts = { currentBindMounts(context, settings) },
        )
    }
    single { RootfsInstaller(get()) }
    single { WorkspaceRepository(get(), get()) }
    single {
        val context: android.content.Context = get()
        val settings: SettingsStore = get()
        CodexRuntime(
            workspaceRepository = get(),
            proot = get(),
            settingsStore = settings,
            relay = get(),
            // 和会话、终端同一份现算，别在这里再写一份
            bindMounts = { currentBindMounts(context, settings) },
        )
    }
    // 必须是 single：Codex 的 app-server 进程归它管，跟着 ViewModel 走的话
    // 转个屏就被 onCleared 杀掉，正跑着的一轮直接没了
    single {
        val runtime: CodexRuntime = get()
        CodexAppServerManager { runtime.launchAppServer() }
    }

    // 设备操控：server 必须是 single —— 它持有绑定的端口和一次性 token，
    // 每 get 一次新建一个的话，写进 CLI 配置的地址和实际在听的那个就对不上了
    single { PrivilegedClient() }
    // ADB 密钥/证书是设备身份，必须 single 且持久化：配对一次后连接复用同一副密钥
    single { MinAdbManager(get()) }
    single { AgentDisplaySession(get()) }
    single { PrivilegedStarter(get(), get(), get(), get(), get()) }
    // DeviceController 要能问到虚拟屏会话是否活跃，所以放在 AgentDisplaySession 之后
    single { DeviceController(get(), get(), get()) }
    single { DeviceMcpServer(get()) }
    single { DeviceMcpRegistrar(settings = get(), configStore = get(), server = get()) }

    single { NetworkProbe(get()) }
    single {
        val context: android.content.Context = get()
        LocalServiceRegistry(
            context = context,
            workspaceRepository = get(),
            networkProbe = get(),
            settingsStore = get(),
            relay = get(),
            proot = get(),
        )
    }

    single { ClaudeCodeInstaller(get(), get()) }
    // Rootfs 里那几个配置文件的读写层：/mcp、/agents、/memory、/config、/permissions
    single { ClaudeCodeConfigStore(get(), get()) }
    // 托管第一次动 settings.json 之前留的底。只在私有目录里，不进 SAF、不进导出文件
    single { ProviderBackup(File(get<android.content.Context>().filesDir, "provider-backups")) }
    // 供应商配置往 Rootfs 文件上的单向投影。事实来源始终是 DataStore，见类注释
    // 第二个参数的类型是接口 ClaudeSettingsFile：必须写明拿哪个实现，
    // 不写的话 Koin 去找接口的定义，找不到就在启动时崩（见 WorkspaceManager 那条）
    single { ProviderSync(get(), get<ClaudeCodeConfigStore>(), get()) }
    // 预设表（assets 里那份）解析一次缓存住
    single { ProviderPresetSource(get()) }
    // 输入框草稿：按会话落盘，杀进程再进还在。必须是 single，VM 和注册表切会话都读同一份
    single { ComposerDraftStore(get<android.content.Context>()) }
    // 置顶 / 分类 / 人手改过的标题。CLI transcript 里没有这些
    single { ClaudeCodeSessionMetaStore(get<android.content.Context>()) }
    // Codex 的会话元数据与 Claude 同构（置顶 / 改名），threadId 当 key，各存各的文件。
    // 复用同一个 store 类，用 qualifier 区分 —— 两个引擎的元数据互不掺杂
    single(named("codex")) {
        ClaudeCodeSessionMetaStore(java.io.File(get<android.content.Context>().filesDir, "codex-session-meta.json"))
    }
    // Codex 输入框草稿：纯文本一张表，按 threadId 落盘
    single { dev.min.code.core.codex.CodexDraftStore(java.io.File(get<android.content.Context>().filesDir, "codex-drafts.json")) }
    // 单日花费台账必须是 single：它要横跨所有会话累加，每个会话一份就退化成会话内计数
    single { ClaudeCodeCostLedger(get()) }
    // 多会话：manager 是 factory，每个会话一个实例（各自一个 CLI 进程），由 registry 持有
    factory {
        ClaudeCodeManager(
            context = get(),
            workspaceRepository = get(),
            settingsStore = get(),
            installer = get(),
            costLedger = get(),
            networkProbe = get(),
            // 参数类型是接口，Koin 按接口找不到定义 —— 必须写明具体类
            localServices = get<LocalServiceRegistry>(),
            // 退还兜底要落进同一份草稿盘（上面的 single），另开一份就把草稿写劈叉了
            drafts = get(),
            relay = get(),
        )
    }
    single { ClaudeCodeSessionRegistry(get(), factory = { get() }) }
    single(named("min")) {
        ClaudeCodeSessionMetaStore(File(get<android.content.Context>().filesDir, "agent-session-meta.json"))
    }
    single {
        val scope: AppScope = get()
        val settings: SettingsStore = get()
        val workspace: WorkspaceRepository = get()
        val installer: ClaudeCodeInstaller = get()
        val availability = combine(workspace.workspace, settings.settings) { _, s ->
            when {
                !installer.status(CLAUDE_CODE_WORKSPACE_ID.toString()).ready -> EngineAvailability.NeedsInstall
                !s.claudeConnected -> EngineAvailability.NeedsProvider
                else -> EngineAvailability.Ready
            }
        }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, EngineAvailability.NeedsInstall)
        ClaudeCodeEngine(
            registry = get(), scope = scope, availability = availability, metaStore = get(), drafts = get(),
            configStore = get(), services = get<LocalServiceRegistry>().services,
            currentProfile = settings.settings.map { s -> s.activeProfile?.id to s.profiles.associate { it.id to it.label } }
                .stateIn(scope, SharingStarted.Eagerly, null to emptyMap()),
            stopHostedService = { get<LocalServiceRegistry>().stop(it) },
            // 后台 shell 面板读输出尾巴：要工作区目录（host 路径）与进程表的日志，只有组合根拿得到
            readShellOutput = { shell, sessionId ->
                val services = get<LocalServiceRegistry>()
                withContext(Dispatchers.IO) {
                    readShellOutput(shell, workspace.workspaceDir(), sessionId, services::logOf)
                }
            },
        )
    } bind AgentEngine::class
    single {
        val runtime: CodexRuntime = get()
        val workspace: WorkspaceRepository = get()
        val scope: AppScope = get()
        val settings: SettingsStore = get()
        CodexEngine(
            manager = get(), scope = scope,
            // 环境就绪且装了 Codex 就算可用：登录与否不在这里判（要在 proot 里跑命令，不能在注册表每次查可用性时跑），
            // 由开始面板的 Codex 一栏读 CodexRuntime.status 说明，真没登录时握手失败的原因会写在会话的错误提示里。
            // 装完（status 变）要重算，所以把 status 也并进来
            availability = combine(workspace.workspace, runtime.status) { ws, _ ->
                if (ws.shellStatus == WorkspaceShellStatus.READY.name && runtime.isInstalled()) EngineAvailability.Ready else EngineAvailability.NeedsInstall
            }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, EngineAvailability.NeedsInstall),
            prepare = { runtime.prepare() }, codexHome = runtime::codexHome,
            metaStore = get(named("codex")), drafts = get(),
            // 新会话的默认模型 / 强度来自当前连接配置（旧 Codex 页的 threadOptions 做的同一件事）
            defaultOptions = {
                val profile = settings.snapshot?.activeCodexProfile
                CodexAppServerManager.Options(
                    model = profile?.model?.trim()?.takeIf { it.isNotBlank() },
                    effort = profile?.effort?.trim()?.takeIf { it.isNotBlank() },
                )
            },
            searchFiles = { query, limit -> workspace.mentionFiles(query, limit) },
        )
    } bind AgentEngine::class
    single {
        MinAgentEngine(
            context = get(), settings = get(), workspace = get(), runner = get<ProotShellRunner>(),
            appScope = get<AppScope>(), drafts = get(), meta = get(named("min")),
            // 实验开关：release 默认关、debug 默认开，设置里「实验」可改。关着时引擎 Disabled，一切入口不出现
            enabled = get<SettingsStore>().settings.map { it.minAgentEnabled }
                .stateIn(get<AppScope>(), SharingStarted.Eagerly, BuildConfig.DEBUG),
            devices = get<DeviceController>(),
            foreground = get(),
        )
    } bind AgentEngine::class
    single { AgentSessionRegistry(getAll<AgentEngine>(), get<AppScope>()) }
    // 情感特供：记忆存在 App 私有目录；整理器盯着注册表，和保活一样 createdAtStart
    single { dev.min.code.core.companion.CompanionStore(File(get<android.content.Context>().filesDir, "companion")) }
    single(createdAtStart = true) {
        val registry: AgentSessionRegistry = get()
        val workspace: WorkspaceRepository = get()
        dev.min.code.core.companion.CompanionKeeper(
            scope = get<AppScope>(),
            live = registry.live,
            peekItems = { ref -> registry.engine(ref.engine).peek(ref.id)?.state?.value?.items },
            config = get<SettingsStore>().settings.map { it.companion },
            rootfsChanges = workspace.workspace,
            store = get(),
            model = get<MinAgentEngine>().companionModel,
            syncBlock = { block -> dev.min.code.core.companion.syncCompanionBlock(workspace, block) },
        ).also { it.start() }
    }
    // 订阅登录要 single：登录进行中的状态横跨页面，前台服务也盯着它保活
    single {
        ClaudeSubscription(
            workspaceRepository = get(),
            installer = get(),
            settingsStore = get(),
            providerSync = get(),
            relay = get(),
            registry = get(),
            scope = get<AppScope>(),
        )
    }

    // 保活与后台通知。createdAtStart：它订阅的是注册表，注册表可能在页面之外先动起来
    single(createdAtStart = true) {
        SessionSupervisor(
            context = get(),
            appScope = get(),
            registry = get(),
            localServices = get(),
            subscription = get(),
        )
    }

    single { WorkspaceTerminalSessionManager(get(), get(), get(), get()) }

    // rootfs 里有程序要开网页时弹的那张卡的收件箱。createdAtStart：请求可能在任何页面上来，
    // 盯投递目录的人不能等某个页面先打开（见 GuestOpenInbox）
    single(createdAtStart = true) { GuestOpenInbox(get(), get<AppScope>()) }

    // ClaudeCodeVM 按职责拆成了五个，ClaudeCodePage 一次取齐
    viewModelOf(::SessionVM)
    // Min 会话的元数据是 named("min") 那一份（与 Min 引擎读标题的是同一个），viewModelOf 按类型解析取不到它
    viewModel { SessionListVM(get(), get(), get(), get(), get(), get(named("min")), get(named("codex")), get()) }
    viewModelOf(::WorkspaceToolsVM)
    viewModelOf(::MaintenanceVM)
    viewModelOf(::ClaudeConfigVM)
    viewModelOf(::SetupVM)
    viewModelOf(::SettingsVM)
    viewModelOf(::CompanionVM)
    viewModelOf(::ProvidersVM)
    viewModel { WorkspaceDetailVM(context = get(), id = it.get<String>(), repository = get(), terminalSessionManager = get()) }
}
