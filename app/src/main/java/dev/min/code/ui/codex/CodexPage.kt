package dev.min.code.ui.codex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.SessionMeta
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexEvent
import dev.min.code.core.codex.CodexSessionSummary
import dev.min.code.core.codex.CODEX_EFFORT_LEVELS
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkMenuItem
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.session.RenameDialog
import dev.min.code.ui.session.SeaSendKey
import dev.min.code.ui.theme.CloudTheme
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookmark02
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Stop
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Codex 的会话页。
 *
 * 两态：会话里（[CodexTranscript] + 输入坞，和 Claude 那边同一套渲染）和
 * 会话外（安装 / 登录 / 启动）。以前是把配置表单、登录提示、审批卡、输入框
 * 全堆在一个 verticalScroll 里，输入框吊在最底下，一打字就被键盘顶掉。
 *
 * 整页罩在 [CloudTheme] 里：Claude 那边的取底是海，这边是云。两个引擎长得一样
 * 是对的——同一套组件、同一套折叠规则——但**在哪个引擎里**要一眼看得出来，
 * 而不是靠去读顶栏那行字。换底是整页的事，所以罩在最外面，
 * 连带底下的 sheet 和对话框一起。
 */
@Composable
fun CodexPage(vm: CodexVM = koinViewModel()) {
    CloudTheme { CodexPageContent(vm) }
}

@Composable
private fun CodexPageContent(vm: CodexVM) {
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var showConnection by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sessionMetas by vm.sessionMetas.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()

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
                    if (session.status == SessionStatus.Running) {
                        InkTextButton(onClick = vm::stop, tone = InkButtonTone.Vermilion) {
                            Text(stringResource(R.string.codex_stop))
                        }
                    } else {
                        // 会话列表和连接方式都只在没跑的时候给：切到别的会话要覆盖
                        // 屏幕上的内容，而那时候屏幕上的才是实时的；连接改了也要重启才生效。
                        // 两个都用图标，省出横向空间给「继续 / 新会话」
                        if (runtime.installed) {
                            InkIconButton(
                                icon = HugeIcons.LeftToRightListBullet,
                                contentDescription = stringResource(R.string.codex_sessions),
                                onClick = {
                                    vm.refreshSessions()
                                    showSessions = true
                                },
                            )
                            InkIconButton(
                                icon = HugeIcons.Settings02,
                                contentDescription = stringResource(R.string.codex_connection),
                                onClick = { showConnection = true },
                            )
                        }
                        // 屏幕上有历史（刚停的、或是上次重进 App 读回来的）时，
                        // 「继续」接着那条 thread 聊，「新会话」另起一条。
                        // 没有这两个按钮，停掉之后就再也开不起来，只能退出页面重进
                        if (inSession && session.status != SessionStatus.Starting) {
                            InkTextButton(onClick = vm::startNew) {
                                Text(stringResource(R.string.codex_new_session))
                            }
                            InkTextButton(onClick = vm::start) {
                                Text(stringResource(R.string.codex_resume))
                            }
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
                    draft = draft,
                    enabled = session.canSend,
                    busy = session.busy,
                    onDraftChange = vm::setDraft,
                    onSend = vm::send,
                    onStop = vm::stop,
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

    if (showSessions) {
        CodexSessionSheet(
            sessions = sessions,
            metas = sessionMetas,
            onPick = {
                vm.openSession(it)
                showSessions = false
            },
            onPin = { summary, pinned -> vm.pinSession(summary.threadId, pinned) },
            onRename = { summary, title -> vm.renameSession(summary.threadId, title) },
            onDelete = { vm.deleteSession(it) },
            onDismiss = { showSessions = false },
        )
    }

    session.pendingApproval?.let { approval ->
        CodexApprovalSheet(approval = approval, onAnswer = vm::answerApproval)
    }
}

/**
 * 旧会话。读的是 Codex 自己写的那些 rollout 文件，所以 App 重装、换设备，
 * 只要 rootfs 还在，聊过的东西就都还在。
 *
 * 点一条**只是把它摆出来**，不自动接上 —— 翻看和继续跑是两件事。
 *
 * 行尾菜单给 置顶 / 改名 / 删除。标题和置顶来自 App 侧元数据（[metas]，
 * Claude 抽屉同一套 store，threadId 当 key）；删除连 rollout 文件一起走 ——
 * 历史的事实来源是文件，删了就是没了，所以先过确认框。
 */
@Composable
private fun CodexSessionSheet(
    sessions: List<CodexSessionSummary>,
    metas: Map<String, SessionMeta>,
    onPick: (CodexSessionSummary) -> Unit,
    onPin: (CodexSessionSummary, Boolean) -> Unit,
    onRename: (CodexSessionSummary, String) -> Unit,
    onDelete: (CodexSessionSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val stamp = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var pendingRename by remember { mutableStateOf<CodexSessionSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<CodexSessionSummary?>(null) }
    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.codex_sessions),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.codex_sessions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // 一屏放不下就滚，但别把整个 sheet 撑满屏幕
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sessions, key = { it.threadId }) { summary ->
                    val meta = metas[summary.threadId]
                    val menuOpen = remember(summary.threadId) { mutableStateOf(false) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(summary) }
                            .padding(vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    meta?.title
                                        ?: summary.preview
                                        ?: stringResource(R.string.codex_session_untitled),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (meta?.pinned == true) {
                                        // 和 Claude 抽屉同一个角标：右上角一枚小书签
                                        Icon(
                                            HugeIcons.Bookmark02,
                                            contentDescription =
                                                stringResource(R.string.codex_session_pinned),
                                            tint = MaterialTheme.sea.seaDeep.copy(alpha = 0.55f),
                                            modifier = Modifier.size(10.dp),
                                        )
                                    }
                                    Text(
                                        stamp.format(Date(summary.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetbrainsMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Box {
                                InkIconButton(
                                    icon = HugeIcons.MoreHorizontal,
                                    contentDescription = stringResource(R.string.session_row_actions),
                                    onClick = { menuOpen.value = true },
                                    tint = MaterialTheme.colorScheme.outline,
                                    size = 32.dp,
                                    iconSize = 14.dp,
                                )
                                DropdownMenu(
                                    expanded = menuOpen.value,
                                    onDismissRequest = { menuOpen.value = false },
                                    shape = RoundedCornerShape(12.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    val pinned = meta?.pinned == true
                                    InkMenuItem(
                                        stringResource(
                                            if (pinned) R.string.session_unpin else R.string.session_pin,
                                        ),
                                        icon = if (pinned) HugeIcons.PinOff else HugeIcons.Pin,
                                        onClick = { menuOpen.value = false; onPin(summary, !pinned) },
                                    )
                                    InkMenuItem(
                                        stringResource(R.string.common_rename),
                                        icon = HugeIcons.Edit02,
                                        onClick = { menuOpen.value = false; pendingRename = summary },
                                    )
                                    InkMenuItem(
                                        stringResource(R.string.common_delete),
                                        icon = HugeIcons.Delete02,
                                        tint = MaterialTheme.sea.vermilion,
                                        onClick = { menuOpen.value = false; pendingDelete = summary },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRename?.let { target ->
        RenameDialog(
            current = metas[target.threadId]?.title ?: target.preview.orEmpty(),
            onConfirm = {
                onRename(target, it)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }
    pendingDelete?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.codex_session_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                onDelete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        ) {
            // 正文里的名字要和列表上显示的一致：改过名的用改名，而不是磁盘摘要
            val displayName = metas[target.threadId]?.title
                ?: target.preview
                ?: stringResource(R.string.codex_session_untitled)
            Text(stringResource(R.string.codex_session_delete_body, displayName))
        }
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
 *
 * 正文由 VM 持有并落盘（草稿）：切页、杀进程再回来都还在，
 * threadId 换了 VM 会自己换档，这里不保管状态。
 *
 * 发送键与停止键的摆法照抄 Claude 的胶囊（SeaSendKey + 并排停止）：
 * 两个引擎在屏幕上要长一个样，差别只在谁在说话。
 */
@Composable
private fun CodexComposer(
    draft: String,
    enabled: Boolean,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                value = draft,
                onValueChange = onDraftChange,
                label = stringResource(R.string.codex_composer_hint),
                enabled = enabled,
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            // 停止键与发送键并排、负间距相叠——与 Claude 胶囊同一组：
            // 它们是同一件事的两个方向。busy 时发送键睡着（Codex 不排队，
            // 排队语义是 Claude 侧的另一个决定），停止键必须一按就到
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-6).dp),
            ) {
                AnimatedVisibility(
                    visible = busy,
                    enter = InkMotion.expand,
                    exit = InkMotion.collapse,
                ) {
                    InkIconButton(
                        icon = HugeIcons.Stop,
                        contentDescription = stringResource(R.string.composer_interrupt),
                        onClick = onStop,
                        tint = MaterialTheme.sea.vermilion,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }
                SeaSendKey(
                    enabled = enabled && draft.isNotBlank(),
                    queued = false,
                    onClick = { if (onSend(draft)) onDraftChange("") },
                )
            }
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
    // 按钮是按 availableDecisions 过滤的，正常情况下应答必然成功；但服务端可能在
    // 弹出之后改变主意。静默失败会让用户以为批过了、实际上这一轮还挂着——
    // 至少要说一声，sheet 留着让人再试。remember 按 approval 记忆：
    // 换了一条新审批时旧的红字必须清掉，那是上一个请求的事
    var rejected by remember(approval) { mutableStateOf(false) }

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
            if (rejected) {
                Notice(stringResource(R.string.codex_approval_failed), tone = NoticeTone.Error)
            }
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

            // 按钮排布对齐 Claude 的权限面板：拒绝是判定（朱砂）在左，允许是主动作在右，
            // 各占一半；acceptForSession 不是二选一里的项，单独一行垫底
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InkButton(
                    onClick = { if (!onAnswer(CodexDecision.DECLINE)) rejected = true },
                    tone = InkButtonTone.Vermilion,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.codex_deny)) }
                InkButton(
                    onClick = { if (!onAnswer(CodexDecision.ACCEPT)) rejected = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.codex_approve)) }
            }

            if (allowsSession) {
                InkButton(
                    onClick = { if (!onAnswer(CodexDecision.ACCEPT_FOR_SESSION)) rejected = true },
                    tone = InkButtonTone.Paper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_approve_session)) }
            }
        }
    }
}

/** 连接方式。从会话页挪进 sheet —— 它是设一次就不再看的东西，不该常驻在会话上方 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodexConnectionSheet(
    profile: dev.min.code.core.settings.CodexProfile,
    onSave: (dev.min.code.core.settings.CodexProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember(profile.apiKey) { mutableStateOf(profile.apiKey) }
    var baseUrl by remember(profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var mode by remember(profile.authMode) { mutableStateOf(profile.authMode) }
    var model by remember(profile.model) { mutableStateOf(profile.model) }
    var effort by remember(profile.effort) { mutableStateOf(profile.effort) }

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
            // 模型 / 思考强度跟着连接走：thread/start 和每一轮 turn/start 都带上。
            // 空着 = 不传，用 Codex 自己的默认——不替用户猜模型
            InkTextField(
                value = model,
                onValueChange = { model = it },
                label = stringResource(R.string.codex_model),
                placeholder = "gpt-5.1-codex-max",
                singleLine = true,
                monospace = true,
            )
            Text(
                stringResource(R.string.codex_effort),
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InkChip(
                    label = stringResource(R.string.codex_effort_default),
                    selected = effort.isBlank(),
                    onClick = { effort = "" },
                )
                CODEX_EFFORT_LEVELS.forEach { level ->
                    InkChip(
                        label = level,
                        selected = effort == level,
                        onClick = { effort = level },
                        monospace = true,
                    )
                }
            }
            InkButton(
                onClick = {
                    onSave(
                        profile.copy(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            authMode = mode,
                            model = model.trim(),
                            effort = effort.trim(),
                        ),
                    )
                },
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
