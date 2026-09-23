package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.files.WorkspaceDetailVM
import dev.min.code.ui.terminal.TerminalKeyboardToggle
import dev.min.code.ui.terminal.WorkspaceTerminalContent
import dev.min.code.ui.terminal.WorkspaceTerminalSessionManager
import dev.min.code.ui.terminal.WorkspaceTerminalTabsState
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalSkin
import dev.min.code.ui.theme.MinTheme
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.flow.flowOf
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PlusSign
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 会话页上的终端：盖在对话上升起来，返回键落下，人没离开会话。
 *
 * ## 它是什么、不是什么
 *
 * 是**同一个 rootfs 里另开的一个 bash** —— 和会话进程共用同一份 Linux、同一个
 * `/workspace` bind mount（见 `WorkspaceTerminalSession`），所以在这里改的文件就是
 * agent 下一步读到的文件。终端本体和独立终端页是同一份 [WorkspaceTerminalContent]。
 *
 * **不是** Claude 那个进程自己的控制终端。会话跑的是 `-p --output-format stream-json`
 * 无头模式，stdin/stdout 是 JSON 管道而不是 TTY，它根本没有终端画面可以镜像
 * （对话界面就是它的输出）。所以在这里按 Ctrl-C 停的是你自己敲的那条命令，
 * 停 agent 仍然走输入坞右下角那个键。
 *
 * 每个**在跑**的会话预开一个页签，落在那个会话自己的工作目录里；页签名写工作目录，
 * 几个会话同时开着时"哪个是哪个"只能靠它分辨。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeCodeTerminalSheet(
    liveSessions: List<ClaudeCodeSessionRegistry.LiveSession>,
    onDismiss: () -> Unit,
) {
    val vm: WorkspaceDetailVM = koinViewModel(
        parameters = { parametersOf(CLAUDE_CODE_WORKSPACE_ID.toString()) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionManager: WorkspaceTerminalSessionManager = koinInject()
    val root = state.workspace?.root
    val terminalStateFlow = remember(root, sessionManager) {
        root?.let(sessionManager::observeWorkspace) ?: flowOf(WorkspaceTerminalTabsState())
    }
    val terminalState by terminalStateFlow.collectAsStateWithLifecycle(
        initialValue = WorkspaceTerminalTabsState(),
    )
    // 设置读回来之前是 null：先不摆终端，免得按默认值弹一次键盘
    val autoKeyboard by sessionManager.autoShowKeyboard.collectAsStateWithLifecycle(initialValue = null)
    var pendingCloseTabId by remember(root) { mutableStateOf<Long?>(null) }

    // 在跑的会话各开一个页签。一个都没在跑（全是历史会话）时退回一个普通页签，
    // 否则点开终端只会看到一句"没有标签页"，等于这个按钮坏了
    val live = liveSessions.filter { it.isLive }
    LaunchedEffect(root, live.map { it.key }) {
        val currentRoot = root ?: return@LaunchedEffect
        if (live.isEmpty()) {
            sessionManager.ensureSession(currentRoot)
        } else {
            sessionManager.ensureSessionTabs(
                root = currentRoot,
                sessions = live.map {
                    WorkspaceTerminalSessionManager.SessionTab(
                        key = it.key,
                        cwd = it.cwd.ifBlank { null },
                        label = terminalTabLabel(it.cwd),
                    )
                },
            )
        }
    }

    // 终端永远是夜形态：浅底上的 ANSI 配色不可读，而且终端就该长这样。
    // 包住整个 InkSheet 而不只是内容 —— 只包内容的话，纸色的容器会在黑底外围镶一圈白边。
    MinTheme(mode = ThemeMode.DARK, style = LocalSkin.current.style) {
        InkSheet(
            onDismissRequest = onDismiss,
            // 终端要多少给多少，没有半展开那一档：半个终端读不了一行输出
            sheetState = rememberBottomSheetState(
                SheetValue.Hidden,
                setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.terminal_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.terminal_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                autoKeyboard?.let { TerminalKeyboardToggle(it, sessionManager::setAutoShowKeyboard) }
                // 新开一个标签是"人"的动作：金
                InkIconButton(
                    icon = HugeIcons.PlusSign,
                    contentDescription = stringResource(R.string.terminal_new_tab),
                    onClick = { root?.let(sessionManager::createTab) },
                    enabled = root != null && !terminalState.isCreating,
                    tint = MaterialTheme.sea.seaDeep,
                )
            }
            // ModalBottomSheet 的内容列高度是包裹的，fillMaxSize 会收成 0。
            // 终端必须先有一个确定的高度，TerminalView 才算得出行列数。
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            Box(Modifier.fillMaxWidth().height(screenHeight * TERMINAL_SHEET_HEIGHT_RATIO)) {
                autoKeyboard?.let { auto ->
                    WorkspaceTerminalContent(
                        root = root,
                        state = terminalState,
                        contentPadding = PaddingValues(0.dp),
                        onSelectTab = { tabId -> root?.let { sessionManager.selectTab(it, tabId) } },
                        onCloseTab = { tabId -> pendingCloseTabId = tabId },
                        autoShowKeyboard = auto,
                    )
                }
            }
        }
    }

    val pendingCloseTab = terminalState.tabs.firstOrNull { it.id == pendingCloseTabId }
    if (pendingCloseTab != null) {
        TerminalCloseTabDialog(
            label = pendingCloseTab.label ?: pendingCloseTab.number.toString(),
            onConfirm = {
                root?.let { sessionManager.closeTab(it, pendingCloseTab.id) }
                pendingCloseTabId = null
            },
            onDismiss = { pendingCloseTabId = null },
        )
    }
}

@Composable
private fun TerminalCloseTabDialog(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.terminal_close_title, label),
        confirmButton = {
            // 关掉会杀进程：判定，朱砂
            InkTextButton(onClick = onConfirm, tone = InkButtonTone.Vermilion) {
                Text(stringResource(R.string.terminal_close))
            }
        },
        dismissButton = {
            InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        Text(
            stringResource(R.string.terminal_close_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 终端占屏高的比例。留一线对话在上面，人才看得出这是盖上来的一层 */
private const val TERMINAL_SHEET_HEIGHT_RATIO = 0.82f

/**
 * 页签上写什么：工作目录相对 `/workspace` 的那一段。
 *
 * 不写完整路径 —— 每个会话的前缀都一样，手机宽度下真正有区分度的尾段会被挤没。
 * 就在工作区根上时写 `workspace`，写个空字符串等于没标签。
 */
internal fun terminalTabLabel(cwd: String): String {
    val relative = cwd.removePrefix("/workspace").trim('/')
    return relative.ifBlank { "workspace" }
}
