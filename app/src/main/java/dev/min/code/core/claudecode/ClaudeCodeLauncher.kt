package dev.min.code.core.claudecode

import android.content.Context
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.activeDnsServers
import dev.min.code.core.relay.RelayController
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.SettingsStore
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceShellContext
import java.io.File

/**
 * 把一组启动选项变成一个跑起来的 `claude` 进程：读供应商、备好 rootfs、拼 argv / env、交给 proot。
 *
 * 从 [ClaudeCodeManager] 的 launchCli 原样搬出来，为的是单测能换成假进程 —— 这半段要 Keystore、
 * 工作区和 proot，JVM 里一样都起不来。进程起来之后的事（读写、握手、退出）仍归 Manager。
 */
fun interface ClaudeCodeLauncher {
    /** 起不来就抛，异常文案会原样显示给用户 */
    suspend fun launch(options: ClaudeCodeManager.SessionOptions, sessionId: String): LaunchedCli
}

class LaunchedCli(
    val process: Process,
    /** 这个工作区的 rootfs 目录。会话仓库读 transcript、编辑快照换算路径都靠它 */
    val linuxDir: File,
    /** 启动时生效的那条供应商，见 [ClaudeCodeManager.SessionState.launchedProfileId] */
    val profileId: String,
)

/** 线上用的那一份：在 Claude Code 工作区的 Rootfs 里经 proot 起官方 CLI */
class ProotClaudeCodeLauncher(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val installer: ClaudeCodeInstaller,
    private val networkProbe: NetworkProbe,
    private val relay: RelayController?,
) : ClaudeCodeLauncher {
    private val runner by lazy {
        ProotShellRunner(nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir))
    }

    /**
     * 当前生效的那条供应商，整条取回来。
     *
     * 启动时**只读这一次**：地址、token 和自定义 env 必须来自同一条配置。分成
     * token / baseUrl 两次 `current()` 的话，中间用户正好切了一家，
     * 起来的进程就会拿着 A 家的 key 去敲 B 家的门。
     */
    private suspend fun activeProfile(): ApiProfile? = settingsStore.current().activeProfile

    override suspend fun launch(options: ClaudeCodeManager.SessionOptions, sessionId: String): LaunchedCli {
        val profile = activeProfile()
        check(profile != null && profile.token.isNotBlank()) {
            "未配置 ANTHROPIC_AUTH_TOKEN，请先在设置页填写 token"
        }

        val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Claude Code 工作区不存在")
        val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
        val linuxDir = File(workspaceDir, "linux")

        // 包在、二进制不在（上次那个 100 MB 的平台包没下完）也算"找不到"，
        // 但要把原因说清楚，否则用户只看到一句 ENOENT
        val entry = installer.claudeEntry(linuxDir)
            ?: error(installer.cliProblem(linuxDir))

        val args = claudeLaunchArgs(entry, options, sessionId)

        if (options.skipPermissions || options.permissionMode == ClaudeCodePermissionMode.BYPASS) {
            installer.ensureBypassPermissionsAccepted(linuxDir)
        }

        // 设备 DNS + 短身份卡：会话路径以前只用公共 DNS；别让模型猜 172.x。
        val netSnap = runCatching { networkProbe.snapshot() }.getOrNull()
        runCatching {
            RootfsPatcher().patch(
                linuxDir,
                RootfsPatchOptions(nameservers = context.activeDnsServers()),
            )
        }
        installer.ensureRuntimeDocs(linuxDir, netSnap)

        // 方言不是原生时改写成本地路由地址（要挂起，所以在拼 env 之前先拿好）
        val relayBaseUrl = relay?.claudeBaseUrl(profile)
        val shellContext = WorkspaceShellContext(
            root = workspace.root,
            // 走 bash 的 eval，必须逐个 shell 转义，否则含空格的参数（如模型名）会被拆开
            command = args.joinToString(" ") { ClaudeCodeManager.shellQuote(it) },
            cwd = "",
            filesDir = File(workspaceDir, "files"),
            linuxDir = linuxDir,
            tempDir = File(workspaceDir, "tmp"),
            workingDir = File(workspaceDir, "files"),
            timeoutMillis = 0L, // 长会话不由 runner 管超时
            env = claudeSessionEnv(options, profile, relayBaseUrl, netSnap),
        )

        val proc = runner.launch(shellContext)
            ?: error(runner.checkAvailability(shellContext) ?: "无法启动 proot 进程")
        return LaunchedCli(proc, linuxDir, profile.id)
    }
}
