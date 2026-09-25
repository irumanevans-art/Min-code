package dev.min.code.ui.codex

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.files.toImportSource
import dev.min.code.ui.session.AttachmentChip
import dev.min.code.ui.session.ComposerCapsule
import dev.min.code.ui.session.ComposerChipStrip
import dev.min.code.ui.session.ComposerPlus
import dev.min.code.ui.session.FileMentionSuggestions
import dev.min.code.ui.session.LastNonNull
import dev.min.code.ui.session.SlashSuggestionRow
import dev.min.code.ui.theme.InkMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Codex 的输入坞。胶囊和 Claude 那边是同一只（[ComposerCapsule]）：两个引擎在屏幕上
 * 长一个样，差别只在谁在说话。这里只放 Codex 自己的那几样——排队的消息、本地斜杠命令、
 * 附件走 VM 导入。
 *
 * 正文由 VM 持有并落盘（草稿）：切页、杀进程再回来都还在，
 * threadId 换了 VM 会自己换档，这里不保管状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CodexComposer(
    draftState: State<String>,
    enabled: Boolean,
    busy: Boolean,
    queued: List<String>,
    attachments: List<CodexAttachment>,
    modelText: String,
    modeText: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Boolean,
    onTakeQueued: (Int) -> Unit,
    onImportFile: (String, java.io.InputStream, (Boolean) -> Unit) -> Unit,
    onRemoveAttachment: (CodexAttachment) -> Unit,
    onSearchFiles: suspend (String) -> List<String>,
    onSlash: (CodexSlash) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft by draftState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CodexAttachment?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        // 取文件名和开流都要过 ContentProvider —— 文档来自云盘时这一下能卡住几百毫秒，
        // 而这个回调本身跑在主线程上
        scope.launch {
            var remaining = uris.size
            uris.forEach { uri ->
                // 和文件页的导入同一个取名 + 开流（toImportSource），
                // 取不到显示名时 SAF 的 `primary:Download/a.txt` 也会被剥成 `a.txt`
                val (name, stream) = withContext(Dispatchers.IO) {
                    uri.toImportSource(context.contentResolver).let { it.name to it.open() }
                }
                if (stream == null) {
                    remaining -= 1
                    if (remaining == 0) importing = false
                    return@forEach
                }
                onImportFile(name, stream) {
                    remaining -= 1
                    if (remaining == 0) importing = false
                }
            }
        }
    }

    val slashMatches = remember(draft) { codexSlashMatches(draft) }
    // 候选列表收起的那几百毫秒里还要画得出内容，所以记住最后一份非空的
    val lastSlash = remember { LastNonNull(slashMatches.takeIf { it.isNotEmpty() }) }
    val shownSlash = lastSlash.update(slashMatches.takeIf { it.isNotEmpty() }).orEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        ComposerChipStrip(visible = attachments.isNotEmpty()) {
            attachments.forEach { att ->
                AttachmentChip(name = att.name, onRemove = { pendingDelete = att })
            }
        }
        // 候选摆在输入框上方：手指在下面打字，列表从上面长出来才不会被自己的手挡住
        AnimatedVisibility(
            visible = slashMatches.isNotEmpty(),
            enter = InkMotion.expand,
            exit = InkMotion.collapse,
        ) {
            Column {
                shownSlash.forEach { slash ->
                    SlashSuggestionRow(
                        command = slash.command,
                        description = stringResource(slash.labelRes()),
                        onClick = { onDraftChange(slash.command) },
                    )
                }
                InkDivider()
            }
        }
        FileMentionSuggestions(
            text = draft,
            enabled = enabled,
            onSearchFiles = onSearchFiles,
            onTextChange = onDraftChange,
            showDivider = true,
        )
        // 排着的消息摆在输入框**上方**而不是混进会话流：它们还没被模型看见，
        // 放进流里就成了「我说了话它没理我」。codex TUI 也是这么摆的。
        AnimatedVisibility(
            visible = queued.isNotEmpty(),
            enter = InkMotion.expand,
            exit = InkMotion.collapse,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    stringResource(R.string.codex_queued_hint, queued.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    queued.forEachIndexed { index, text ->
                        InkChip(
                            label = text.lineSequence().first().take(QUEUED_CHIP_CHARS),
                            selected = false,
                            onClick = { onTakeQueued(index) },
                        )
                    }
                }
            }
        }
        ComposerCapsule(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            busy = busy,
            // 只挂了附件、一个字没写也该能发 —— 那本身就是一句「看看这个」
            canSend = enabled && (draft.isNotBlank() || attachments.isNotEmpty()),
            onSend = {
                // 本地命令在这里下车，不进 turn/start：协议里没有斜杠命令，
                // 发过去只会变成给模型的一句话，白花一轮
                val slash = codexSlashTarget(draft)
                when {
                    slash != null -> { onSlash(slash); onDraftChange("") }
                    onSend(draft) -> onDraftChange("")
                }
            },
            // Codex 这边还没有接 turn/interrupt，胶囊里这一键停的是整个 app-server 进程
            // （和顶栏「停止」同一个动作）。接上之后换成只掐这一轮，和 Claude 的 Esc 对齐
            onInterrupt = onStop,
            plus = {
                // 和 Claude 同一个「+」：附件、会话设置（模型 · 权限 · 强度…）都收在这里。
                // 图片那两项暂不给 —— Codex 的 localImage 内容块还没接。
                // 用量那一栏也不给：thread/tokenUsage 报的是累计用量，不是此刻上下文占了多少，
                // 画成一圈会比真实的满；Codex 也不回报花费。没有的数就不画
                ComposerPlus(
                    enabled = enabled && !importing,
                    busy = importing,
                    canPickImage = false,
                    showImageItems = false,
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onPickImage = {},
                    onTakePhoto = {},
                    modelText = modelText,
                    modeText = modeText,
                    onOpenSettings = onOpenSettings,
                )
            },
        )
    }

    // 移除附件是真删文件，问一句。留在工作区里的话 Codex 照样 ls 得到、读得到，
    // 「我明明去掉了」和「它还是看见了」对不上才是更坏的结果
    pendingDelete?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.composer_delete_attachment_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                onRemoveAttachment(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        ) {
            Text(stringResource(R.string.composer_delete_attachment_body, target.name))
        }
    }
}

/** 排队 chip 上留几个字。一行装得下三四个，够认出是哪条 */
private const val QUEUED_CHIP_CHARS = 18

/** 命令旁边那句说明：打开会话设置的写它展开的那一栏。命令名本身不翻译，说明跟着界面语言走 */
private fun CodexSlash.labelRes(): Int = toSection()?.labelRes ?: R.string.codex_new_session
