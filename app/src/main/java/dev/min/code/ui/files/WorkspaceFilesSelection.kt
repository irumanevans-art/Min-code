package dev.min.code.ui.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.settings.WorkspaceOpenMode
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkMenuItem
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.ExternalLink
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImage
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.FolderImport
import me.rerere.hugeicons.stroke.Move01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 多选时替换底部标签栏的那条动作条。
 *
 * 纸 + 一条界线，没有海拔、没有阴影 —— 它和列表是同一张纸的两段，不是浮起来的工具条。
 */
@Composable
fun WorkspaceSelectionBar(
    count: Int,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = count > 0
    Column(modifier = modifier.fillMaxWidth()) {
        InkDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(HugeIcons.FileExport, stringResource(R.string.common_export), enabled, onExport)
            SelectionAction(HugeIcons.Move01, stringResource(R.string.common_move), enabled, onMove)
            SelectionAction(HugeIcons.Share08, stringResource(R.string.common_share), enabled, onShare)
            SelectionAction(
                icon = HugeIcons.Delete01,
                label = stringResource(R.string.common_delete),
                enabled = enabled,
                onClick = onDelete,
                tint = MaterialTheme.sea.vermilion,
            )
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * 「打开方式」。默认路由仍是文件类型说了算，这张 sheet 只是让人当场改主意、或者永久改主意。
 */
@Composable
fun WorkspaceOpenWithSheet(
    entry: WorkspaceFileEntry,
    onDismiss: () -> Unit,
    onPick: (WorkspaceOpenMode, Boolean) -> Unit,
) {
    val available = remember(entry.path, entry.sizeBytes) { availableOpenModes(entry) }
    val disabled = remember(entry.path, entry.sizeBytes) { editorDisabledReason(entry) }
    val canRemember = remember(entry.path) { canRememberOpenMode(entry) }
    var rememberChoice by remember { mutableStateOf(false) }

    InkSheet(onDismissRequest = onDismiss) {
        SheetBody {
            SectionTitle(stringResource(R.string.workspace_detail_open_with))
            Text(
                text = entry.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            OpenModeRow(
                mode = WorkspaceOpenMode.EDITOR,
                icon = HugeIcons.FileEdit,
                label = stringResource(R.string.workspace_detail_open_with_editor),
                reason = if (disabled == OpenDisable.TOO_LARGE) {
                    stringResource(R.string.workspace_detail_open_with_too_large)
                } else {
                    null
                },
                enabled = WorkspaceOpenMode.EDITOR in available,
                onPick = { onPick(it, rememberChoice) },
            )
            OpenModeRow(
                mode = WorkspaceOpenMode.IMAGE,
                icon = HugeIcons.FileImage,
                label = stringResource(R.string.workspace_detail_open_with_image),
                reason = null,
                enabled = WorkspaceOpenMode.IMAGE in available,
                onPick = { onPick(it, rememberChoice) },
            )
            OpenModeRow(
                mode = WorkspaceOpenMode.EXTERNAL,
                icon = HugeIcons.ExternalLink,
                label = stringResource(R.string.workspace_detail_open_with_external),
                reason = null,
                enabled = true,
                onPick = { onPick(it, rememberChoice) },
            )

            if (canRemember) {
                InkDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InkCheckbox(checked = rememberChoice, onCheckedChange = null)
                    Text(
                        text = stringResource(
                            R.string.workspace_detail_open_with_remember,
                            entry.extension(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

@Composable
private fun OpenModeRow(
    mode: WorkspaceOpenMode,
    icon: ImageVector,
    label: String,
    reason: String?,
    enabled: Boolean,
    onPick: (WorkspaceOpenMode) -> Unit,
) {
    if (!enabled && reason == null) return
    Column(modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        InkMenuItem(
            text = label,
            icon = icon,
            onClick = { if (enabled) onPick(mode) },
        )
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
            )
        }
    }
}

/** 导入：文件 / 文件夹 / .zip 三条路，各自说清楚代价 */
@Composable
fun WorkspaceImportSheet(
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onZip: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkSheet(onDismissRequest = onDismiss) {
        SheetBody {
            SectionTitle(stringResource(R.string.workspace_detail_import))
            InkMenuItem(
                text = stringResource(R.string.workspace_detail_import_files),
                icon = HugeIcons.FileImport,
                onClick = onFiles,
            )
            InkMenuItem(
                text = stringResource(R.string.workspace_detail_import_folder),
                icon = HugeIcons.FolderImport,
                onClick = onFolder,
            )
            ChoiceHint(stringResource(R.string.workspace_detail_import_folder_desc))
            InkMenuItem(
                text = stringResource(R.string.workspace_detail_import_zip),
                icon = HugeIcons.FileZip,
                onClick = onZip,
            )
            ChoiceHint(stringResource(R.string.workspace_detail_import_zip_desc))
        }
    }
}

/** 导出：打包成一个 zip，还是复制到用户选的文件夹。两条路的差别写在下面一行小字里 */
@Composable
fun WorkspaceExportSheet(
    onArchive: () -> Unit,
    onTree: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkSheet(onDismissRequest = onDismiss) {
        SheetBody {
            SectionTitle(stringResource(R.string.workspace_detail_export_title))
            InkMenuItem(
                text = stringResource(R.string.workspace_detail_export_archive),
                icon = HugeIcons.FileZip,
                onClick = onArchive,
            )
            ChoiceHint(stringResource(R.string.workspace_detail_export_archive_desc))
            InkMenuItem(
                text = stringResource(R.string.workspace_detail_export_tree),
                icon = HugeIcons.FolderImport,
                onClick = onTree,
            )
            ChoiceHint(stringResource(R.string.workspace_detail_export_tree_desc))
        }
    }
}

@Composable
private fun ChoiceHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 36.dp, bottom = 2.dp),
    )
}

/**
 * 批量操作的进度 / 结果。跑的时候不让点外面关掉 —— 手一滑就以为取消了、其实还在写。
 */
@Composable
fun WorkspaceBulkProgressSheet(
    progress: WorkspaceBulkProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkSheet(
        onDismissRequest = { if (progress.finished) onDismiss() },
        dismissible = progress.finished,
    ) {
        SheetBody {
            SectionTitle(
                when (progress.kind) {
                    WorkspaceBulkProgress.Kind.EXPORT_ZIP -> stringResource(R.string.workspace_detail_bulk_exporting)
                    WorkspaceBulkProgress.Kind.EXPORT_TREE -> stringResource(R.string.workspace_detail_bulk_copying)
                    WorkspaceBulkProgress.Kind.IMPORT_FILES,
                    WorkspaceBulkProgress.Kind.IMPORT_ZIP,
                    WorkspaceBulkProgress.Kind.IMPORT_TREE,
                    -> stringResource(R.string.workspace_detail_bulk_importing)

                    WorkspaceBulkProgress.Kind.DELETE -> stringResource(R.string.workspace_detail_bulk_deleting)
                    WorkspaceBulkProgress.Kind.MOVE -> stringResource(R.string.workspace_detail_bulk_moving)
                }
            )

            AnimatedContent(
                targetState = progress.finished,
                transitionSpec = {
                    fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast())
                },
                label = "bulk",
            ) { finished ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!finished) {
                        InkLineProgress(
                            progress = if (progress.total > 0) {
                                (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                            } else {
                                null
                            },
                        )
                        Text(
                            text = if (progress.total > 0) {
                                stringResource(
                                    R.string.workspace_detail_bulk_progress,
                                    progress.done,
                                    progress.total,
                                )
                            } else {
                                stringResource(R.string.workspace_detail_bulk_counting, progress.done)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress.current.isNotBlank()) {
                            Text(
                                text = progress.current,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            InkTextButton(
                                onClick = onCancel,
                                tone = InkButtonTone.Vermilion,
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    } else {
                        Text(
                            text = if (progress.cancelled) {
                                stringResource(R.string.workspace_detail_bulk_cancelled)
                            } else {
                                stringResource(
                                    R.string.workspace_detail_bulk_done,
                                    progress.done,
                                    progress.bytesDone.fileSizeToString(),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                        )
                        if (progress.skipped > 0) {
                            Text(
                                text = stringResource(R.string.workspace_detail_bulk_skipped, progress.skipped),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (progress.failures.isNotEmpty()) {
                            Notice(
                                text = buildString {
                                    append(
                                        stringResource(
                                            R.string.workspace_detail_bulk_failed,
                                            progress.failures.size,
                                        )
                                    )
                                    // 失败最多列五条：再多也只是同一个原因刷屏
                                    progress.failures.take(5).forEach { append('\n').append(it) }
                                },
                                tone = NoticeTone.Error,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            InkTextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.common_confirm))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [InkSheet] 自己不给横向内边距（`MoveSheet` 之类是各自垫的）。这一层把留白和纵向节奏收在一处，
 * 免得每张 sheet 的标题都贴到屏幕边上。
 */
@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
