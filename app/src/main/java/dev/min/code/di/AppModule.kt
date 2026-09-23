package dev.min.code.di

import dev.min.code.AppScope
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.device.AgentDisplaySession
import dev.min.code.core.device.DeviceController
import dev.min.code.core.device.DeviceMcpRegistrar
import dev.min.code.core.device.DeviceMcpServer
import dev.min.code.core.network.NetworkProbe
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.privileged.PrivilegedStarter
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.deviceStorageBindMount
import dev.min.code.core.rootfs.hasDeviceStorageAccess
import dev.min.code.core.service.ClaudeCodeSessionSupervisor
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.ProviderBackup
import dev.min.code.core.settings.ProviderPresetSource
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.files.WorkspaceDetailVM
import dev.min.code.ui.codex.CodexVM
import dev.min.code.ui.session.ClaudeCodeVM
import dev.min.code.ui.providers.ProvidersVM
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
            baseDir = File(context.filesDir, "workspaces"),
            // 参数类型是接口 WorkspaceShellRunner，而 single 只按 ProotShellRunner 登记：
            // 不写明类型，Koin 会去找接口的定义，找不到就在 App 启动时崩（编译期看不出来）
            shellRunner = get<ProotShellRunner>(),
            // 每次起 proot 现算：开关和系统权限都可能在两次会话之间被改掉。
            // 设置还没读到过（冷启动最初的一瞬）时 snapshot 是 null，按"没开"处理 ——
            // 少挂一次的代价是这次会话看不到 /sdcard，多挂一次的代价是权限被收回后还摆着它
            bindMounts = {
                listOfNotNull(
                    deviceStorageBindMount(
                        enabled = settings.snapshot?.shareDeviceStorage == true,
                        granted = hasDeviceStorageAccess(context),
                    )
                )
            },
        )
    }
    single { RootfsInstaller(get()) }
    single { WorkspaceRepository(get(), get()) }
    single {
        CodexRuntime(
            workspaceRepository = get(),
            proot = get(),
            settingsStore = get(),
            relay = get(),
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
    single { PrivilegedClient(get()) }
    single { PrivilegedStarter(get(), get()) }
    single { AgentDisplaySession(get()) }
    // DeviceController 要能问到虚拟屏会话是否活跃，所以放在 AgentDisplaySession 之后
    single { DeviceController(get(), get()) }
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
    single { ProviderSync(get(), get(), get()) }
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
            localServices = get(),
            // 退还兜底要落进同一份草稿盘（上面的 single），另开一份就把草稿写劈叉了
            drafts = get(),
            relay = get(),
        )
    }
    single { ClaudeCodeSessionRegistry(get(), factory = { get() }) }

    // 保活与后台通知。createdAtStart：它订阅的是注册表，注册表可能在页面之外先动起来
    single(createdAtStart = true) {
        ClaudeCodeSessionSupervisor(
            context = get(),
            appScope = get(),
            registry = get(),
            localServices = get(),
            codex = get(),
        )
    }

    single { WorkspaceTerminalSessionManager(get(), get(), get(), get()) }

    viewModelOf(::ClaudeCodeVM)
    viewModel {
        CodexVM(
            runtime = get(),
            manager = get(),
            settingsStore = get(),
            // 上面 named("codex") 那份；不带 qualifier 会撞上 Claude 的那份
            metaStore = get(named("codex")),
            draftStore = get(),
            workspaceRepository = get(),
        )
    }
    viewModelOf(::SetupVM)
    viewModelOf(::SettingsVM)
    viewModelOf(::ProvidersVM)
    viewModel { WorkspaceDetailVM(context = get(), id = it.get<String>(), repository = get(), terminalSessionManager = get()) }
}
