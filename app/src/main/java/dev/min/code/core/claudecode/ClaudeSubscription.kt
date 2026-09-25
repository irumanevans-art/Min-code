package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.relay.RelayController
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.ClaudeAuthMode
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * # Claude 订阅这条路
 *
 * **Min 不实现 OAuth，不解析、不保存、不转送订阅凭证。** Anthropic 不允许第三方产品提供
 * Claude.ai 登录或代持订阅凭证，所以这里的每一步都只是「让官方 CLI 自己去做」：
 *
 * - 登录：在 rootfs 里起 `claude auth login --claudeai`。CLI 自己在 127.0.0.1 的随机端口上起回调，
 *   把带 `redirect_uri=http://localhost:<port>/callback` 的授权 URL 交给 `$BROWSER`。
 *   我们的 `$BROWSER` 是一行 shell，只把这个 URL 写进一个文件；Min 读到它、校验是 claude.com 的授权页，
 *   交给**外部浏览器**（Custom Tab / 系统浏览器，不是预览槽的 WebView —— 那是 Min 进程里的浏览器，
 *   登录态和授权 code 都会落在 Min 手里）。浏览器跳回 localhost 时直接落到 proot 里的 CLI
 *   （proot 不隔离网络命名空间，实测 Chrome 访问得到）。token 由 CLI 自己写进
 *   `/root/.claude/.credentials.json`，也只由它自己读。
 * - CLI 的输出一律丢进 /dev/null：Min 这边能看到的只有那个公开的授权 URL（里面只有 state 和
 *   code_challenge，PKCE 的 verifier 在 CLI 手里）和两个退出码。
 * - 判定：`claude auth status` 的退出码（未登录 = 1，实测 2.1.280）。不读它的 JSON。
 * - 登出：`claude auth logout`（顶层没有 `claude logout`，那会被当成一句 prompt）。
 *
 * **为什么不用 `claude setup-token`**：它把一年期的 token 打印到终端让人复制。Min 要用它就得读这段输出、
 * 存下来、再当 `CLAUDE_CODE_OAUTH_TOKEN` 注入——那正是代持订阅凭证。同理 `CLAUDE_CODE_OAUTH_TOKEN`
 * 在 [dev.min.code.core.settings.RESERVED_ENV_KEYS] 里，供应商的自定义 env 塞不进来。
 *
 * **订阅期间一个认证变量都不注入**，连 `ANTHROPIC_BASE_URL` 也不给：实测只要它指着中转或机内路由，
 * CLI 就会把订阅的 OAuth bearer（带 `oauth-2025-04-20` beta 头）发过去。空串也不行 ——
 * 非空的 `ANTHROPIC_API_KEY` / `AUTH_TOKEN` 会压过订阅登录，所以是「不注入」，不是「注入空串」。
 * 起会话、终端、`!` 命令、托管服务、settings.json 投影都读 [dev.min.code.core.settings.AppSettings.claudeProfile]，
 * 订阅时它是 null。
 */

/** 走订阅时会话记下的「连接 id」。换路时空闲会话据它判断要不要重起，和供应商 id 同一个位置 */
const val SUBSCRIPTION_CONNECTION_ID = "claude-subscription"

/** 登录这件事走到哪了。界面照着它画；[Browser] 时由界面去开浏览器 */
sealed interface ClaudeLoginState {
    data object Idle : ClaudeLoginState

    /** 正在起 CLI、等它把授权 URL 交出来 */
    data object Starting : ClaudeLoginState

    /** 授权页已就绪，等用户在浏览器里点完。[url] 是公开的授权地址，不是凭证 */
    data class Browser(val url: String) : ClaudeLoginState

    /** 浏览器回来了、CLI 退出了，正在用 `auth status` 确认 */
    data object Verifying : ClaudeLoginState

    data object Done : ClaudeLoginState

    data class Failed(val reason: ClaudeLoginFailure) : ClaudeLoginState
}

enum class ClaudeLoginFailure {
    /** rootfs 里没有能跑的 CLI */
    CLI_MISSING,

    /** CLI 退出了也没交出授权 URL（版本变了、起不来） */
    NO_URL,

    /** 等浏览器回来等超时了 */
    TIMEOUT,

    /** 流程走完了，`auth status` 仍说没登录（在浏览器里取消了、换了没有订阅的账号） */
    NOT_LOGGED_IN,
}

/**
 * 只放行 Anthropic 自家的授权页。这个 URL 来自 rootfs 里的一个文件，而 rootfs 是 agent 能写的地方 ——
 * 不校验的话，这条「替 CLI 开浏览器」的桥就能被拿去开任意网页。
 */
internal fun isClaudeAuthorizeUrl(raw: String): Boolean {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    // userInfo 在场（https://claude.com@evil.example/…）时 host 已经是后者，下面照样拦得住；这里再明确拒一次
    if (uri.rawUserInfo != null) return false
    val host = uri.host?.lowercase() ?: return false
    if (host !in CLAUDE_AUTH_HOSTS) return false
    return uri.path.orEmpty().contains("/oauth/authorize")
}

/**
 * `claude auth logout` 算不算成了。[exitCode] 为 null = rootfs 里没有能跑的 CLI。
 * 只有 0 算：超时被强杀记成 -1（见 [ClaudeSubscription] 的 EXIT_TIMEOUT），也是没成。
 */
internal fun cliLogoutSucceeded(exitCode: Int?): Boolean = exitCode == 0

/** 实测 2.1.280 给的是 claude.com/cai/oauth/authorize；另几家是 Anthropic 用过 / 文档里出现过的授权域 */
private val CLAUDE_AUTH_HOSTS = setOf("claude.com", "claude.ai", "platform.claude.com", "console.anthropic.com")

/**
 * 订阅登录 / 登出 / 换路。单例：登录期间要挂住前台服务（[active]），App 退到浏览器后
 * proot 里等回调的 CLI 不能被冻住。
 */
class ClaudeSubscription(
    private val workspaceRepository: WorkspaceRepository,
    private val installer: ClaudeCodeInstaller,
    private val settingsStore: SettingsStore,
    private val providerSync: ProviderSync,
    private val relay: RelayController,
    private val registry: ClaudeCodeSessionRegistry,
    private val scope: CoroutineScope,
) {
    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    private val _login = MutableStateFlow<ClaudeLoginState>(ClaudeLoginState.Idle)
    val login: StateFlow<ClaudeLoginState> = _login.asStateFlow()

    /** 正在登录：前台服务据此保活 */
    val active: StateFlow<Boolean> = _login
        .map { it is ClaudeLoginState.Starting || it is ClaudeLoginState.Browser || it is ClaudeLoginState.Verifying }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var job: Job? = null

    private val _staleSessions = MutableStateFlow<List<String>>(emptyList())

    /**
     * 最近一次换路（登录成功 / 切回供应商 / 登出）之后，因为正忙没重起、仍在用旧连接的会话 key。
     * 空闲的已经自动重起了，见 [ClaudeCodeSessionRegistry.reloadConnection]。
     *
     * 做成流而不是 [useProvider] 的返回值：登录成功那次换路发生在 [scope] 里，
     * 登录面板那时可能已经关了；设置页和登录面板读的是同一份，谁先处理掉，另一处也跟着收起。
     * 界面照供应商页那样打提示 + 给一颗手动重启（`ui/providers/StaleSessionsNotice.kt`）。
     */
    val staleSessions: StateFlow<List<String>> = _staleSessions.asStateFlow()

    /**
     * 开始登录。已经登录过（凭证文件还在、没过期）就不开浏览器，直接换到订阅这条路。
     * 重复调用时沿用正在进行的那一次。
     */
    fun startLogin() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _login.value = ClaudeLoginState.Starting
            val linux = workspaceRepository.linuxDir()
            val entry = installer.claudeEntry(linux)
            if (entry == null) {
                _login.value = ClaudeLoginState.Failed(ClaudeLoginFailure.CLI_MISSING)
                return@launch
            }
            val claude = entry.joinToString(" ") { ClaudeCodeManager.shellQuote(it) }
            if (authStatusOk(claude)) {
                useSubscription()
                _login.value = ClaudeLoginState.Done
                return@launch
            }

            val dir = File(linux, BRIDGE_DIR_REL).apply { mkdirs() }
            val urlFile = File(dir, URL_FILE).apply { delete() }
            File(dir, CANCEL_FILE).delete()
            File(dir, BROWSER_SCRIPT).apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$1\" > $BRIDGE_DIR_GUEST/$URL_FILE\n")
                setExecutable(true, false)
            }

            // 等 URL 和等 CLI 退出同时进行：URL 一出现就交给界面，CLI 等浏览器回调时一直挂着
            val watcher = launch {
                val deadline = System.currentTimeMillis() + URL_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    val url = urlFile.takeIf { it.isFile }?.readText()?.trim()
                    if (!url.isNullOrEmpty()) {
                        if (isClaudeAuthorizeUrl(url)) _login.value = ClaudeLoginState.Browser(url)
                        else Log.w(TAG, "ignored a non-Anthropic URL handed to BROWSER")
                        return@launch
                    }
                    delay(URL_POLL_MS)
                }
            }
            val result = workspaceRepository.executeCommand(
                id = workspaceId,
                command = loginScript(claude),
                timeoutMillis = LOGIN_TIMEOUT_MS,
                env = cleanEnv() + ("BROWSER" to "$BRIDGE_DIR_GUEST/$BROWSER_SCRIPT"),
            )
            watcher.cancel()
            urlFile.delete()
            val sawUrl = _login.value is ClaudeLoginState.Browser
            if (result.exitCode == EXIT_CANCELLED) {
                _login.value = ClaudeLoginState.Idle
                return@launch
            }
            _login.value = ClaudeLoginState.Verifying
            _login.value = when {
                authStatusOk(claude) -> {
                    useSubscription()
                    ClaudeLoginState.Done
                }
                !sawUrl -> ClaudeLoginState.Failed(ClaudeLoginFailure.NO_URL)
                result.exitCode == EXIT_TIMEOUT -> ClaudeLoginState.Failed(ClaudeLoginFailure.TIMEOUT)
                else -> ClaudeLoginState.Failed(ClaudeLoginFailure.NOT_LOGGED_IN)
            }
        }
    }

    /** 取消登录：留个记号让脚本自己结束 CLI（进程是 proot 里的，这边拿不到它的句柄） */
    fun cancelLogin() {
        if (job?.isActive != true) {
            _login.value = ClaudeLoginState.Idle
            return
        }
        runCatching { File(File(workspaceRepository.linuxDir(), BRIDGE_DIR_REL), CANCEL_FILE).createNewFile() }
    }

    /** 看完结果（成功或失败）之后回到空闲，下次进来是干净的 */
    fun acknowledge() {
        if (job?.isActive != true) _login.value = ClaudeLoginState.Idle
    }

    /**
     * 退出订阅登录：调 CLI 自己的 `auth logout`（凭证文件由它删），然后换回供应商这条路。
     * 供应商表一个字不动。
     *
     * @return CLI 的登出成没成：只看退出码（输出照旧进 /dev/null）。CLI 不在也算没成 ——
     *   凭证文件是它写的，它不在就没人能按它的规矩删。
     *
     * **登出没成也照样切回供应商。** 用户点「退出」表达的是「别再用订阅了」，这一半不靠 CLI 也做得到：
     * 切回之后 Min 起的会话注入供应商的 key，非空的 key 压过订阅登录（见文件头）；表里没有能用的供应商时
     * 会话根本起不来、要先连一家。反过来「登出没成就留在订阅上」等于把人扣在他明确不想要的那条路上，
     * 而他能做的只是再点一次、再失败一次。
     * 代价是凭证文件可能还在 rootfs 里：下次选订阅会不开浏览器直接登入，用户自己在终端里跑的 `claude`
     * （没注入 key 时）也可能照旧用它。这些由界面如实告诉他，不由这里替他决定。
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val entry = installer.claudeEntry(workspaceRepository.linuxDir())
        val exitCode = entry?.let {
            val claude = it.joinToString(" ") { part -> ClaudeCodeManager.shellQuote(part) }
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = "$UNSET_AUTH; $claude auth logout >/dev/null 2>&1",
                timeoutMillis = STATUS_TIMEOUT_MS,
                env = cleanEnv(),
            ).exitCode
        }
        val cliSignedOut = cliLogoutSucceeded(exitCode)
        if (!cliSignedOut) Log.w(TAG, "claude auth logout did not succeed (cli missing or non-zero exit)")
        useProvider()
        cliSignedOut
    }

    /**
     * 换回供应商这条路（不登出：订阅的凭证文件留着，下次切回来不用再登）。
     * 顺序照供应商页切换那条：先落盘，再投影，再路由，最后才动进程 —— 重起的进程在 launch 里现读连接。
     */
    suspend fun useProvider() = switchTo(ClaudeAuthMode.PROVIDER)

    private suspend fun useSubscription() = switchTo(ClaudeAuthMode.SUBSCRIPTION)

    private suspend fun switchTo(mode: ClaudeAuthMode) {
        settingsStore.setClaudeAuth(mode)
        providerSync.apply()
        relay.reconcile()
        _staleSessions.value = registry.reloadConnection()
    }

    /** 用户在提示上点了「重启」：连正忙的一起重起（那一轮会断，这是他明确选的） */
    fun restartStaleSessions() {
        registry.reloadConnection(includeBusy = true)
        _staleSessions.value = emptyList()
    }

    fun dismissStaleSessions() {
        _staleSessions.value = emptyList()
    }

    private suspend fun authStatusOk(claude: String): Boolean = workspaceRepository.executeCommand(
        id = workspaceId,
        command = "$UNSET_AUTH; $claude auth status >/dev/null 2>&1",
        timeoutMillis = STATUS_TIMEOUT_MS,
        env = cleanEnv(),
    ).exitCode == 0

    /**
     * 后台起 `auth login`，每秒看一眼取消记号。stdin 接 /dev/null：CLI 读到 EOF 也不退，一直等回调（实测）。
     * 输出进 /dev/null：Min 不读 CLI 在登录过程里说的任何话。
     */
    private fun loginScript(claude: String): String = """
        $UNSET_AUTH
        $claude auth login --claudeai >/dev/null 2>&1 </dev/null &
        pid=${'$'}!
        while kill -0 ${'$'}pid 2>/dev/null; do
          if [ -e $BRIDGE_DIR_GUEST/$CANCEL_FILE ]; then
            kill ${'$'}pid 2>/dev/null; wait ${'$'}pid 2>/dev/null
            rm -f $BRIDGE_DIR_GUEST/$CANCEL_FILE
            exit $EXIT_CANCELLED
          fi
          sleep 1
        done
        wait ${'$'}pid
    """.trimIndent()

    /** 只有 node 的 PATH 和几条非认证开关。**四个认证变量、BASE_URL 一个都不在** */
    private fun cleanEnv(): Map<String, String> = ClaudeCodeInstaller.nodeEnv() + mapOf(
        "IS_SANDBOX" to "1",
        "DISABLE_AUTOUPDATER" to "1",
        "DISABLE_TELEMETRY" to "1",
        "DISABLE_ERROR_REPORTING" to "1",
    )

    private companion object {
        const val TAG = "ClaudeSubscription"

        /** 放 BROWSER 小脚本和 URL 记号的目录。rootfs 里的 /root/.min，宿主侧是 linuxDir 下同一路径 */
        const val BRIDGE_DIR_REL = "root/.min"
        const val BRIDGE_DIR_GUEST = "/root/.min"
        const val BROWSER_SCRIPT = "claude-login-browser"
        const val URL_FILE = "claude-login.url"
        const val CANCEL_FILE = "claude-login.cancel"

        /**
         * 兜底：rootfs 里用户自己的 profile 可能 export 了这些。执行命令走的是 `bash -l`，会读到它们；
         * 在场的话 `auth status` 会按 key 判成「已登录」，订阅就被它顶掉了。CI 在场时 CLI 可能不去开浏览器
         */
        const val UNSET_AUTH =
            "unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN ANTHROPIC_BASE_URL CLAUDE_CODE_OAUTH_TOKEN CI"

        /** CLI 起来到交出 URL：冷启动的 proot + 二进制，模拟器上几秒；给足 */
        const val URL_WAIT_MS = 60_000L
        const val URL_POLL_MS = 300L

        /** 人在浏览器里登录的上限。超了就结束 CLI，回调端口随之关掉 */
        const val LOGIN_TIMEOUT_MS = 10 * 60_000L
        const val STATUS_TIMEOUT_MS = 60_000L

        const val EXIT_CANCELLED = 130

        /** 超时被 readResult 强杀时没有退出值，它记成 -1 */
        const val EXIT_TIMEOUT = -1
    }
}
