package dev.min.code.core.codex

import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.sanitizedCodexEnv
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
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
        ensureCaBundle(workspaceRepository.linuxDir())
        val config = File(workspaceRepository.linuxDir(), "root/.codex/config.toml")
        // 渲染与「该不该有这个文件」的判断都在 CodexConfigToml 里，纯函数、可单测。
        // null = 官方登录自己管那份配置，留着上一次选的中转会让它偷偷走别人的地址
        val toml = renderCodexConfigToml(profile)
        if (toml == null) {
            config.delete()
        } else {
            config.parentFile?.mkdirs()
            config.writeText(toml)
        }
    }

    /**
     * 现成的 config.toml 原文，给「从 Rootfs 导入现有配置」用。文件不在就返回 null。
     *
     * 这是 `ProviderSync` 那条「从不反向读回」规矩的例外之一：一次显式的、用户点了
     * 按钮的搬运，读完就变成普通条目，此后不再看这个文件。
     */
    suspend fun readConfigToml(): String? = withContext(Dispatchers.IO) {
        File(workspaceRepository.linuxDir(), "root/.codex/config.toml")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
    }

    suspend fun currentProfile(): CodexProfile? = settingsStore.current().activeCodexProfile

    /**
     * Codex 在 guest 里的家目录。会话记录就落在它下面的 `sessions/`，
     * 历史的事实来源是这些文件，见 [listCodexSessions]。
     */
    fun codexHome(): File = File(workspaceRepository.linuxDir(), "root/.codex")

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

    /**
     * 把 Android 的根证书导出成 rootfs 里的 CA bundle。
     *
     * 这个 Ubuntu rootfs 是最小集，`/etc/ssl/certs` 压根不存在。Claude Code 感觉不到，
     * 因为 node 把根证书编进了自己的二进制；Codex 是 Rust 写的，走系统信任库，
     * 于是每一个请求都死在 `invalid peer certificate: UnknownIssuer`，连 TLS 握手
     * 都过不去 —— 界面上只看到一轮任务毫无动静地失败。
     *
     * 不走 `apt-get install ca-certificates`：那要联网、要先有可用的源，在 proot 里还慢，
     * 而且装证书这件事本身不该依赖网络。Android 自己的信任库就在手边，导出来即可，
     * 还能跟着系统更新走。
     */
    private fun ensureCaBundle(linuxDir: File) {
        val target = File(linuxDir, CA_BUNDLE.removePrefix("/"))
        if (target.isFile && target.length() > 0) return
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            // MIME 编码器按 64 列折行，正好是 PEM 的样子
            val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            val pem = buildString {
                for (alias in store.aliases()) {
                    val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                    append("-----BEGIN CERTIFICATE-----\n")
                    append(encoder.encodeToString(cert.encoded))
                    append("\n-----END CERTIFICATE-----\n")
                }
            }
            if (pem.isBlank()) return
            target.parentFile?.mkdirs()
            target.writeText(pem)
        }.onFailure { Log.w(TAG, "导出 CA bundle 失败，Codex 大概率连不上网", it) }
    }

    private fun runtimeEnv(profile: CodexProfile? = null): Map<String, String> = buildMap {
        val base = profile?.baseUrl?.trimEnd('/')
        val key = profile?.apiKey.orEmpty()
        put("PATH", "/opt/codex/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", "/root")
        // 指到上面导出的那份；两个变量都给，OpenSSL 系和 rustls 系各认一个
        put("SSL_CERT_FILE", CA_BUNDLE)
        put("SSL_CERT_DIR", CA_BUNDLE.substringBeforeLast('/'))
        // 这条配置自己带的环境变量。放在专有键之前：底下那三个是事实来源自己的位置，
        // 不许被 env 表覆盖（同 ClaudeCodeManager 那边的 RESERVED_ENV_KEYS）
        if (profile != null) putAll(sanitizedCodexEnv(profile))
        if (!base.isNullOrBlank() && profile.authMode != CodexAuthMode.CLI) {
            put("OPENAI_BASE_URL", base)
            put("CODEX_API_KEY", key)
            put("OPENAI_API_KEY", key)
        }
    }

    companion object {
        private const val TAG = "CodexRuntime"

        const val CODEX_BIN = "/opt/codex/bin/codex"

        /** guest 里那份根证书，见 [ensureCaBundle] */
        const val CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"

        /** 见 `CodexConfigToml.kt`：provider id 与官方地址都收在那儿，渲染和回读共用 */
        const val OPENAI_BASE_URL = CODEX_OPENAI_BASE_URL
    }
}
