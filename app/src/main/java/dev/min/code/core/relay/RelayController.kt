package dev.min.code.core.relay

import android.util.Log
import dev.min.code.core.settings.ApiFormat
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AuthHeader
import dev.min.code.core.settings.CODEX_WIRE_API_CHAT
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RelayController"

/**
 * 要不要起本地路由、给 CLI 哪一个 base URL，都从这里问。
 *
 * ## 什么时候起
 *
 * - Claude：当前生效的那条 [ApiProfile.apiFormat] 不是原生 Anthropic Messages；
 * - Codex：当前生效的那条是中转，且 `wire_api = chat`（只提供 `/chat/completions`）。
 *
 * 两个条件都不成立时服务器停着——不占端口，也不让「看起来在路由、其实在直连」
 * 这种状态存在。
 *
 * ## 给出去的地址
 *
 * 返回的是 `http://127.0.0.1:{port}/r/{token}/…` 这种**只在本机、只在 App 活着时通**
 * 的地址。界面上必须把这句话说出来：终端里手敲的 `claude` 同理，App 一杀就断。
 */
class RelayController(
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()
    private var server: LocalRelayServer? = null

    private val resolver = LocalRelayServer.Resolver { kind, profileId ->
        // resolver 在 HTTP 工作线程上被调，只能阻塞地读；SettingsStore.current 很快
        val settings = runBlocking(Dispatchers.IO) { settingsStore.current() }
        when (kind) {
            LocalRelayServer.Kind.Claude -> {
                val profile = settings.profiles.firstOrNull { it.id == profileId } ?: return@Resolver null
                LocalRelayServer.Upstream(
                    baseUrl = profile.baseUrl,
                    token = profile.token,
                    toChatCompletions = profile.apiFormat.needsRelay,
                    fullUrlEndpoint = profile.fullUrlEndpoint,
                    authHeader = when (profile.authHeader) {
                        AuthHeader.API_KEY -> LocalRelayServer.AuthStyle.ApiKey
                        AuthHeader.AUTH_TOKEN -> LocalRelayServer.AuthStyle.Bearer
                    },
                )
            }
            LocalRelayServer.Kind.Codex -> {
                val profile = settings.codexProfiles.firstOrNull { it.id == profileId } ?: return@Resolver null
                LocalRelayServer.Upstream(
                    baseUrl = profile.baseUrl,
                    token = profile.apiKey,
                    toChatCompletions = profile.effectiveWireApi == CODEX_WIRE_API_CHAT,
                    fullUrlEndpoint = false,
                    authHeader = LocalRelayServer.AuthStyle.Bearer,
                )
            }
        }
    }

    /**
     * Claude 侧该用的 base URL。
     *
     * 需要路由时返回本地地址，并把服务器拉起来；不需要时返回配置里的原地址，
     * 顺手把服务器停掉（如果 Codex 侧也不需要的话）。
     */
    suspend fun claudeBaseUrl(profile: ApiProfile): String = mutex.withLock {
        if (!profile.apiFormat.needsRelay) {
            stopIfUnusedLocked(alsoClaude = false)
            return profile.baseUrl
        }
        val s = ensureRunningLocked()
        s.claudeBaseUrl(profile.id)
    }

    /**
     * Codex 侧该用的 base URL（写进 config.toml 的那个）。
     *
     * 只有中转 + chat wire_api 才走路由；官方登录 / 官方 API key / responses 方言都直连。
     */
    suspend fun codexBaseUrl(profile: CodexProfile): String? = mutex.withLock {
        if (!profile.isRelay || profile.effectiveWireApi != CODEX_WIRE_API_CHAT) {
            stopIfUnusedLocked(alsoCodex = false)
            return@withLock null
        }
        val s = ensureRunningLocked()
        s.codexBaseUrl(profile.id)
    }

    /** 当前生效的配置还要不要路由。两边都不要了就停 */
    suspend fun reconcile() = mutex.withLock {
        val settings = settingsStore.current()
        val needClaude = settings.activeProfile?.apiFormat?.needsRelay == true
        val needCodex = settings.activeCodexProfile?.let {
            it.isRelay && it.effectiveWireApi == CODEX_WIRE_API_CHAT
        } == true
        if (needClaude || needCodex) {
            ensureRunningLocked()
        } else {
            stopLocked()
        }
    }

    suspend fun shutdown() = mutex.withLock { stopLocked() }

    val isRunning: Boolean get() = server?.isRunning == true
    val port: Int get() = server?.port ?: -1

    private fun ensureRunningLocked(): LocalRelayServer {
        val existing = server
        if (existing != null && existing.isRunning) return existing
        val s = LocalRelayServer(resolver)
        s.start()
        server = s
        Log.i(TAG, "relay up on ${s.port}")
        return s
    }

    private suspend fun stopIfUnusedLocked(alsoClaude: Boolean = true, alsoCodex: Boolean = true) {
        val settings = settingsStore.current()
        val needClaude = alsoClaude && settings.activeProfile?.apiFormat?.needsRelay == true
        val needCodex = alsoCodex && settings.activeCodexProfile?.let {
            it.isRelay && it.effectiveWireApi == CODEX_WIRE_API_CHAT
        } == true
        // 调用方已经表明「我这边不要了」；另一边如果也不要，就停
        val stillNeedClaude = if (alsoClaude) needClaude else settings.activeProfile?.apiFormat?.needsRelay == true
        val stillNeedCodex = if (alsoCodex) needCodex else settings.activeCodexProfile?.let {
            it.isRelay && it.effectiveWireApi == CODEX_WIRE_API_CHAT
        } == true
        if (!stillNeedClaude && !stillNeedCodex) stopLocked()
    }

    private fun stopLocked() {
        server?.stop()
        server = null
    }
}
