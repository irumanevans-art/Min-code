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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
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
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 *
 * 整页就是一张纸：没有输入框的边框，光标是金的，文件名在顶栏用等宽（它是机器产物，不是标题）。
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 下面 LaunchedEffect / scope.launch 里取不了 stringResource，先在这儿取好
    val readError = stringResource(R.string.editor_err_read)
    val saveError = stringResource(R.string.editor_err_save)
    val savedMessage = stringResource(R.string.common_saved)
    val tooLargeTemplate = stringResource(R.string.workspace_err_too_large)

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure {
            loadError = if (it is FileTooLargeException) tooLargeTemplate.format(it.size)
            else it.message ?: readError
            loading = false
        }
    }

    Scaffold(
        topBar = {
            InkTopBar(
                title = fileName,
                titleMono = true,
                navigationIcon = { BackButton() },
                actions = {
                    if (editable && !loading && loadError == null) {
                        InkTextButton(
                            onClick = {
                                if (!saving) {
                                    saving = true
                                    scope.launch {
                                        runCatching {
                                            repository.writeText(
                                                id = id,
                                                path = path,
                                                text = textState.text.toString(),
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
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val phase = when {
            loading -> EditorPhase.Loading
            loadError != null -> EditorPhase.Error
            else -> EditorPhase.Content
        }
        // 读取 → 正文 / 出错：落墨进、飞白出，不是硬切
        AnimatedContent(
            targetState = phase,
            transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
            label = "editor",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { current ->
            when (current) {
                EditorPhase.Loading -> InkLoading(status = stringResource(R.string.editor_loading))

                EditorPhase.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Notice(text = loadError ?: readError, tone = NoticeTone.Error)
                }

                EditorPhase.Content -> InkTextArea(
                    state = textState,
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
}

private enum class EditorPhase { Loading, Error, Content }
