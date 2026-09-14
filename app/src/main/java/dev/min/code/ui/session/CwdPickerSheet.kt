package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 在工作区里选一个文件夹当 CLI 的工作目录。
 *
 * 手机上没有桌面那种系统文件夹对话框，手填绝对路径又容易打错。
 * 这里直接浏览沙箱里已经存在的目录：点进去、选当前、也能新建。
 *
 * [asSheet] 为 true 时自己包一层底部面板（首页点路径用）；
 * false 时只画内容（会话设置的手风琴里已经有一张 sheet，再套一层会互相抢）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CwdPickerSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onList: suspend (String) -> Result<List<WorkspaceFileEntry>>,
    onCreate: (parent: String, name: String, onDone: (Result<String>) -> Unit) -> Unit,
    asSheet: Boolean = true,
) {
    if (asSheet) {
        InkSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CwdBrowser(
                current = current,
                onPick = onPick,
                onList = onList,
                onCreate = onCreate,
                titled = true,
                modifier = Modifier.height(480.dp),
            )
        }
    } else {
        CwdBrowser(
            current = current,
            onPick = onPick,
            onList = onList,
            onCreate = onCreate,
            titled = false,
            modifier = Modifier.height(360.dp),
        )
    }
}

@Composable
private fun CwdBrowser(
    current: String,
    onPick: (String) -> Unit,
    onList: suspend (String) -> Result<List<WorkspaceFileEntry>>,
    onCreate: (parent: String, name: String, onDone: (Result<String>) -> Unit) -> Unit,
    titled: Boolean,
    modifier: Modifier = Modifier,
) {
    val (startArea, startRelative) = CwdPath.split(current)
    var area by remember { mutableStateOf(startArea) }
    var relative by remember { mutableStateOf(startRelative) }
    val guest = CwdPath.guest(area, relative)
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(guest) {
        loading = true
        error = null
        onList(guest)
            .onSuccess { listed ->
                entries = listed.filter { it.isDirectory }
                loading = false
            }
            .onFailure { e ->
                entries = emptyList()
                error = e.message ?: "打不开这个目录"
                loading = false
            }
    }

    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (titled) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Seal()
                Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            "点进文件夹，再选当前这一层。Claude Code 会在这里读写文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        InkSegmented(
            options = listOf("文件", "Rootfs"),
            selected = if (area == WorkspaceStorageArea.FILES) 0 else 1,
            onSelect = { index ->
                area = if (index == 0) WorkspaceStorageArea.FILES else WorkspaceStorageArea.LINUX
                relative = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InkIconButton(
                icon = HugeIcons.ArrowTurnBackward,
                contentDescription = "上一级",
                onClick = { relative = relative.substringBeforeLast('/', missingDelimiterValue = "") },
                enabled = relative.isNotBlank(),
                size = 36.dp,
                iconSize = 18.dp,
            )
            Text(
                text = guest,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InkIconButton(
                icon = HugeIcons.Add01,
                contentDescription = "新建文件夹",
                onClick = { creating = true },
                size = 36.dp,
                iconSize = 18.dp,
            )
        }
        InkDivider()
        error?.let {
            Notice(
                text = it,
                tone = NoticeTone.Error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (!loading && entries.isEmpty() && error == null) {
                item {
                    Text(
                        "这里没有子文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                }
            }
            items(entries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { relative = entry.path }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.sea.seaDeep,
                    )
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        InkButton(
            onClick = { onPick(guest) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("使用当前目录")
        }
    }

    if (creating) {
        CreateFolderDialog(
            onDismiss = { creating = false },
            onConfirm = { name ->
                onCreate(guest, name) { result ->
                    result
                        .onSuccess {
                            relative = CwdPath.split(it).second
                            creating = false
                        }
                        .onFailure { error = it.message ?: "新建失败" }
                }
            },
        )
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val valid = CwdPath.folderName(name) != null
    InkDialog(
        onDismissRequest = onDismiss,
        title = "新建文件夹",
        confirmButton = {
            InkTextButton(
                onClick = { CwdPath.folderName(name)?.let(onConfirm) },
                enabled = valid,
            ) { Text("创建") }
        },
        dismissButton = {
            InkTextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        InkTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "名称",
            placeholder = "project",
            singleLine = true,
            monospace = true,
        )
    }
}

@Composable
internal fun CwdPathRow(
    cwd: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            cwd,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 预览 / 没接上仓库时给空表，点开选择器也不会崩 */
internal val EmptyCwdList: suspend (String) -> Result<List<WorkspaceFileEntry>> =
    { Result.success(emptyList()) }

internal val NoopCwdCreate: (String, String, (Result<String>) -> Unit) -> Unit =
    { _, _, done -> done(Result.failure(IllegalStateException("预览里不能新建"))) }
