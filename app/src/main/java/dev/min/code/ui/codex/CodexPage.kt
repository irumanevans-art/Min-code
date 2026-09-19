package dev.min.code.ui.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexEvent
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel

/**
 * Codex 的会话页。
 *
 * 两态：会话里（[CodexTranscript] + 输入坞，和 Claude 那边同一套渲染）和
 * 会话外（安装 / 登录 / 启动）。以前是把配置表单、登录提示、审批卡、输入框
 * 全堆在一个 verticalScroll 里，输入框吊在最底下，一打字就被键盘顶掉。
 */
@Composable
fun CodexPage(vm: CodexVM = koinViewModel()) {
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var showConnection by remember { mutableStateOf(false) }

    // 有历史就一直显示会话流 —— 会话停掉之后把读过的内容抹掉，
    // 等于每次停止都清一次屏
    val inSession = session.items.isNotEmpty() ||
        session.status == SessionStatus.Running ||
        session.status == SessionStatus.Starting

    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(R.string.codex_title),
                subtitle = stringResource(session.status.labelRes()),
                navigationIcon = { BackButton() },
                actions = {
                    if (runtime.installed) {
                        InkTextButton(onClick = { showConnection = true }) {
                            Text(stringResource(R.string.codex_connection))
                        }
                    }
                    when {
                        session.status == SessionStatus.Running ->
                            InkTextButton(onClick = vm::stop, tone = InkButtonTone.Vermilion) {
                                Text(stringResource(R.string.codex_stop))
                            }

                        // 停掉或失败之后历史还留在屏幕上，于是这一页不会退回启动面板 ——
                        // 没有这个按钮就再也开不起来，只能退出页面重进
                        inSession && session.status != SessionStatus.Starting ->
                            InkTextButton(onClick = vm::start) {
                                Text(stringResource(R.string.codex_restart))
                            }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (inSession) {
                CodexTranscript(
                    session = session,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        // 给底部的输入坞留出位置，否则最后一条永远压在它下面
                        bottom = 96.dp,
                    ),
                )
                CodexComposer(
                    enabled = session.canSend,
                    busy = session.busy,
                    onSend = vm::send,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                CodexStartPane(
                    runtime = runtime,
                    session = session,
                    onInstall = vm::install,
                    onStart = vm::start,
                    onOpenConnection = { showConnection = true },
                )
            }
        }
    }

    if (showConnection) {
        CodexConnectionSheet(
            profile = profile,
            onSave = {
                vm.saveProfile(it)
                showConnection = false
            },
            onDismiss = { showConnection = false },
        )
    }

    session.pendingApproval?.let { approval ->
        CodexApprovalSheet(approval = approval, onAnswer = vm::answerApproval)
    }
}

/** 会话外那一屏：装 Codex、去登录、开始。 */
@Composable
private fun CodexStartPane(
    runtime: dev.min.code.core.codex.CodexRuntime.Status,
    session: dev.min.code.core.codex.CodexAppServerManager.State,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val navigator = LocalNavController.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            session.errorMessage?.let { Notice(it, tone = NoticeTone.Error) }

            if (!runtime.installed) {
                Notice(
                    text = runtime.error ?: stringResource(R.string.codex_install_hint),
                    tone = if (runtime.error != null) NoticeTone.Error else NoticeTone.Info,
                )
                InkButton(
                    onClick = onInstall,
                    busy = runtime.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_install)) }
                return@Column
            }

            Text(
                runtime.version ?: stringResource(R.string.codex_installed),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!runtime.authenticated) {
                // 还没登录不是错误，是「还差一步」——用海的水洗版警示，不上朱红
                Notice(
                    text = runtime.loginStatus ?: stringResource(R.string.codex_login_hint),
                    tone = NoticeTone.Warn,
                )
                InkButton(
                    onClick = { navigator.navigate(Screen.Terminal) },
                    tone = InkButtonTone.Paper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_open_login_terminal)) }
                Text(
                    LOGIN_COMMAND,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InkButton(
                    onClick = onOpenConnection,
                    tone = InkButtonTone.Paper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_connection_open)) }
            }

            InkButton(
                onClick = onStart,
                enabled = runtime.authenticated,
                busy = session.status == SessionStatus.Starting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codex_start)) }
        }
    }
}

/**
 * 输入坞。停靠在底部、跟着键盘走 —— 以前它是滚动列的最后一项，
 * 一聚焦就被键盘顶出视野。
 */
@Composable
private fun CodexComposer(
    enabled: Boolean,
    busy: Boolean,
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    PaperCard(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            InkTextField(
                value = input,
                onValueChange = { input = it },
                label = stringResource(R.string.codex_composer_hint),
                enabled = enabled,
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            InkButton(
                onClick = { if (onSend(input)) input = "" },
                enabled = enabled && input.isNotBlank(),
                busy = busy,
                compact = true,
            ) { Text(stringResource(R.string.codex_send)) }
        }
    }
}

/**
 * 审批。
 *
 * 和 Claude 的权限面板同一条规矩：**不许滑走**。它是在会话流上方弹出来的，
 * 用户很可能正往回滑历史，弹出那一瞬间手指还在动，一下就滑掉了 ——
 * 而滑掉只能当成拒绝，模型会以为你否了这件事，转头去改方案。出口只有按钮。
 *
 * 「拒绝」是判定，用朱红；「允许」不是。第三个按钮只在服务端真的给了
 * acceptForSession 时才画 —— 回一个它没列出来的决定，这一轮会一直等下去。
 */
@Composable
private fun CodexApprovalSheet(
    approval: CodexEvent.ApprovalRequest,
    onAnswer: (CodexDecision) -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val allowsSession = approval.availableDecisions.isEmpty() ||
        CodexDecision.ACCEPT_FOR_SESSION.wire in approval.availableDecisions

    InkSheet(
        onDismissRequest = { /* 只能按按钮，见上 */ },
        sheetState = sheetState,
        dismissible = false,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(
                    when (approval.kind) {
                        CodexEvent.ApprovalRequest.Kind.Command -> R.string.codex_command_approval
                        else -> R.string.codex_file_approval
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            approval.command?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = JetbrainsMono)
            }
            approval.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            approval.cwd?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.codex_approval_cwd, it),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InkButton(
                onClick = { onAnswer(CodexDecision.ACCEPT) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codex_approve)) }

            if (allowsSession) {
                InkButton(
                    onClick = { onAnswer(CodexDecision.ACCEPT_FOR_SESSION) },
                    tone = InkButtonTone.Paper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_approve_session)) }
            }

            InkButton(
                onClick = { onAnswer(CodexDecision.DECLINE) },
                tone = InkButtonTone.Vermilion,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codex_deny)) }
        }
    }
}

/** 连接方式。从会话页挪进 sheet —— 它是设一次就不再看的东西，不该常驻在会话上方 */
@Composable
private fun CodexConnectionSheet(
    profile: dev.min.code.core.settings.CodexProfile,
    onSave: (dev.min.code.core.settings.CodexProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember(profile.apiKey) { mutableStateOf(profile.apiKey) }
    var baseUrl by remember(profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var mode by remember(profile.authMode) { mutableStateOf(profile.authMode) }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.codex_connection),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AuthModeChip(R.string.codex_auth_cli, CodexAuthMode.CLI, mode) { mode = it }
                AuthModeChip(R.string.codex_auth_openai, CodexAuthMode.OPENAI_API_KEY, mode) { mode = it }
                AuthModeChip(R.string.codex_auth_relay, CodexAuthMode.RELAY, mode) { mode = it }
            }
            if (mode != CodexAuthMode.CLI) {
                InkTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = stringResource(R.string.codex_api_key),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (mode == CodexAuthMode.RELAY) {
                    InkTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = stringResource(R.string.codex_base_url),
                        singleLine = true,
                    )
                }
            }
            InkButton(
                onClick = { onSave(profile.copy(apiKey = apiKey, baseUrl = baseUrl, authMode = mode)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codex_save_connection)) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AuthModeChip(
    labelRes: Int,
    value: CodexAuthMode,
    current: CodexAuthMode,
    onPick: (CodexAuthMode) -> Unit,
) {
    InkButton(
        onClick = { onPick(value) },
        tone = if (current == value) InkButtonTone.Ink else InkButtonTone.Paper,
        compact = true,
        modifier = Modifier.weight(1f),
    ) { Text(stringResource(labelRes)) }
}

/** 顶栏副标题。状态词是给人读的，走资源而不是 enum 的 name */
private fun SessionStatus.labelRes(): Int = when (this) {
    SessionStatus.Idle -> R.string.codex_status_idle
    SessionStatus.Starting -> R.string.codex_status_starting
    SessionStatus.Running -> R.string.codex_status_running
    SessionStatus.Closed -> R.string.codex_status_closed
    SessionStatus.Failed -> R.string.codex_status_failed
}

/** 官方登录命令，给用户照着在终端里敲 */
private const val LOGIN_COMMAND = "/opt/codex/bin/codex login --device-auth"
