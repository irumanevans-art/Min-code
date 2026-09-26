package dev.min.code.core.settings

import android.util.Log
import dev.min.code.core.relay.RelayController
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * # 供应商配置的投影层
 *
 * ## 一句话规则
 *
 * **DataStore 里 `activeProfile` / `activeCodexProfile` 那两条是唯一事实来源。**
 * 其它一切——会话进程 env、终端进程 env、本地服务 env、`~/.claude/settings.json` 的
 * 托管区、`~/.codex/config.toml`——都是它的单向投影，每次整段重写，从不反向读回。
 *
 * 唯一的例外是**导入**（`ProviderTransfer.kt`）：那是一次显式的、用户点了按钮的反向
 * 搬运，搬完就变成普通条目，此后不再看文件。
 *
 * ## 为什么不能照搬桌面版 cc-switch
 *
 * 桌面版的模型是「provider 里存一份完整的 settings.json，切换 = 整份写过去」。这里不行：
 * `-p --output-format stream-json` 无头模式下 CLI **读** settings.json 但**不应用**其中的
 * `env` 块（见 `ClaudeCodeManager` 里那条实测注释）。照搬的结果是「文件写对了、会话还在
 * 用旧 key」——最难查的那种不一致。
 *
 * 所以能力全量对齐，事实来源倒过来。托管开关因此只管「要不要动 rootfs 里那两个文件」，
 * 不管「能不能用」：关着的时候会话和终端照常工作。
 *
 * ## 写 settings.json 还有什么意义
 *
 * 三件事：终端里手敲的 `claude`、用户自己写的脚本、以及「我想看看当前配置长什么样」。
 * 界面上必须把这句话说出来，否则用户看到文件里有 `env` 就会以为会话读的是它。
 */
class ProviderSync(
    private val settingsStore: SettingsStore,
    private val configStore: ClaudeSettingsFile,
    private val backup: ProviderBackup,
) {
    /** 一次投影的结果。**失败不抛异常**，原样交给界面去呈现 */
    data class Report(val claude: Outcome = Outcome.Skipped)

    sealed interface Outcome {
        /** 托管关着且本来就没写过，或者 Rootfs 还没装 */
        data object Skipped : Outcome
        data object Written : Outcome

        /** 托管刚关掉，把上次写进去的键清干净了 */
        data object Cleared : Outcome

        /** 文件解析不了 / 写不进去。[reason] 直接进 Notice */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 把当前事实来源投影到 Rootfs 文件上。**幂等**，可以随便重复调。
     *
     * 整个「读账本 → 改文件 → 写账本」序列拿 [ledgerLock]：三次操作各自原子没用，
     * 两次并发 apply 交错的结果就是文件里留着上一家的键而账本记的是这一家的——
     * 下一次同步照错账删键。文件本身的 RMW 由 ClaudeCodeConfigStore 自己的 fileLock
     * 串行化（所有 App 内写方共用）；CLI 自己写这个文件的那条竞态是已知取舍，不在此列。
     *
     * Codex 那一侧由 `CodexRuntime.prepare()` 负责（它还要同时导根证书、刷 launchProfile），
     * 这里只管 Claude 的 settings.json。
     */
    suspend fun apply(): Report = ledgerLock.withLock {
        val settings = settingsStore.current()
        // 走订阅时 claudeProfile 是 null：托管区清空，终端里的 claude 才会用它自己登录的订阅
        val managed = if (settings.manageGuestConfig) {
            settings.claudeProfile
                ?.let { managedSettingsEnv(it, settings.guestConfigIncludesSecrets) }
                .orEmpty()
        } else {
            // 托管关着 = 目标状态是「一个托管键都没有」。下面的规则 1 会把上次写进去的全删掉 ——
            // 不清的话，文件里会一直留着上一家的 token，而界面上托管已经显示「关」
            emptyMap()
        }
        if (managed.isEmpty() && settings.managedEnvKeys.isEmpty()) return@withLock Report(Outcome.Skipped)

        // 托管第一次要动这个文件之前留一份底：managedEnvKeys 还是空的就表示我们一次都没写过，
        // 此刻文件里的东西全是用户自己或 CLI 写的。见 ProviderBackup 的三条边界
        if (settings.managedEnvKeys.isEmpty()) {
            configStore.readSettingsText()?.let { backup.snapshot(BACKUP_TAG_CLAUDE_SETTINGS, it) }
        }

        var written: Set<String> = emptySet()
        val ok = configStore.updateSettings { draft ->
            val (next, keys) = applyManagedEnv(draft, managed, settings.managedEnvKeys)
            draft.clear()
            draft.putAll(next)
            written = keys
        }
        if (!ok) {
            // 什么都没写进去，那就**不能**更新记账 —— 记成新的，下一次就不知道该删哪些旧键了
            return@withLock Report(Outcome.Failed(FAILED_UNREADABLE))
        }
        settingsStore.setManagedEnvKeys(written)
        Report(if (written.isEmpty()) Outcome.Cleared else Outcome.Written)
    }

    companion object {
        /** 文案键由界面翻译；这里只给一个稳定的标识 */
        const val FAILED_UNREADABLE = "settings_unreadable"

        /**
         * 供应商设置没保存成功（DataStore 写盘 / Keystore 加密失败）。与
         * [FAILED_UNREADABLE] 同约定：稳定标识，界面翻译成 providers_save_failed。
         */
        const val FAILED_SAVE = "providers_save_failed"

        /**
         * 串行化「文件 + 记账」的**配对**写。除了 [apply] 之外还有一处动账本：
         * 备份恢复（ProvidersVM.restoreBackup）先整份覆盖文件、清账、再重投影 ——
         * 那三步也必须拿同一把锁，否则夹在中间的一次 apply 会把账配到恢复前的文件上。
         */
        internal val ledgerLock = Mutex()
    }
}

/**
 * 事实来源自己占着的位置。写在 [ApiProfile.env] 里也不算数——**忽略，不是覆盖**。
 *
 * 两处都能写就一定会出现「界面显示 A、进程用 B」，而那种不一致用户自己查不出来：
 * 设置页上白纸黑字是一个地址，请求却发去了另一个。
 */
val RESERVED_ENV_KEYS = setOf(
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_AUTH_TOKEN",
    // 另一种放法（`x-api-key`）。两个键都占住：只占一个的话，选了 Bearer 的人
    // 还能从自定义 env 里塞一个 API_KEY 进去，于是两把钥匙同时在场
    "ANTHROPIC_API_KEY",
    "OPENAI_BASE_URL",
    "OPENAI_API_KEY",
    "CODEX_API_KEY",
    // 订阅的长期 token（`claude setup-token` 印出来的那种）。放进供应商配置 = Min 代持订阅凭证，
    // 那是不允许的；订阅只走 CLI 自己的登录，见 ClaudeSubscription.kt
    "CLAUDE_CODE_OAUTH_TOKEN",
    "PATH",
    "HOME",
    "USER",
    "SHELL",
    "IS_SANDBOX",
)

/**
 * 过一遍用户填的环境变量表。
 *
 * 键名 trim 之后为空、或落在 [RESERVED_ENV_KEYS] 里的整条丢掉；**值不 trim**——
 * 有的自定义头部值末尾的空格是有意义的，替用户擦掉是越界。
 */
internal fun sanitizeEnv(env: Map<String, String>): Map<String, String> =
    env.mapKeys { it.key.trim() }
        .filterKeys { it.isNotEmpty() && it !in RESERVED_ENV_KEYS }

internal fun sanitizedProfileEnv(profile: ApiProfile): Map<String, String> = sanitizeEnv(profile.env)

internal fun sanitizedCodexEnv(profile: CodexProfile): Map<String, String> = sanitizeEnv(profile.env)

/**
 * 托管区该长什么样：写进 `settings.json` 的 `env` 块里、由我们认领的那些键。
 *
 * [includeSecrets] 为 false 时**整个不写** `ANTHROPIC_AUTH_TOKEN` 这个键 ——
 * 不是写空串。空串会让终端里的 `claude` 拿着一个空 key 去请求，换回来一个
 * 莫名其妙的 401；键不存在则它老老实实地说「没配置」。
 */
internal fun managedSettingsEnv(profile: ApiProfile, includeSecrets: Boolean): Map<String, String> =
    buildMap {
        putAll(sanitizedProfileEnv(profile))
        put("ANTHROPIC_BASE_URL", profile.baseUrl)
        // 键名跟着这条供应商的认证方式走（见 ApiProfile.authHeader）。换一种放法时
        // 上一个键会被规则 1 删掉 —— 它在 managedEnvKeys 里，而这次的托管区里没有它
        if (includeSecrets && profile.token.isNotBlank()) put(profile.tokenEnvKey, profile.token)
    }

/**
 * 把托管区合进现有的 `settings.json`。
 *
 * 这个文件同时被 CLI 自己写（`bypassPermissionsModeAccepted`、OAuth 态）、也可能被
 * 用户手写，所以**绝不整文件覆盖**，只动 `env` 下我们自己写过的那些子键。
 * 「写过哪些」记在 DataStore 的 [AppSettings.managedEnvKeys] 上，不往这个文件里塞
 * 自定义标记键——那会污染 CLI 的文件，而且用户手改之后标记会骗人。
 *
 * 三条规则，每条都对应一个真实会出的岔子：
 *
 * 1. [previousKeys] 里、[managed] 里没有的键 → **删掉**。不删的话，从「带
 *    `ANTHROPIC_SMALL_FAST_MODEL` 的 A 家」切到「不带的 B 家」之后，A 家那个键
 *    会留在文件里继续生效。
 * 2. [previousKeys] 之外的键 → 一律不动。那是用户自己或 CLI 写的。
 * 3. 合完 `env` 空了 → 把 `env` 这个键整个删掉，不留一个 `"env": {}` 在那儿。
 *
 * @return 新的 settings 内容，以及这次真正写进去的键（回存 [AppSettings.managedEnvKeys]）
 */
internal fun applyManagedEnv(
    existing: Map<String, JsonElement>,
    managed: Map<String, String>,
    previousKeys: Set<String>,
): Pair<Map<String, JsonElement>, Set<String>> {
    val env = ((existing[KEY_ENV] as? JsonObject)?.toMutableMap() ?: linkedMapOf())
    (previousKeys - managed.keys).forEach { env.remove(it) }
    managed.forEach { (k, v) -> env[k] = JsonPrimitive(v) }

    val next = LinkedHashMap(existing)
    if (env.isEmpty()) next.remove(KEY_ENV) else next[KEY_ENV] = JsonObject(env)
    return next to managed.keys
}

private const val KEY_ENV = "env"

/**
 * 此刻给 rootfs 里的 shell 用的供应商凭据：终端页签、托管服务、输入框里的 `!` 命令共用这一份。
 * 方言不是原生时 `claude` 也走机内路由，和会话进程同一个入口（路由只在 App 活着时通，这几样本来也是）。
 * 解密要走 Keystore，调用方别放在主线程上；解不开时当没有 —— 命令该跑还是跑，只是里面没有 key。
 */
internal suspend fun currentShellCredentialEnv(
    settingsStore: SettingsStore,
    relay: RelayController?,
): Map<String, String> = runCatching {
    val settings = settingsStore.current()
    val override = settings.claudeProfile?.let { relay?.claudeBaseUrl(it) }
    shellCredentialEnv(settings, claudeBaseUrlOverride = override)
}
    .onFailure { Log.w("ProviderSync", "读取供应商凭据失败，shell 里将没有 key", it) }
    .getOrDefault(emptyMap())

/**
 * 终端页签与本地服务进程要带的供应商环境变量。
 *
 * 在这之前终端里**一个凭据都没有**（env 只有一个 PATH）——同一个 App 里，会话页能用的
 * token，换个页签敲 `claude` 就用不了。这就是那个缺口。
 *
 * 两侧的键一起给：终端里敲 `claude` 的人和敲 `codex` 的人是同一个人，分开给只会让
 * 其中一个莫名其妙地不能用。但 Codex 处于 [CodexAuthMode.CLI] 时**不注入**
 * `OPENAI_API_KEY` —— 官方登录读的是它自己那份 auth.json，塞一个环境变量进去只会
 * 让「官方登录」偷偷走别人的 key。
 *
 * @param claudeBaseUrlOverride 机内路由给出的本地地址。非空时终端里的 `claude` 也走路由，
 *   和会话进程看到的是同一个入口。空 = 直连配置里的地址。
 *   **settings.json 的托管区不走这个参数**——那份文件可能被 App 外的脚本读，
 *   写 `127.0.0.1` 进去等于把它们指向一个随时会消失的端口。
 */
internal fun shellCredentialEnv(
    settings: AppSettings,
    claudeBaseUrlOverride: String? = null,
): Map<String, String> {
    if (!settings.injectCredentialsIntoShells) return emptyMap()
    return buildMap {
        // 走订阅时是 null：Claude 这一侧一个键都不给（连 BASE_URL 也不给），终端里的 claude 用它自己的登录
        settings.claudeProfile?.let { profile ->
            putAll(sanitizedProfileEnv(profile))
            put("ANTHROPIC_BASE_URL", claudeBaseUrlOverride ?: profile.baseUrl)
            // 键名跟着这条供应商的认证方式走，和会话进程拿到的是同一个（见 ApiProfile.authHeader）
            if (profile.token.isNotBlank()) put(profile.tokenEnvKey, profile.token)
        }
        settings.activeCodexProfile
            ?.takeIf { it.authMode != CodexAuthMode.CLI && it.apiKey.isNotBlank() }
            ?.let { profile ->
                putAll(sanitizedCodexEnv(profile))
                val base = if (profile.isRelay) profile.baseUrl.trimEnd('/') else OPENAI_API_BASE_URL
                if (base.isNotBlank()) put("OPENAI_BASE_URL", base)
                put("OPENAI_API_KEY", profile.apiKey)
                put("CODEX_API_KEY", profile.apiKey)
            }
    }
}

/** 和 `CodexConfigToml.kt` 里那个常量同值。这里不 import，免得 settings 反过来依赖 codex 包 */
private const val OPENAI_API_BASE_URL = "https://api.openai.com/v1"
