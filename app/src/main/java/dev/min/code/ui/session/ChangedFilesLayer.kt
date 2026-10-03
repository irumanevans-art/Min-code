package dev.min.code.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.session.ChangedFile
import dev.min.code.core.session.combineDiffs
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.richtext.DiffView
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FilePlus
import me.rerere.hugeicons.stroke.FileRemove
import kotlin.math.abs

/**
 * 会话底部「改动过的文件」浮层。
 *
 * ## 为什么不用 [dev.min.code.ui.components.InkSheet]
 *
 * 全 App 的底部面板都从 `InkSheet` 出，但它是 `ModalBottomSheet`：有遮罩、盖住会话流、
 * 按返回键会关。这里要的恰好相反 —— 被改的文件与模型刚才那句话是同一件事的两面，
 * 遮住一半就没法对着看。所以这是一个**非模态**的自绘浮层：收起时是底栏上方的一条细条，
 * 展开时从底部浮起、可拖高度，背后不加遮罩，会话流照旧能滚。
 *
 * 视觉仍与 sheet 同族：纸底、上圆角、海色提手，动效全部走 [InkMotion]。
 *
 * ## 高度三档
 *
 * 收起（只剩一行把手）/ 半屏 / 大屏，拖动跟手、松手吸附最近一档。吸附规则是纯函数
 * [nearestSnap]：手机的拖拽手感没法自动化验收，但「拖到这里该落到哪一档」可以，那一半必须被测到。
 */
@Composable
internal fun ChangedFilesLayer(
    files: List<ChangedFile>,
    undoneIds: Set<String>,
    labels: ChangedFilesLabels,
    onJumpTo: (String) -> Unit,
    onUndo: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = files.isNotEmpty(),
        enter = InkMotion.rise,
        exit = InkMotion.sink,
        modifier = modifier,
    ) {
        ChangedFilesPanel(files, undoneIds, labels, onJumpTo, onUndo)
    }
}

@Composable
private fun ChangedFilesPanel(
    files: List<ChangedFile>,
    undoneIds: Set<String>,
    labels: ChangedFilesLabels,
    onJumpTo: (String) -> Unit,
    onUndo: (List<String>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 拖拽中的高度（dp）；NaN = 没在拖，用吸附值
    var dragHeightDp by remember { mutableFloatStateOf(Float.NaN) }
    var dragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val (added, removed) = remember(files, undoneIds) { files.totals(undoneIds) }
    // 收起时只剩把手那一行：内容整体收掉，列表不参与测量
    var handleHeightDp by remember { mutableFloatStateOf(COLLAPSED_HEIGHT_DP.value) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val halfDp = maxHeight * PANEL_HALF_FRACTION
        val tallDp = maxHeight * PANEL_TALL_FRACTION
        val settledDp = if (expanded) (if (openPath != null) tallDp else halfDp) else COLLAPSED_HEIGHT_DP
        val targetDp = if (dragging && !dragHeightDp.isNaN()) dragHeightDp.dp else settledDp
        val animatedHeight by animateDpAsState(
            targetValue = targetDp,
            animationSpec = if (dragging) snap() else InkMotion.spatial(),
            label = "changed-files-height",
        )

        Column(
            Modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.sea.paper),
        ) {
            // ---- 把手：整行可点开 / 收起；纵向拖拽调高度 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(halfDp, tallDp) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragHeightDp = animatedHeight.value
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val deltaDp = -dragAmount / density.density
                                val cur = if (dragHeightDp.isNaN()) animatedHeight.value else dragHeightDp
                                dragHeightDp = (cur + deltaDp).coerceIn(COLLAPSED_HEIGHT_DP.value, tallDp.value)
                            },
                            onDragEnd = {
                                dragging = false
                                val landed = nearestSnap(dragHeightDp, halfDp.value, tallDp.value)
                                expanded = landed > COLLAPSED_HEIGHT_DP.value + 1f
                                if (!expanded) openPath = null
                                dragHeightDp = Float.NaN
                            },
                            onDragCancel = {
                                dragging = false
                                dragHeightDp = Float.NaN
                            },
                        )
                    }
                    .clickable {
                        expanded = !expanded
                        if (!expanded) openPath = null
                    }
                    .padding(horizontal = 12.dp)
                    .height(COLLAPSED_HEIGHT_DP),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    HugeIcons.FileEdit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.sea.seaDeep,
                )
                Text(
                    text = labels.title.format(files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                DiffCounts(added, removed)
                Spacer(Modifier.weight(1f))
                ChevronRotating(expanded)
            }

            if (animatedHeight > COLLAPSED_HEIGHT_DP) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    AnimatedContent(
                        targetState = openPath,
                        transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
                        label = "changed-files-page",
                    ) { path ->
                        val file = files.firstOrNull { it.path == path }
                        if (file == null) {
                            FileList(files, undoneIds, labels, onOpen = { openPath = it })
                        } else {
                            FileDetail(
                                file = file,
                                undoneIds = undoneIds,
                                labels = labels,
                                onBack = { openPath = null },
                                onJumpTo = onJumpTo,
                                onUndo = onUndo,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文件清单：一行一个文件，点进单文件 diff */
@Composable
private fun FileList(
    files: List<ChangedFile>,
    undoneIds: Set<String>,
    labels: ChangedFilesLabels,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(files, key = { it.path }) { file ->
            val (added, removed) = file.totals(undoneIds)
            val undone = file.changes.all { it.itemId in undoneIds }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(file.path) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChangeGlyph(file, undone)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetbrainsMono,
                        color = if (undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = file.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (file.changes.size > 1) {
                    Text(
                        text = labels.times.format(file.changes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (undone) {
                    Text(
                        text = labels.undone,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DiffCounts(added, removed)
                }
            }
        }
    }
}

/** 单个文件：多段 diff 接成一段 + 跳到改动的那一轮 + 撤销这个文件的全部改动 */
@Composable
private fun FileDetail(
    file: ChangedFile,
    undoneIds: Set<String>,
    labels: ChangedFilesLabels,
    onBack: () -> Unit,
    onJumpTo: (String) -> Unit,
    onUndo: (List<String>) -> Unit,
) {
    val live = file.changes.filterNot { it.itemId in undoneIds }
    val diff = remember(file, undoneIds) {
        combineDiffs(live.mapNotNull { it.diff }) { index -> labels.diffSeparator.format(index + 1) }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            InkTextButton(onClick = onBack, icon = HugeIcons.ArrowLeft01) { Text(labels.back) }
        }
        // 跳到改动的那一轮：改过多次时给最近一次——要看的是「刚才改了什么」
        live.lastOrNull()?.let { latest ->
            Text(
                text = labels.jumpToChange,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.sea.seaDeep,
                modifier = Modifier.clickable { onJumpTo(latest.itemId) }.padding(vertical = 4.dp),
            )
        }
        if (diff.isBlank()) {
            Text(
                text = labels.noDiff,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item { DiffView(diff, maxLines = 400, showFileHeader = live.size == 1) }
            }
        }
        HorizontalDivider(color = MaterialTheme.sea.rule)
        val undoIds = live.mapNotNull { it.toolUseId }
        if (undoIds.isNotEmpty()) {
            // 从最后一次往前逐个还原（store 的 restoreAll 自己会再逆序一次，这里按会话顺序给）
            InkTextButton(onClick = { onUndo(undoIds) }, icon = HugeIcons.ArrowTurnBackward) {
                Text(labels.undoFile.format(file.name))
            }
        } else {
            Text(
                text = labels.cannotUndo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/** 新建 / 删除 / 改过三种图标，与工具卡同一套图标语言 */
@Composable
private fun ChangeGlyph(file: ChangedFile, undone: Boolean) {
    val change = file.changes.last()
    val tint = when {
        undone -> MaterialTheme.colorScheme.onSurfaceVariant
        change.created -> MaterialTheme.sea.bamboo
        change.deleted -> MaterialTheme.sea.vermilion
        else -> MaterialTheme.sea.seaDeep
    }
    val icon = when {
        change.created -> HugeIcons.FilePlus
        change.deleted -> HugeIcons.FileRemove
        else -> HugeIcons.FileEdit
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
}

/** `+12 −3`：增是竹青、删是朱砂，与 [DiffView] 同一套物质 */
@Composable
private fun DiffCounts(added: Int, removed: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (added > 0) {
            Text(
                text = "+$added",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.sea.bamboo,
            )
        }
        if (removed > 0) {
            Text(
                text = "−$removed",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.sea.vermilion,
            )
        }
    }
}

@Composable
private fun ChevronRotating(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = InkMotion.spatial(),
        label = "changed-files-chevron",
    )
    Icon(
        HugeIcons.ArrowDown01,
        contentDescription = null,
        modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun List<ChangedFile>.totals(undoneIds: Set<String>): Pair<Int, Int> =
    fold(0 to 0) { (a, r), file ->
        val (fa, fr) = file.totals(undoneIds)
        (a + fa) to (r + fr)
    }

/** 收起态的高度：只剩一行把手 */
internal val COLLAPSED_HEIGHT_DP: Dp = 34.dp

/** 展开后占可用高度的比例：常态一半稍弱、向上拖到底 78% */
private const val PANEL_HALF_FRACTION = 0.42f
private const val PANEL_TALL_FRACTION = 0.78f

/**
 * 松手后落到哪一档：离得最近的那档。三档之间的距离并不等分（收起 → 半屏往往比
 * 半屏 → 大屏远得多），所以按距离取最近，而不是按拖动方向决定。纯函数，可单测。
 */
internal fun nearestSnap(heightDp: Float, halfDp: Float, tallDp: Float): Float {
    val collapsed = COLLAPSED_HEIGHT_DP.value
    val candidates = listOf(collapsed, halfDp, tallDp).filter { it >= collapsed - 0.5f }
    return candidates.minByOrNull { abs(it - heightDp) } ?: collapsed
}

/** 浮层里要用的界面文案 */
internal data class ChangedFilesLabels(
    val title: String,
    val times: String,
    val undone: String,
    val back: String,
    val jumpToChange: String,
    val noDiff: String,
    val cannotUndo: String,
    val undoFile: String,
    val diffSeparator: String,
)

@Composable
internal fun rememberChangedFilesLabels(): ChangedFilesLabels = ChangedFilesLabels(
    title = stringResource(R.string.changed_files_title),
    times = stringResource(R.string.changed_files_times),
    undone = stringResource(R.string.changed_files_undone),
    back = stringResource(R.string.changed_files_back),
    jumpToChange = stringResource(R.string.changed_files_jump),
    noDiff = stringResource(R.string.changed_files_no_diff),
    cannotUndo = stringResource(R.string.changed_files_cannot_undo),
    undoFile = stringResource(R.string.changed_files_undo_file),
    diffSeparator = stringResource(R.string.changed_files_diff_separator),
)
