package dev.min.code.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeLoginFailure
import dev.min.code.core.claudecode.ClaudeLoginState
import dev.min.code.core.claudecode.ClaudeSubscription
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkSpinner
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.openInExternalBrowser
import dev.min.code.ui.providers.StaleSessionsNotice
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.seaInk
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import org.koin.compose.koinInject

/**
 * 「用 Claude 订阅登录」的面板：照着 [ClaudeSubscription.login] 画，**打开即开始登录**。
 *
 * 三个入口（启动面板、设置 → 连接、会话里的快速切换）都只管把这张面板打开，
 * 「开始」只写在这里一处。已经登录过的话 [ClaudeSubscription.startLogin] 不开浏览器，
 * 直接走到 Done。
 *
 * 登录本身全是官方 CLI 的事，这张面板只做两件事：把 CLI 交出来的授权页交给**外部**浏览器
 * （为什么不能是预览槽见 [openInExternalBrowser]），以及说清楚现在走到哪了。
 *
 * 关面板的规矩：还在进行中（等 URL / 等浏览器 / 在确认）就先问一句再 [ClaudeSubscription.cancelLogin]
 * ——人多半是切回来看一眼，误滑一下就把等着回调的 CLI 杀了很冤；已经结束的直接
 * [ClaudeSubscription.acknowledge]，下次进来是干净的。
 *
 * 登录成功后有正忙的会话没切过去（[ClaudeSubscription.staleSessions]）时，Done 这一步挂上供应商页
 * 同一条「重启它们」提示，并且**不自己关**——自己关的话，从会话里的快速切换进来的人就看不到这句了。
 * 用户处理掉（重启或先不动）之后再照常停一下自己关。
 */
@Composable
fun ClaudeSubscriptionSheet(
    onDismiss: () -> Unit,
    subscription: ClaudeSubscription = koinInject(),
) {
    val state by subscription.login.collectAsStateWithLifecycle()
    val inProgress by subscription.active.collectAsStateWithLifecycle()
    val stale by subscription.staleSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askCancel by remember { mutableStateOf(false) }
    var noBrowser by remember { mutableStateOf(false) }
    // 见过进行中之后又回到 Idle = 这一次被取消了（不是刚打开时那一帧的 Idle）。
    // 只认进行中的三态：打开那一瞬可能先看到上一次没看过的 Done / Failed，它们随即被清成 Idle，不能算
    var started by remember { mutableStateOf(false) }

    fun finish() {
        subscription.acknowledge()
        onDismiss()
    }

    fun close() {
        if (inProgress) askCancel = true else finish()
    }

    LaunchedEffect(Unit) {
        // 上一次的结果若没被看过（面板随宿主一起消失时会这样），先清掉，免得开头闪一下旧的失败
        subscription.acknowledge()
        subscription.startLogin()
    }

    LaunchedEffect(state) {
        when (val s = state) {
            ClaudeLoginState.Idle -> if (started) onDismiss()
            is ClaudeLoginState.Browser -> {
                started = true
                // 进入这一步时自动开一次；「再打开一次」给关掉了浏览器的人
                noBrowser = !context.openInExternalBrowser(s.url)
            }
            ClaudeLoginState.Starting, ClaudeLoginState.Verifying -> started = true
            // 自己关不关另看下面那一个：它还要看有没有没切过去的会话
            ClaudeLoginState.Done, is ClaudeLoginState.Failed -> Unit
        }
    }

    // 不能并进上面那个 LaunchedEffect 的 key：那边 Browser 一步会自动开浏览器，key 一变就会再开一次
    val autoClose = state == ClaudeLoginState.Done && stale.isEmpty()
    LaunchedEffect(autoClose) {
        if (autoClose) {
            delay(DONE_LINGER_MS)
            finish()
        }
    }

    val inProgressNow by rememberUpdatedState(inProgress)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // 进行中不让滑掉 / 点遮罩 / 返回键直接关：改成先问一句（见头注释）
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && inProgressNow) {
                askCancel = true
                false
            } else {
                true
            }
        },
    )

    InkSheet(onDismissRequest = ::finish, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seal()
                Text(stringResource(R.string.claude_login_title), style = MaterialTheme.typography.titleMedium)
            }

            AnimatedContent(
                targetState = state,
                transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
                contentKey = { it::class },
                label = "claudeLogin",
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (s) {
                        ClaudeLoginState.Idle, ClaudeLoginState.Starting -> {
                            Waiting(stringResource(R.string.claude_login_starting))
                            Actions {
                                InkButton(onClick = ::close, tone = InkButtonTone.Paper) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            }
                        }
                        is ClaudeLoginState.Browser -> {
                            Waiting(stringResource(R.string.claude_login_browser))
                            if (noBrowser) {
                                Notice(stringResource(R.string.claude_login_no_browser), tone = NoticeTone.Warn)
                            }
                            Actions {
                                InkButton(onClick = ::close, tone = InkButtonTone.Paper) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                InkButton(onClick = { noBrowser = !context.openInExternalBrowser(s.url) }) {
                                    Text(stringResource(R.string.claude_login_reopen))
                                }
                            }
                        }
                        ClaudeLoginState.Verifying -> Waiting(stringResource(R.string.claude_login_verifying))
                        ClaudeLoginState.Done -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(HugeIcons.Tick01, null, Modifier.size(18.dp).seaInk())
                                Text(stringResource(R.string.claude_login_done), style = MaterialTheme.typography.bodyMedium)
                            }
                            StaleSessionsNotice(
                                count = stale.size,
                                onRestart = subscription::restartStaleSessions,
                                onDismiss = subscription::dismissStaleSessions,
                            )
                            Actions {
                                InkButton(onClick = ::finish) { Text(stringResource(R.string.claude_login_done_action)) }
                            }
                        }
                        is ClaudeLoginState.Failed -> {
                            // 登录没成是一次判定，用朱
                            Notice(stringResource(failureText(s.reason)), tone = NoticeTone.Error)
                            Actions {
                                InkButton(onClick = ::finish, tone = InkButtonTone.Paper) {
                                    Text(stringResource(R.string.common_close))
                                }
                                InkButton(onClick = subscription::startLogin) {
                                    Text(stringResource(R.string.claude_login_retry))
                                }
                            }
                        }
                    }
                }
            }

            InkDivider()
            Text(
                stringResource(R.string.claude_login_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    RikkaConfirmDialog(
        show = askCancel,
        title = stringResource(R.string.claude_login_cancel_title),
        confirmText = stringResource(R.string.claude_login_cancel_confirm),
        dismissText = stringResource(R.string.claude_login_keep_waiting),
        // 取消一次登录不毁任何东西，不用朱
        destructive = false,
        onConfirm = {
            askCancel = false
            subscription.cancelLogin()
            onDismiss()
        },
        onDismiss = { askCancel = false },
    ) {
        Text(stringResource(R.string.claude_login_cancel_body))
    }
}

/** 正在等的一步：一圈墨 + 一句话 */
@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InkSpinner(size = 16.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private fun failureText(reason: ClaudeLoginFailure): Int = when (reason) {
    ClaudeLoginFailure.CLI_MISSING -> R.string.claude_login_failed_cli_missing
    ClaudeLoginFailure.NO_URL -> R.string.claude_login_failed_no_url
    ClaudeLoginFailure.TIMEOUT -> R.string.claude_login_failed_timeout
    ClaudeLoginFailure.NOT_LOGGED_IN -> R.string.claude_login_failed_not_logged_in
}

/** 成功那一句停留多久再自己关：够读完一句短话，又不用人再点一下 */
private const val DONE_LINGER_MS = 1_500L
