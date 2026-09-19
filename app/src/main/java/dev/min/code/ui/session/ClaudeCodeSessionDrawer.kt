package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.R
import dev.min.code.core.claudecode.groupSessionIds
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkMenuItem
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookmark02
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Tag01

/**
 * 会话列表侧边栏。
 *
 * 对话内容读官方 CLI 写在 Rootfs 里的 transcript；置顶 / 分类 / 人手改过的标题
 * 是 App 侧的元数据。删会话会同时清掉 transcript 和内部进程。
 *
 * 一张浅一阶的纸。当前会话是整栏里唯一一扇海的窗：一块圆角的海，字是纸白，右上角一枚「使用中」；
 * 进程还活着的打一粒在呼吸的海点；元信息一律等宽字。
 * 会话列表是主体，占中间全部高度；「新建」挂在题跋右侧，文件 / 终端 / 环境 / 设置
 * 这些入口收在底部一组。
 */
@Composable
fun ClaudeCodeSessionDrawer(
    sessions: List<ClaudeCodeVM.SessionEntry>,
    permanent: Boolean = false,
    /** 宽屏分栏时由外层拖动手势决定；null 则退回 300.dp */
    paneWidth: Dp? = null,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onPinSession: (String, Boolean) -> Unit = { _, _ -> },
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onSetCategory: (String, String?) -> Unit = { _, _ -> },
    categories: List<String> = emptyList(),
    onOpenFiles: () -> Unit = {},
    onOpenCodex: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenRuntime: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCopyTranscript: (() -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<ClaudeCodeVM.SessionEntry?>(null) }
    var pendingRename by remember { mutableStateOf<ClaudeCodeVM.SessionEntry?>(null) }
    var pendingCategory by remember { mutableStateOf<ClaudeCodeVM.SessionEntry?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(sessions, searchQuery) { filterSessionEntries(sessions, searchQuery) }
    val groups = remember(filtered) {
        groupSessionIds(
            ids = filtered.map { it.id },
            pinned = filtered.map { it.pinned },
            category = filtered.map { it.category },
        )
    }
    val byId = remember(filtered) { filtered.associateBy { it.id } }
    val searching = searchQuery.isNotBlank()

    val scheme = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            ) {
                SectionTitle(
                    text = stringResource(R.string.session_list_title),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            InkIconButton(
                                icon = HugeIcons.Search01,
                                contentDescription = if (searchOpen || searching) {
                                    stringResource(R.string.session_list_close_search)
                                } else {
                                    stringResource(R.string.session_list_open_search)
                                },
                                onClick = {
                                    if (searchOpen || searching) {
                                        searchOpen = false
                                        searchQuery = ""
                                    } else {
                                        searchOpen = true
                                    }
                                },
                                size = 32.dp,
                                iconSize = 18.dp,
                            )
                            InkIconButton(
                                icon = HugeIcons.PlusSign,
                                contentDescription = stringResource(R.string.session_list_new),
                                onClick = onNewSession,
                                size = 32.dp,
                                iconSize = 18.dp,
                            )
                        }
                    },
                )
            }

            AnimatedVisibility(visible = searchOpen || searching) {
                SessionListSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = {
                        searchOpen = false
                        searchQuery = ""
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                )
            }

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.session_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.session_list_no_match, searchQuery.trim()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    groups.forEach { group ->
                        if (group.header != null) {
                            // key 用组的结构性身份：用户分类可以和内置组头重名，
                            // 从显示文案派生 key 会撞成 duplicate key 崩掉列表
                            item(key = "h-${group.key}") {
                                Text(
                                    group.header,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetbrainsMono,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(group.items, key = { it }) { id ->
                            val s = byId[id] ?: return@items
                            SessionRow(
                                entry = s,
                                onClick = { onOpenSession(s.id) },
                                onPin = { onPinSession(s.id, !s.pinned) },
                                onRename = { pendingRename = s },
                                onCategorize = { pendingCategory = s },
                                onDelete = { pendingDelete = s },
                            )
                        }
                    }
                }
            }

            InkDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            // 手机上逐段拖选很难受，而 SelectionContainer 已经占掉了长按手势，
            // 没法再给每条挂一个"复制本条"。整段导出放在这里作为兜底。
            onCopyTranscript?.let { ActionRow(HugeIcons.Copy01, stringResource(R.string.session_copy_transcript), it) }
            // 另一个引擎，和工作区、终端一样是"换个地方干活"，不是一条设置项 ——
            // 埋在设置页里的话，想用 Codex 的人得先想到去翻设置
            ActionRow(HugeIcons.AiBrain01, stringResource(R.string.codex_title), onOpenCodex)
            ActionRow(HugeIcons.Folder01, stringResource(R.string.session_drawer_files), onOpenFiles)
            ActionRow(HugeIcons.ComputerTerminal01, stringResource(R.string.session_terminal), onOpenTerminal)
            ActionRow(HugeIcons.Globe, stringResource(R.string.runtime_drawer), onOpenRuntime)
            // CLI 装完之后安装向导就从 UI 上消失了，更新入口只能挂在这儿
            ActionRow(HugeIcons.Package, stringResource(R.string.session_drawer_maintenance), onOpenMaintenance)
            ActionRow(HugeIcons.Settings02, stringResource(R.string.settings_title), onOpenSettings)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (permanent) {
        PermanentDrawerSheet(
            modifier = Modifier.width(paneWidth ?: 300.dp),
            drawerContainerColor = scheme.surfaceContainerLow,
            drawerContentColor = scheme.onSurface,
            content = { body() },
        )
    } else {
        ModalDrawerSheet(
            drawerContainerColor = scheme.surfaceContainerLow,
            drawerContentColor = scheme.onSurface,
            drawerShape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
            content = { body() },
        )
    }

    pendingDelete?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.session_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                onDeleteSession(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        ) {
            Text(stringResource(R.string.session_delete_body))
        }
    }
    pendingRename?.let { target ->
        RenameDialog(
            current = target.title,
            onConfirm = { name ->
                onRenameSession(target.id, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }
    pendingCategory?.let { target ->
        CategoryDialog(
            current = target.category,
            existing = categories,
            onConfirm = { name ->
                onSetCategory(target.id, name)
                pendingCategory = null
            },
            onDismiss = { pendingCategory = null },
        )
    }
}

/**
 * 会话列表搜索条：标题 / 分类 / id / 正文。打开就聚焦，和文件页那条同款。
 */
@Composable
private fun SessionListSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = stringResource(R.string.session_list_search),
            singleLine = true,
            leading = {
                Icon(
                    HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        InkIconButton(
            icon = HugeIcons.Cancel01,
            contentDescription = stringResource(R.string.session_list_close_search),
            onClick = onClose,
            size = 32.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SessionRow(
    entry: ClaudeCodeVM.SessionEntry,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onCategorize: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.sea
    var menu by remember { mutableStateOf(false) }
    // 当前会话 = 一扇海的窗淡入
    val active by animateFloatAsState(
        targetValue = if (entry.isActive) 1f else 0f,
        animationSpec = InkMotion.effect(),
        label = "sessionActive",
    )
    val titleColor by animateColorAsState(
        targetValue = if (entry.isActive) palette.onSea else scheme.onSurface,
        animationSpec = InkMotion.effect(),
        label = "sessionTitle",
    )
    val metaColor by animateColorAsState(
        targetValue = if (entry.isActive) palette.onSea.copy(alpha = 0.78f) else scheme.onSurfaceVariant,
        animationSpec = InkMotion.effect(),
        label = "sessionMeta",
    )
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape),
    ) {
        if (active > 0.001f) {
            Box(Modifier.matchParentSize().seaFill(shape, alpha = active))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 进程还活着的打个点：切过去是秒切，不用重启也不会丢上下文
            if (entry.isLive) {
                LiveDot(if (entry.isActive) palette.onSea else palette.sea)
            } else {
                Spacer(Modifier.size(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(visible = entry.isActive, enter = InkMotion.enter, exit = InkMotion.exit) {
                        InUseBadge()
                    }
                }
                // 元信息一行是拼出来的，先把要用到的几个词取出来：buildString 里不好取资源
                val pinnedText = stringResource(R.string.session_meta_pinned)
                val countText = stringResource(R.string.session_meta_messages, entry.messageCount)
                val liveText = stringResource(R.string.session_meta_live)
                Text(
                    text = buildString {
                        if (entry.pinned) append(pinnedText)
                        if (!entry.category.isNullOrBlank() && !entry.pinned) {
                            if (isNotEmpty()) append("  ")
                            append(entry.category)
                        }
                        if (entry.updatedAt != Long.MAX_VALUE) {
                            if (isNotEmpty()) append("  ")
                            append(formatTime(entry.updatedAt))
                        }
                        if (entry.messageCount > 0) {
                            if (isNotEmpty()) append("  ")
                            append(countText)
                        }
                        if (entry.isLive) {
                            if (isNotEmpty()) append("  ")
                            append(liveText)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = metaColor,
                    maxLines = 1,
                )
            }
            Box {
                InkIconButton(
                    icon = HugeIcons.MoreHorizontal,
                    contentDescription = stringResource(R.string.session_row_actions),
                    onClick = { menu = true },
                    tint = if (entry.isActive) palette.onSea.copy(alpha = 0.8f) else scheme.outline,
                    size = 32.dp,
                    iconSize = 14.dp,
                )
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = scheme.surfaceContainerLow,
                ) {
                    InkMenuItem(
                        stringResource(if (entry.pinned) R.string.session_unpin else R.string.session_pin),
                        icon = if (entry.pinned) HugeIcons.PinOff else HugeIcons.Pin,
                        onClick = { menu = false; onPin() },
                    )
                    InkMenuItem(
                        stringResource(R.string.common_rename),
                        icon = HugeIcons.Edit02,
                        onClick = { menu = false; onRename() },
                    )
                    InkMenuItem(
                        stringResource(R.string.session_category),
                        icon = HugeIcons.Tag01,
                        onClick = { menu = false; onCategorize() },
                    )
                    InkMenuItem(
                        stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete02,
                        tint = palette.vermilion,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        if (entry.pinned && !entry.isActive) {
            Icon(
                HugeIcons.Bookmark02,
                contentDescription = null,
                tint = palette.seaDeep.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 36.dp, top = 4.dp)
                    .size(10.dp),
            )
        }
    }
}

@Composable
internal fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.common_rename),
        confirmButton = {
            InkTextButton(
                onClick = { onConfirm(value) },
                enabled = value.trim().isNotBlank(),
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        InkTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.session_rename_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(
    current: String?,
    existing: List<String>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current.orEmpty()) }
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.session_category),
        confirmButton = {
            InkTextButton(onClick = { onConfirm(value.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        InkTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.session_category_label),
            placeholder = stringResource(R.string.session_category_hint),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (existing.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                existing.forEach { name ->
                    InkChip(
                        label = name,
                        selected = value == name,
                        onClick = { value = name },
                    )
                }
            }
        }
    }
}

/** 海上那枚「使用中」：纸白的一小块玻璃 */
@Composable
private fun InUseBadge() {
    Text(
        stringResource(R.string.session_in_use),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
        color = MaterialTheme.sea.onSea,
        maxLines = 1,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 活着的点：缓慢呼吸。动画值只在绘制阶段读，不引起重组；
 * 系统动画关掉时是一粒静止的点。
 */
@Composable
private fun LiveDot(color: Color) {
    val breath: State<Float>? = if (rememberAnimationsEnabled()) {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = InkMotion.Ease),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    } else {
        null
    }
    androidx.compose.foundation.Canvas(Modifier.size(6.dp)) {
        drawCircle(color.copy(alpha = breath?.value ?: 1f))
    }
}

/** 同一支 formatter 复用：SimpleDateFormat 构造不便宜，会话多时每行每次重组新建是纯浪费。
 *  只在主线程（Compose UI）调用，无并发问题 */
private val drawerTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = drawerTimeFormat.format(Date(millis))
