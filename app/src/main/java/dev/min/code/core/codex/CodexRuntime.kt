package dev.min.code.core.codex

import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext

/** Installs and launches Codex beside Claude Code in the shared Ubuntu rootfs. */
class CodexRuntime(
    private val workspaceRepository: WorkspaceRepository,
    private val proot: ProotShellRunner,
    private val settingsStore: SettingsStore,
) {
    data class Status(
        val installed: Boolean = false,
        val version: String? = null,
        val authenticated: Boolean = false,
        val loginStatus: String? = null,
        val busy: Boolean = false,
        val detail: String? = null,
        val error: String? = null,
    )

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    @Volatile private var launchProfile: CodexProfile? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val profile = settingsStore.current().activeCodexProfile
        launchProfile = profile
        val linux = workspaceRepository.linuxDir()
        val installed = File(linux, CODEX_BIN.removePrefix("/")).isFile
        val version = if (installed) {
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = "$CODEX_BIN --version",
                timeoutMillis = 30_000,
                env = runtimeEnv(profile),
            ).stdout.trim().takeIf { it.isNotBlank() }
        } else null
        // env 必须带上当前 profile：API key 模式下 `login status` 判的就是环境里的
        // OPENAI_API_KEY，不传的话每次都报未登录，界面上永远卡在"先去登录"
        val login = if (installed) {
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = "$CODEX_BIN login status",
                timeoutMillis = 30_000,
                env = runtimeEnv(profile),
            )
        } else null
        _status.value = Status(
            installed = installed,
            version = version,
            authenticated = when (profile?.authMode) {
                CodexAuthMode.CLI, null -> login?.exitCode == 0
                CodexAuthMode.OPENAI_API_KEY, CodexAuthMode.RELAY -> !profile.apiKey.isBlank()
            },
            loginStatus = login?.let { result ->
                result.stdout.ifBlank { result.stderr }.trim().takeIf { it.isNotBlank() }
            },
        )
    }

    /** Persist only non-secret Codex routing metadata in the guest config; keys remain env-only. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        val profile = settingsStore.current().activeCodexProfile
        launchProfile = profile
        val config = File(workspaceRepository.linuxDir(), "root/.codex/config.toml")
        when (profile?.authMode) {
            CodexAuthMode.RELAY -> {
                config.parentFile?.mkdirs()
                val base = profile.baseUrl.trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"")
                config.writeText(
                    "model_provider = \"$RELAY_PROVIDER_ID\"\n\n" +
                        "[model_providers.$RELAY_PROVIDER_ID]\n" +
                        "name = \"Min relay\"\n" +
                        "base_url = \"$base\"\n" +
                        "wire_api = \"responses\"\n" +
                        "env_key = \"OPENAI_API_KEY\"\n",
                )
            }

            // 官方 API 不需要任何配置：`openai` 是 Codex 的**内置 provider**，
            // 写 `[model_providers.openai]` 会被它当成企图覆盖内置项，直接报
            // "contains reserved built-in provider IDs: `openai`" 然后退回默认。
            // 认证只靠环境变量里的 OPENAI_API_KEY，见 runtimeEnv。
            //
            // CLI 模式同样删掉：那份配置归官方登录自己管，留着上一次选的中转
            // 会让「官方登录」偷偷走别人的地址。
            CodexAuthMode.OPENAI_API_KEY, CodexAuthMode.CLI, null -> config.delete()
        }
    }

    suspend fun currentProfile(): CodexProfile? = settingsStore.current().activeCodexProfile

    suspend fun install() = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(busy = true, detail = "Installing @openai/codex…", error = null)
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "mkdir -p /opt/codex && /opt/node/bin/npm install --global --prefix /opt/codex @openai/codex",
            timeoutMillis = 10 * 60_000L,
            env = runtimeEnv(),
        )
        if (result.exitCode != 0 || result.timedOut) {
            _status.value = Status(
                installed = false,
                error = result.stderr.ifBlank { result.stdout }.take(1200),
            )
        } else {
            refresh()
        }
    }

    fun launchAppServer(): Process {
        val workspace = workspaceRepository.workspace.value
        check(File(workspaceRepository.linuxDir(), CODEX_BIN.removePrefix("/")).isFile) {
            "Codex CLI is not installed"
        }
        val profile = launchProfile
        // 中转的路由已经由 prepare() 写进 config.toml 了，不要再从命令行 `-c` 传一遍：
        // 那串带引号的赋值要穿过 sh -c，引号被吃掉之后 Codex 收到的是个裸标识符
        val command = "$CODEX_BIN app-server"
        val env = runtimeEnv(profile)
        return requireNotNull(
            proot.launch(
                WorkspaceShellContext(
                    root = workspace.root,
                    command = command,
                    cwd = "",
                    filesDir = workspaceRepository.filesDir(),
                    linuxDir = workspaceRepository.linuxDir(),
                    tempDir = File(workspaceRepository.workspaceDir(), "tmp"),
                    workingDir = workspaceRepository.filesDir(),
                    timeoutMillis = Long.MAX_VALUE,
                    env = env,
                ),
            ),
        ) { "Unable to launch Codex app-server" }
    }

    private fun runtimeEnv(profile: CodexProfile? = null): Map<String, String> = buildMap {
        val base = profile?.baseUrl?.trimEnd('/')
        val key = profile?.apiKey.orEmpty()
        put("PATH", "/opt/codex/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", "/root")
        if (!base.isNullOrBlank() && profile.authMode != CodexAuthMode.CLI) {
            put("OPENAI_BASE_URL", base)
            put("CODEX_API_KEY", key)
            put("OPENAI_API_KEY", key)
        }
    }

    companion object {
        const val CODEX_BIN = "/opt/codex/bin/codex"

        /**
         * 中转 provider 在 config.toml 里的 id。**不能叫 `openai`** —— 那是 Codex 的
         * 内置 id，用了会被判成覆盖内置项。带前缀的自定义名才安全。
         */
        const val RELAY_PROVIDER_ID = "min_relay"
    }
}
