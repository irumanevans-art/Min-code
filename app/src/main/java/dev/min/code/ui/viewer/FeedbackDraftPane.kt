package dev.min.code.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.agent.spi.FeedbackDraft
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.theme.sea
import dev.min.code.ui.viewer.ViewerInbox
import dev.min.code.util.fileProviderUri
import dev.min.agent.tools.FeedbackBundle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「诊断包草稿」面板：列出模型起草、还没被用户决定的草稿。每一条能改正文、导出成 zip、或丢弃。
 *
 * 什么都不会自动发送：导出只在本机生成一个 zip 并打开系统分享面板，发给谁、发不发，由用户自己在分享面板里选。
 * 导出的 zip 由 [FeedbackBundle] 组成，凭证在那里去掉（并扫一遍成品）。
 *
 * 会话日志随草稿附带（内核排队时附上，见 [FeedbackDraft.sessionLog]）；
 * 诚实的缺口：凭证名单只有空的环境与设置，手机上没有把 key 的存储接进来，靠的是 sk-/gh_/Bearer 形状的字符串去除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDraftPane(
    drafts: List<PendingDraft>,
    onClose: () -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    InkSheet(
        onDismissRequest = onClose,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            Modifier.fillMaxWidth().height(screenH * 0.8f).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.viewer_draft_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.sea.ink,
            )
            Text(
                text = stringResource(R.string.viewer_draft_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.sea.ink,
            )
            drafts.forEach { item ->
                Spacer(Modifier.height(12.dp))
                DraftCard(item)
            }
        }
    }
}

@Composable
private fun DraftCard(item: PendingDraft) {
    val draft = item.draft
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 每条草稿自己的编辑缓冲：改了正文就替换收件箱里的那条，没改就原样
    var body by remember(item) { mutableStateOf(draft.body) }
    Column(Modifier.fillMaxWidth()) {
        Text(text = draft.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.sea.ink)
        InkTextField(
            value = body,
            onValueChange = { body = it },
            minLines = 3,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton(onClick = {
                val edited = draft.copy(body = body)
                if (edited != draft) ViewerInbox.replaceDraft(item.id, edited)
                scope.launch { exportBundle(context, edited) }
            }) { Text(stringResource(R.string.viewer_draft_export)) }
            InkButton(onClick = { ViewerInbox.discardDraft(item.id) }) {
                Text(stringResource(R.string.viewer_draft_discard))
            }
        }
    }
}

/** 导出：在缓存目录生成 zip（先清空），经 FileProvider 交给系统分享面板。只在用户点了导出时发生 */
private suspend fun exportBundle(context: Context, draft: FeedbackDraft) {
    val uri = withContext(Dispatchers.IO) {
        val bytes = FeedbackBundle.build(
            draft,
            sessionLog = draft.sessionLog,
            versionInfo = "Min ${BuildConfig.VERSION_NAME}",
            env = emptyMap(),
            settings = emptyMap(),
        )
        val dir = File(context.cacheDir, "diagnostics").apply {
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }
        val target = File(dir, "min-feedback-" + System.currentTimeMillis() + ".zip").apply { writeBytes(bytes) }
        context.fileProviderUri(target)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
