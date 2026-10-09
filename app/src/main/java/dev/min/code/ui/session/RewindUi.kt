package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.engine.RewindPreviewView
import dev.min.code.core.engine.RewindScopeView
import dev.min.code.core.session.ChatItem
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

/**
 * 回退在会话流里的两个动作：长按用户消息「回到这里」、折叠记录里「撤销这次回退」。
 * 用 CompositionLocal 而不是逐层传参：会话流的渲染（块 → 条目 → 子 agent 视图）有好几层、Codex 页也在用，
 * 没提供时（Codex、CC）条目不响应长按、折叠记录不给撤销钮。
 */
internal data class RewindActions(val onRewind: (messageId: String) -> Unit, val onUndoRestore: (restoreId: String) -> Unit)

internal val LocalRewindActions = staticCompositionLocalOf<RewindActions?> { null }

/**
 * 折叠记录（[ChatItem.Folded]）：一行标题，点开看逐行说明与原样条目（只读、淡化）。
 * 展开的原样条目不用会话流那套带轨道的条目：它们已经在一条轨道的条目里面，再嵌一层轨道会画出两条竖线。
 * 所以用每种条目最朴素的一行写法，够认出「那段被回退的对话说了什么、调了什么」。
 */
@Composable
internal fun FoldedEntry(
    item: ChatItem.Folded,
    isFirst: Boolean,
    isLast: Boolean,
    labels: TranscriptLabels,
    @Suppress("UNUSED_PARAMETER") onRevert: ((String) -> Unit)?,
    @Suppress("UNUSED_PARAMETER") canRevert: (String) -> Boolean,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val actions = LocalRewindActions.current
    val expandable = item.lines.isNotEmpty() || item.nested.isNotEmpty() || (item.undoId != null && actions != null)
    TranscriptEntry(marker = RailMarker.Dash, isFirst = isFirst, isLast = isLast, tone = RailTone.Ink) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (expandable) {
                androidx.compose.material3.Icon(
                    HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.lines.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.nested.isNotEmpty()) {
                    Column(Modifier.alpha(0.65f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.nested.forEach { NestedLine(it, labels) }
                    }
                }
                val undo = item.undoId
                if (undo != null && actions != null) {
                    InkButton(onClick = { actions.onUndoRestore(undo) }, tone = InkButtonTone.Paper, compact = true) {
                        Text(stringResource(R.string.rewind_undo), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 展开的折叠记录里的一条原样条目：每种一行朴素写法（见 [FoldedEntry]） */
@Composable
private fun NestedLine(item: ChatItem, labels: TranscriptLabels) {
    when (item) {
        is ChatItem.UserText -> Text("› ${item.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sea.ink)
        is ChatItem.AssistantText -> Text(item.text, style = MaterialTheme.typography.bodySmall)
        is ChatItem.ToolCall -> Text(
            "· ${item.name} ${toolSummary(item.name, item.input, labels)}",
            style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ChatItem.Note -> Text(item.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is ChatItem.AgentReport -> Text(
            "· ${item.agentType} · ${item.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ChatItem.Thinking, is ChatItem.ProcessOutput, is ChatItem.Folded -> Unit
    }
}

/**
 * 回退确认框。照 [RewindPreviewView] 写：要恢复 / 删除哪些文件、哪些撤不回、哪些是外部改的、会停掉哪些子 agent、
 * 目标点在压缩之前；范围三选一（差异为空时「代码」「两者」置灰，只给「对话」）。
 * 回退之后文件与对话都会变，所以必须点「回退」才动；返回键 / 点外面都只是取消。
 */
@Composable
internal fun RewindSheet(preview: RewindPreviewView, working: Boolean, onConfirm: (RewindScopeView) -> Unit, onDismiss: () -> Unit) {
    var scope by remember(preview.messageId) {
        mutableStateOf(if (preview.canRestoreCode) RewindScopeView.Both else RewindScopeView.Conversation)
    }
    var showFiles by remember(preview.messageId) { mutableStateOf(false) }
    CardSheet(onDismiss = { if (!working) onDismiss() }, dismissible = !working) {
        CardTitle(stringResource(R.string.rewind_title))
        Text(
            preview.prompt,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            modifier = Modifier.clip(MaterialTheme.shapes.small).padding(vertical = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip(stringResource(R.string.rewind_scope_code), scope == RewindScopeView.Code, { scope = RewindScopeView.Code }, enabled = preview.canRestoreCode && !working)
            InkChip(stringResource(R.string.rewind_scope_conversation), scope == RewindScopeView.Conversation, { scope = RewindScopeView.Conversation }, enabled = !working)
            InkChip(stringResource(R.string.rewind_scope_both), scope == RewindScopeView.Both, { scope = RewindScopeView.Both }, enabled = preview.canRestoreCode && !working)
        }
        Text(
            stringResource(
                when (scope) {
                    RewindScopeView.Code -> R.string.rewind_scope_code_desc
                    RewindScopeView.Conversation -> R.string.rewind_scope_conversation_desc
                    RewindScopeView.Both -> R.string.rewind_scope_both_desc
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 会动文件的范围才列文件
        if (scope != RewindScopeView.Conversation) {
            if (!preview.canRestoreCode) {
                Text(stringResource(R.string.rewind_files_none), style = MaterialTheme.typography.bodySmall)
            } else {
                if (preview.restore.isNotEmpty()) Text(stringResource(R.string.rewind_files_restore, preview.restore.size), style = MaterialTheme.typography.bodyMedium)
                if (preview.delete.isNotEmpty()) Text(stringResource(R.string.rewind_files_delete, preview.delete.size), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (showFiles) R.string.rewind_files_hide else R.string.rewind_files_show),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.ink,
                    modifier = Modifier.clickable { showFiles = !showFiles },
                )
                if (showFiles) {
                    (preview.restore.map { "↺ $it" } + preview.delete.map { "✕ $it" }).forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!preview.hasCheckpoint) Warn(stringResource(R.string.rewind_no_checkpoint))
            if (preview.unrestorable.isNotEmpty()) Warn(stringResource(R.string.rewind_unrestorable, listed(preview.unrestorable)))
            if (preview.external.isNotEmpty()) Warn(stringResource(R.string.rewind_external, listed(preview.external)))
            if (preview.symlinks.isNotEmpty()) Warn(stringResource(R.string.rewind_symlinks, preview.symlinks.size))
        }
        val stopped = preview.stoppedBy(scope)
        if (stopped.isNotEmpty()) Warn(stringResource(R.string.rewind_stops_agents, stopped.size, stopped.joinToString("、")))
        if (scope != RewindScopeView.Code && preview.compaction) Warn(stringResource(R.string.rewind_compaction))
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = onDismiss, modifier = Modifier.weight(1f), tone = InkButtonTone.Paper, enabled = !working) {
                Text(stringResource(R.string.rewind_cancel))
            }
            InkButton(onClick = { onConfirm(scope) }, modifier = Modifier.weight(1f), busy = working) {
                Text(stringResource(R.string.rewind_confirm))
            }
        }
    }
}

/** 「撤销这次回退」的确认：要说清它只覆盖快照照得到的文件 */
@Composable
internal fun UndoRestoreSheet(working: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CardSheet(onDismiss = { if (!working) onDismiss() }, dismissible = !working) {
        CardTitle(stringResource(R.string.rewind_undo_title))
        Text(stringResource(R.string.rewind_undo_note), style = MaterialTheme.typography.bodyMedium)
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = onDismiss, modifier = Modifier.weight(1f), tone = InkButtonTone.Paper, enabled = !working) {
                Text(stringResource(R.string.rewind_cancel))
            }
            InkButton(onClick = onConfirm, modifier = Modifier.weight(1f), busy = working) {
                Text(stringResource(R.string.rewind_undo_confirm))
            }
        }
    }
}

@Composable
private fun Warn(text: String) = Notice(text, tone = NoticeTone.Warn)

/** 路径清单最多列几个：再多就是一面墙，确认框读不下去 */
private const val LISTED_PATHS = 6

private fun listed(paths: List<String>): String =
    paths.take(LISTED_PATHS).joinToString("、") + if (paths.size > LISTED_PATHS) " …（${paths.size}）" else ""

/** 对话框里正在发生什么（[SessionExtras.rewindDialog]） */
internal sealed interface RewindDialog {
    data class Loading(val messageId: String) : RewindDialog
    data class Preview(val view: RewindPreviewView, val working: Boolean = false) : RewindDialog
    data class Undo(val restoreId: String, val working: Boolean = false) : RewindDialog
}

/** 回退对话框的唯一摆放处（在 [SessionExtrasOverlays] 里调用，排在信任 / 批准之后） */
@Composable
internal fun RewindOverlay(extras: SessionExtras) {
    val dialog by extras.rewindDialog.collectAsStateWithLifecycle()
    when (val d = dialog) {
        null, is RewindDialog.Loading -> Unit
        is RewindDialog.Preview -> RewindSheet(d.view, d.working, onConfirm = extras::confirmRewind, onDismiss = extras::dismissRewind)
        is RewindDialog.Undo -> UndoRestoreSheet(d.working, onConfirm = extras::confirmUndo, onDismiss = extras::dismissRewind)
    }
}

/** 设置里「检查点」段：每个工作区的快照仓库占多少，各有一个「清理」。进入这一段时读一次（遍历目录），不轮询 */
@Composable
internal fun CheckpointsSection(rows: List<dev.min.code.core.engine.RewindStorageRow>, onClear: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 清理是删光一个工作区的全部快照、没有撤销：先问一句
    var confirming by remember { mutableStateOf<dev.min.code.core.engine.RewindStorageRow?>(null) }
    dev.min.code.ui.components.RikkaConfirmDialog(
        show = confirming != null,
        title = stringResource(R.string.checkpoints_clean_title, confirming?.label.orEmpty()),
        confirmText = stringResource(R.string.checkpoints_clean),
        dismissText = stringResource(R.string.rewind_cancel),
        onConfirm = { confirming?.let { onClear(it.id) }; confirming = null },
        onDismiss = { confirming = null },
    ) { Text(stringResource(R.string.checkpoints_note)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.checkpoints_none), style = MaterialTheme.typography.bodyMedium)
        }
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, fontFamily = JetbrainsMono)
                    Text(
                        android.text.format.Formatter.formatShortFileSize(context, row.bytes),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InkButton(onClick = { confirming = row }, tone = InkButtonTone.Paper, compact = true) {
                    Text(stringResource(R.string.checkpoints_clean), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(stringResource(R.string.checkpoints_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun checkpointsSummary(rows: List<dev.min.code.core.engine.RewindStorageRow>): String {
    if (rows.isEmpty()) return stringResource(R.string.checkpoints_none)
    val context = androidx.compose.ui.platform.LocalContext.current
    return stringResource(R.string.checkpoints_total, android.text.format.Formatter.formatShortFileSize(context, rows.sumOf { it.bytes }), rows.size)
}
