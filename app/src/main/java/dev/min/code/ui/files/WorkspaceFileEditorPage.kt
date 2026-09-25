package dev.min.code.ui.files

import dev.min.code.core.rootfs.FileTooLargeException
import dev.min.code.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkLoading
import dev.min.code.ui.components.InkTextArea
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 编辑器各壳（整页 / 弹窗）共享的一帧状态：正文文本 + 加载进度。
 * 读写动作分别走 [EditorLoadEffect] 和 [EditorSaveButton]，调用点全工程各只有一处。
 */
internal class EditorState {
    val text = TextFieldState()
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)
}

/**
 * 按当前文件把内容读进 [state.text]。读取（含过大文件报错）的调用全工程只此一处：
 * 页面壳和弹窗壳都从这里拿内容。
 */
@Composable
internal fun EditorLoadEffect(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
    state: EditorState,
) {
    val repository = koinInject<WorkspaceRepository>()
    // effect 里取不了 stringResource，先在这儿取好
    val readError = stringResource(R.string.editor_err_read)
    val tooLargeTemplate = stringResource(R.string.workspace_err_too_large)
    LaunchedEffect(id, area, path) {
        state.loading = true
        state.loadError = null
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            state.text.setTextAndPlaceCursorAtEnd(content)
            state.loading = false
        }.onFailure {
            state.loadError = if (it is FileTooLargeException) tooLargeTemplate.format(it.size)
            else it.message ?: readError
            state.loading = false
        }
    }
}

/**
 * 保存按钮。显隐（可编辑、已加载、无错）和可用都由这层判断；写文件的调用全工程只此一处。
 * 页面顶栏和弹窗头部摆的是同一个按钮。
 */
@Composable
internal fun EditorSaveButton(
    id: String,
    path: String,
    state: EditorState,
    editable: Boolean,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val saveError = stringResource(R.string.editor_err_save)
    val savedMessage = stringResource(R.string.common_saved)
    if (!editable || state.loading || state.loadError != null) return
    InkTextButton(
        onClick = {
            if (!saving) {
                saving = true
                scope.launch {
                    runCatching {
                        repository.writeText(
                            id = id,
                            path = path,
                            text = state.text.text.toString(),
                            overwrite = true,
                        )
                    }.onSuccess {
                        toaster.show(savedMessage, type = ToastType.Success)
                    }.onFailure {
                        toaster.show(it.message ?: saveError, type = ToastType.Error)
                    }
                    saving = false
                }
            }
        },
        enabled = !saving,
    ) {
        Text(if (saving) stringResource(R.string.editor_saving) else stringResource(R.string.common_save))
    }
}

/**
 * 编辑器本体：读取中 / 出错 / 正文三态。不含任何壳 —— 整页 [WorkspaceFileEditorPage]
 * 和工作区文件弹窗都在它外面包自己的头部。[editable] 由壳按 area 判断（LINUX 区只读）。
 */
@Composable
internal fun WorkspaceFileEditor(
    state: EditorState,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    val readError = stringResource(R.string.editor_err_read)
    val phase = when {
        state.loading -> EditorPhase.Loading
        state.loadError != null -> EditorPhase.Error
        else -> EditorPhase.Content
    }
    // 读取 → 正文 / 出错：落墨进、飞白出，不是硬切
    AnimatedContent(
        targetState = phase,
        transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
        label = "editor",
        modifier = modifier.fillMaxSize(),
    ) { current ->
        when (current) {
            EditorPhase.Loading -> InkLoading(status = stringResource(R.string.editor_loading))

            EditorPhase.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Notice(text = state.loadError ?: readError, tone = NoticeTone.Error)
            }

            EditorPhase.Content -> InkTextArea(
                state = state.text,
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                readOnly = !editable,
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 *
 * 整页就是一张纸：没有输入框的边框，光标是金的，文件名在顶栏用等宽（它是机器产物，不是标题）。
 * 壳只剩 Scaffold + 顶栏，读写和内容都在 [WorkspaceFileEditor] 一族里，供弹窗复用。
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val state = remember { EditorState() }
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    EditorLoadEffect(id, area, path, state)

    Scaffold(
        topBar = {
            InkTopBar(
                title = fileName,
                titleMono = true,
                navigationIcon = { BackButton() },
                actions = { EditorSaveButton(id = id, path = path, state = state, editable = editable) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        WorkspaceFileEditor(
            state = state,
            editable = editable,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private enum class EditorPhase { Loading, Error, Content }
