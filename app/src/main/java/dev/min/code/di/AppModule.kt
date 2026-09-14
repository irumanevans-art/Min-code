package dev.min.code.di

import dev.min.code.AppScope
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.service.ClaudeCodeSessionSupervisor
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.files.WorkspaceDetailVM
import dev.min.code.ui.session.ClaudeCodeVM
import dev.min.code.ui.settings.SettingsVM
import dev.min.code.ui.setup.SetupVM
import dev.min.code.ui.terminal.WorkspaceTerminalSessionManager
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module
import java.io.File

val appModule = module {
    single { AppScope() }
    single { SettingsStore(get()) }

    single {
        val context: android.content.Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)),
        )
    }
    single { RootfsInstaller(get()) }
    single { WorkspaceRepository(get(), get()) }

    single { NetworkProbe(get()) }
    single {
        val context: android.content.Context = get()
        LocalServiceRegistry(
            context = context,
            workspaceRepository = get(),
            networkProbe = get(),
            proot = ProotShellRunner(nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)),
        )
    }

    single { ClaudeCodeInstaller(get(), get()) }
    // Rootfs 里那几个配置文件的读写层：/mcp、/agents、/memory、/config、/permissions
    single { ClaudeCodeConfigStore(get(), get()) }
    // 输入框草稿：按会话落盘，杀进程再进还在。必须是 single，VM 和注册表切会话都读同一份
    single { ComposerDraftStore(get<android.content.Context>()) }
    // 置顶 / 分类 / 人手改过的标题。CLI transcript 里没有这些
    single { ClaudeCodeSessionMetaStore(get<android.content.Context>()) }
    // 单日花费台账必须是 single：它要横跨所有会话累加，每个会话一份就退化成会话内计数
    single { ClaudeCodeCostLedger(get()) }
    // 多会话：manager 是 factory，每个会话一个实例（各自一个 CLI 进程），由 registry 持有
    factory { ClaudeCodeManager(get(), get(), get(), get(), get(), get()) }
    single { ClaudeCodeSessionRegistry(factory = { get() }) }

    // 保活与后台通知。createdAtStart：它订阅的是注册表，注册表可能在页面之外先动起来
    single(createdAtStart = true) {
        ClaudeCodeSessionSupervisor(
            context = get(),
            appScope = get(),
            registry = get(),
            localServices = get(),
        )
    }

    single { WorkspaceTerminalSessionManager(get(), get()) }

    viewModelOf(::ClaudeCodeVM)
    viewModelOf(::SetupVM)
    viewModelOf(::SettingsVM)
    viewModel { WorkspaceDetailVM(id = it.get<String>(), repository = get(), terminalSessionManager = get()) }
}
