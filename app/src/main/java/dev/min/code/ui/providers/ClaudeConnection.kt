package dev.min.code.ui.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * Claude Code 此刻走的是哪条路。
 *
 * 有了「用 Claude 订阅」之后，`activeProfile` 只是「供应商表里选中的那家」，**不再等于**
 * Claude 此刻用的连接：走订阅时它照样有值（切回来时用它），但一个认证变量都不注入。
 * 所以界面上所有「现在连的是谁」都从这里取，不直接读 `activeProfile`。
 */
sealed interface ClaudeConnection {
    data object Subscription : ClaudeConnection

    /** 走供应商，[name] 是那家的显示名。缺 token 时仍算这一种——显示名照旧，缺 key 由各处自己提示 */
    data class Provider(val name: String) : ClaudeConnection

    /** 走供应商，但表里一家都没有 */
    data object None : ClaudeConnection
}

fun claudeConnectionOf(settings: AppSettings): ClaudeConnection = when {
    settings.claudeAuth == ClaudeAuthMode.SUBSCRIPTION -> ClaudeConnection.Subscription
    else -> settings.activeProfile?.let { ClaudeConnection.Provider(it.displayName()) } ?: ClaudeConnection.None
}

/**
 * 供应商表里哪一行该画成「使用中」。走订阅时一行都不是——选中的那家此刻没在用，
 * 点它才是切回去。
 */
val AppSettings.claudeProviderInUseId: String?
    get() = activeProfile?.id?.takeIf { claudeAuth == ClaudeAuthMode.PROVIDER }

/** 短名：「Claude 订阅」或供应商名；一家都没有时是空串 */
@Composable
fun ClaudeConnection.shortLabel(): String = when (this) {
    ClaudeConnection.Subscription -> stringResource(R.string.claude_connection_subscription)
    is ClaudeConnection.Provider -> name
    ClaudeConnection.None -> ""
}

/** 长名：「Claude 订阅」/「供应商 · 名字」/「还没有连接」，给不带「供应商」标题的地方 */
@Composable
fun ClaudeConnection.longLabel(): String = when (this) {
    ClaudeConnection.Subscription -> stringResource(R.string.claude_connection_subscription)
    is ClaudeConnection.Provider -> stringResource(R.string.settings_claude_auth_provider_named, name)
    ClaudeConnection.None -> stringResource(R.string.settings_claude_auth_none)
}

/**
 * Claude 此刻连的是谁（短名），给抽屉 / 会话设置里「供应商」那一行当副题。
 *
 * 直接订 [SettingsStore]，不经 ViewModel：这里要的只是一个名字，为一行字拉起
 * 一整个 VM（它还要加载预设表、连会话注册表）不值当。
 */
@Composable
fun rememberClaudeConnectionLabel(): String {
    val store: SettingsStore = koinInject()
    val flow = remember(store) { store.settings.map(::claudeConnectionOf).distinctUntilChanged() }
    val connection by flow.collectAsStateWithLifecycle(initialValue = ClaudeConnection.None)
    return connection.shortLabel()
}
