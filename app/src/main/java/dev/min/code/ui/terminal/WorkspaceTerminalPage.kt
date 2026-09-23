package dev.min.code.ui.terminal

import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.min.code.R
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.EmptyState
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkLoading
import dev.min.code.ui.components.InkTabIndicator
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.files.WorkspaceDetailVM
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalSkin
import dev.min.code.ui.theme.MinTheme
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.flow.flowOf
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.PlusSign
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionManager: WorkspaceTerminalSessionManager = koinInject()
    val root = state.workspace?.root
    val terminalStateFlow = remember(root, sessionManager) {
        root?.let(sessionManager::observeWorkspace) ?: flowOf(WorkspaceTerminalTabsState())
    }
    val terminalState by terminalStateFlow.collectAsStateWithLifecycle(
        initialValue = WorkspaceTerminalTabsState(),
    )
    var pendingCloseTabId by remember(root) { mutableStateOf<Long?>(null) }
    // 设置读回来之前是 null：先不摆终端，免得按默认值弹一次键盘
    val autoKeyboard by sessionManager.autoShowKeyboard.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(root) {
        root?.let { sessionManager.ensureSession(it) }
    }

    // 终端永远是夜形态：浅底上的 ANSI 配色不可读，而且终端就该长这样。
    // 但**风格照旧跟着全局走** —— 强制的只有昼夜这一维，不是整套配色
    MinTheme(mode = ThemeMode.DARK, style = LocalSkin.current.style) {
        Scaffold(
            topBar = {
                InkTopBar(
                    title = state.workspace?.name?.let { stringResource(R.string.workspace_terminal_title_with_name, it) }
                        ?: stringResource(R.string.workspace_terminal_title),
                    navigationIcon = { BackButton() },
                    actions = {
                        autoKeyboard?.let { TerminalKeyboardToggle(it, sessionManager::setAutoShowKeyboard) }
                        val newTabDescription = stringResource(R.string.workspace_terminal_new_tab)
                        // 新开一个标签是"人"的动作：金
                        InkIconButton(
                            icon = HugeIcons.PlusSign,
                            contentDescription = newTabDescription,
                            onClick = {
                                root?.let { currentRoot ->
                                    sessionManager.createTab(currentRoot)
                                }
                            },
                            enabled = root != null && !terminalState.isCreating,
                            tint = MaterialTheme.sea.seaDeep,
                        )
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            autoKeyboard?.let { auto ->
                WorkspaceTerminalContent(
                    root = root,
                    state = terminalState,
                    contentPadding = innerPadding,
                    onSelectTab = { tabId ->
                        root?.let { sessionManager.selectTab(it, tabId) }
                    },
                    onCloseTab = { tabId ->
                        pendingCloseTabId = tabId
                    },
                    autoShowKeyboard = auto,
                )
            }
        }

        val pendingCloseTab = terminalState.tabs.firstOrNull { it.id == pendingCloseTabId }
        if (pendingCloseTab != null) {
            InkDialog(
                onDismissRequest = { pendingCloseTabId = null },
                title = stringResource(
                    R.string.workspace_terminal_close_confirm_title,
                    pendingCloseTab.number,
                ),
                confirmButton = {
                    // 关掉会杀进程：判定，朱砂
                    InkTextButton(
                        onClick = {
                            root?.let { sessionManager.closeTab(it, pendingCloseTab.id) }
                            pendingCloseTabId = null
                        },
                        tone = InkButtonTone.Vermilion,
                    ) {
                        Text(stringResource(R.string.workspace_terminal_close))
                    }
                },
                dismissButton = {
                    InkTextButton(onClick = { pendingCloseTabId = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            ) {
                Text(
                    stringResource(R.string.workspace_terminal_close_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private enum class TerminalPhase { Loading, Empty, Content }

/**
 * 终端顶栏上「打开时自动弹键盘」的开关。独立页和会话页的终端浮层共用。
 * 开着是海色、关着是淡墨；图标看不出是开是关，所以每次切换都用一句 toast 说清现在是哪样。
 */
@Composable
internal fun TerminalKeyboardToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val on = stringResource(R.string.terminal_auto_keyboard_on)
    val off = stringResource(R.string.terminal_auto_keyboard_off)
    InkIconButton(
        icon = HugeIcons.Keyboard,
        contentDescription = if (enabled) on else off,
        onClick = {
            onToggle(!enabled)
            Toast.makeText(context, if (enabled) off else on, Toast.LENGTH_SHORT).show()
        },
        tint = if (enabled) {
            MaterialTheme.sea.seaDeep
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
    )
}

/**
 * 终端本体：页签条 + 一块 [TerminalView]。独立页（[WorkspaceTerminalPage]）和会话页上那个
 * 浮层（`ClaudeCodeTerminalSheet`）共用这一份 —— 两处必须是同一个终端，
 * 不然"从哪儿点开的"会变成两种体验。
 */
@Composable
internal fun WorkspaceTerminalContent(
    root: String?,
    state: WorkspaceTerminalTabsState,
    contentPadding: PaddingValues,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    /** 打开时自动弹键盘；关掉就等用户点终端再弹（[TerminalKeyboardToggle]） */
    autoShowKeyboard: Boolean,
) {
    val phase = when {
        root != null && state.tabs.isNotEmpty() -> TerminalPhase.Content
        root == null || state.isCreating || state.readiness == WorkspaceTerminalReadiness.Loading -> TerminalPhase.Loading
        else -> TerminalPhase.Empty
    }
    // 等待 / 空 / 终端 三态之间只做淡入淡出：终端本体是 AndroidView，不能缩放它
    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast()) },
        label = "terminal",
        modifier = Modifier.fillMaxSize(),
    ) { current ->
        when (current) {
            TerminalPhase.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                InkLoading(status = stringResource(R.string.workspace_terminal_loading))
            }

            TerminalPhase.Empty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                EmptyState(
                    title = stringResource(
                        if (state.readiness == WorkspaceTerminalReadiness.NotInstalled) R.string.workspace_terminal_not_installed
                        else R.string.workspace_terminal_no_tabs
                    ),
                    intensity = 0.35f,
                )
            }

            TerminalPhase.Content -> {
                // 退场动画期间标签可能已经清空了：那就什么都不画，别去索引一个空列表
                val selectedIndex = state.tabs.indexOfFirst { it.id == state.selectedTabId }
                    .takeIf { it >= 0 }
                    ?: 0
                val selectedTab = state.tabs.getOrNull(selectedIndex) ?: return@AnimatedContent

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .imePadding(),
                    // 终端本体永远压在纯黑上：ANSI 配色是按黑底设计的
                    color = MaterialTheme.sea.paper,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            edgePadding = 0.dp,
                            minTabWidth = 64.dp,
                            indicator = { InkTabIndicator(Modifier.tabIndicatorOffset(selectedIndex)) },
                            divider = { InkDivider() },
                        ) {
                            state.tabs.forEach { tab ->
                                val tabDescription = stringResource(
                                    R.string.workspace_terminal_tab,
                                    tab.number,
                                )
                                // 不用 Material 的 Tab：它写死了水波纹。一个 selectable 的盒子 + 墨晕就够
                                val selected = selectedTab.id == tab.id
                                val labelColor by animateColorAsState(
                                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    InkMotion.effect(),
                                    label = "tabLabel",
                                )
                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .selectable(
                                            selected = selected,
                                            role = Role.Tab,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = LocalIndication.current,
                                            onClick = { onSelectTab(tab.id) },
                                        )
                                        .padding(start = 14.dp, end = 4.dp)
                                        .semantics {
                                            contentDescription = tabDescription
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        // 标签号是机器给的：等宽。给会话开的页签写它的工作目录，
                                        // 几个会话同时开着时"哪个是哪个"只能靠这个分辨
                                        Text(
                                            text = tab.label ?: tab.number.toString(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = JetbrainsMono,
                                            color = labelColor,
                                            modifier = Modifier.widthIn(max = 120.dp),
                                        )
                                        val closeDescription = stringResource(
                                            R.string.workspace_terminal_close_tab,
                                            tab.number,
                                        )
                                        InkIconButton(
                                            icon = HugeIcons.Cancel01,
                                            contentDescription = closeDescription,
                                            onClick = { onCloseTab(tab.id) },
                                            size = 28.dp,
                                            iconSize = 12.dp,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        WorkspaceTerminalTabContent(
                            tab = selectedTab,
                            autoShowKeyboard = autoShowKeyboard,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTerminalTabContent(
    tab: WorkspaceTerminalTab,
    autoShowKeyboard: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient = remember(tab.id) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    DisposableEffect(tab.id, viewClient) {
        onDispose {
            if (tab.client.terminalView === viewClient.terminalView) {
                tab.client.terminalView = null
            }
            viewClient.terminalView = null
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                        setBackgroundColor(AndroidColor.rgb(17, 23, 29))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(terminalTextSizePx)
                        setTypeface(terminalTypeface)
                        setTerminalViewClient(viewClient)
                        attachSession(tab.session)
                        tab.client.terminalView = this
                        viewClient.terminalView = this
                        setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                viewClient.focusAndShowKeyboard()
                            }
                            false
                        }
                        if (autoShowKeyboard) {
                            post { viewClient.focusAndShowKeyboard() }
                        }
                    }
                },
                update = { terminalView ->
                    terminalView.isFocusable = true
                    terminalView.isFocusableInTouchMode = true
                    terminalView.setTextSize(terminalTextSizePx)
                    terminalView.setBackgroundColor(AndroidColor.rgb(17, 23, 29))
                    terminalView.setTypeface(terminalTypeface)
                    terminalView.setTerminalViewClient(viewClient)
                    tab.client.terminalView = terminalView
                    viewClient.terminalView = terminalView
                    terminalView.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            viewClient.focusAndShowKeyboard()
                        }
                        false
                    }
                    terminalView.attachSession(tab.session)
                    terminalView.onScreenUpdated()
                },
            )
            if (tab.finished) {
                Text(
                    text = stringResource(R.string.workspace_terminal_exited),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TerminalExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = { controlDown = !controlDown },
            onAltToggle = { altDown = !altDown },
            onSendText = { tab.session.writeText(it) },
        )
    }
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    val palette = MaterialTheme.sea
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.paper2)
            .drawBehind {
                drawLine(
                    palette.seaFoam.copy(alpha = 0.42f),
                    Offset(0f, 0f),
                    Offset(size.width * 0.58f, 0f),
                    1.dp.toPx(),
                )
                drawLine(
                    palette.sea.copy(alpha = 0.28f),
                    Offset(size.width * 0.58f, 0f),
                    Offset(size.width, 0f),
                    1.dp.toPx(),
                )
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

/**
 * 附加键：一块纸片，按住的修饰键（CTRL / ALT）变成金（夜形态的 primary）。
 * 按下微缩、落墨，和全 App 的按钮同一套手感。
 */
@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        if (selected) scheme.primary else scheme.surfaceContainerHigh,
        InkMotion.effect(),
        label = "key",
    )
    val foreground by animateColorAsState(
        if (selected) scheme.onPrimary else scheme.onSurface,
        InkMotion.effect(),
        label = "keyText",
    )
    Text(
        text = label,
        modifier = Modifier
            .pressScale(interaction, 0.94f)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = JetbrainsMono,
        color = foreground,
    )
}

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}
