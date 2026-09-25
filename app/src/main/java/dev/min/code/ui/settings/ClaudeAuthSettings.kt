package dev.min.code.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.providers.claudeConnectionOf
import dev.min.code.ui.providers.longLabel
import dev.min.code.ui.session.ClaudeSubscriptionSheet
import dev.min.code.ui.theme.InkMotion

/** 在「连接方式」上点了一格之后该做什么。纯逻辑，见 [claudeAuthPick] */
internal enum class ClaudeAuthPick {
    /** 点的就是当前这条路 */
    NOTHING,

    /** 切回供应商（不登出） */
    USE_PROVIDER,

    /** 想走供应商，但表里没有能用的（一家都没有、或选中的那家没 key）：送去供应商页，在那里点一家就切回 */
    OPEN_PROVIDERS,

    /** 打开订阅登录面板 */
    LOGIN,
}

internal fun claudeAuthPick(target: ClaudeAuthMode, settings: AppSettings): ClaudeAuthPick = when {
    target == settings.claudeAuth -> ClaudeAuthPick.NOTHING
    target == ClaudeAuthMode.SUBSCRIPTION -> ClaudeAuthPick.LOGIN
    settings.activeProfile?.missingToken == false -> ClaudeAuthPick.USE_PROVIDER
    else -> ClaudeAuthPick.OPEN_PROVIDERS
}

/**
 * 设置 → 连接 顶上那一段：Claude Code 走哪条路。
 *
 * 当前那条路写成一行字，下面一对分段钮切换。分段钮的选中态只跟 `claudeAuth` 走——
 * 点「Claude 订阅」只是打开登录面板，登录成功它才滑过去；中途取消或失败就留在原地，
 * 不会显示成一个其实没生效的选择。
 *
 * 「退出订阅登录」是登出，不删任何配置，所以用纸色次按钮，不用朱。
 */
@Composable
internal fun ClaudeAuthSettings(vm: SettingsVM, settings: AppSettings) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val busy by vm.claudeAuthBusy.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val onSubscription = settings.claudeAuth == ClaudeAuthMode.SUBSCRIPTION

    SectionTitle(stringResource(R.string.settings_claude_auth_title))
    Text(claudeConnectionOf(settings).longLabel(), style = MaterialTheme.typography.bodyMedium)
    InkSegmented(
        options = listOf(
            stringResource(R.string.settings_claude_auth_option_provider),
            stringResource(R.string.claude_connection_subscription),
        ),
        selected = if (onSubscription) 1 else 0,
        enabled = !busy,
        onSelect = { index ->
            val target = if (index == 1) ClaudeAuthMode.SUBSCRIPTION else ClaudeAuthMode.PROVIDER
            when (claudeAuthPick(target, settings)) {
                ClaudeAuthPick.NOTHING -> Unit
                ClaudeAuthPick.USE_PROVIDER -> vm.useClaudeProvider()
                ClaudeAuthPick.OPEN_PROVIDERS -> {
                    toaster.show(context.getString(R.string.settings_claude_auth_no_provider))
                    navController.navigate(Screen.Providers)
                }
                ClaudeAuthPick.LOGIN -> login = true
            }
        },
    )
    AnimatedVisibility(visible = onSubscription, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InkButton(
                onClick = { confirmLogout = true },
                tone = InkButtonTone.Paper,
                busy = busy,
            ) { Text(stringResource(R.string.settings_claude_logout)) }
            Text(
                stringResource(R.string.settings_claude_logout_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (login) ClaudeSubscriptionSheet(onDismiss = { login = false })

    RikkaConfirmDialog(
        show = confirmLogout,
        title = stringResource(R.string.settings_claude_logout_title),
        confirmText = stringResource(R.string.settings_claude_logout),
        dismissText = stringResource(R.string.common_cancel),
        destructive = false,
        onConfirm = {
            confirmLogout = false
            val done = context.getString(R.string.settings_claude_logout_done)
            vm.logoutClaudeSubscription { toaster.show(done, ToastType.Success) }
        },
        onDismiss = { confirmLogout = false },
    ) {
        Text(stringResource(R.string.settings_claude_logout_body))
    }
}
