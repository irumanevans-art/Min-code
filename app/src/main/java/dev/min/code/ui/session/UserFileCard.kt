package dev.min.code.ui.session

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.agent.spi.UserFileDisplay
import dev.min.agent.tools.SendUserFileTool
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PreviewFile
import dev.min.code.ui.viewer.ViewerInbox
import dev.min.code.R
import dev.min.code.core.engine.min.SentFileRow
import dev.min.code.core.engine.min.prefersView
import dev.min.code.core.engine.min.sentFileRows
import dev.min.code.core.engine.min.userFileIntent
import dev.min.code.core.engine.min.userFilesRoot
import dev.min.code.core.session.ChatItem
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.files.fileKindOfName
import dev.min.code.ui.files.icon
import dev.min.code.ui.files.tint
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.util.fileProviderUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Share08

/**
 * 模型发给用户的文件（SendUserFile）在对话流里的卡片：说明（有就写）+ 每个文件一行（图标、文件名、大小），
 * 点行打开（render 与图片 / 文本 / PDF 直接看，其余交给分享面板），右边的分享键总是走分享面板。
 *
 * 和通知读同一份缓存（[userFilesRoot]），所以不论人当时在不在这一页，回来翻到这里都打得开；
 * 缓存被系统清掉之后行还在、大小那里写「已不在本机缓存」，不给一个点了会失败的按钮。
 * 标记用空心方：和跑完的工具、子 agent 报告同一个符号——它是一次已经完成的交付。
 */
@Composable
internal fun SentFilesEntry(item: ChatItem.SentFiles, isFirst: Boolean, isLast: Boolean) {
    val context = LocalContext.current
    // null = 还没去缓存里看过：先只画名字，免得一闪「已不在」
    var rows by remember(item.stageKey, item.names) { mutableStateOf<List<SentFileRow>?>(null) }
    LaunchedEffect(item.stageKey, item.names) {
        rows = withContext(Dispatchers.IO) { sentFileRows(userFilesRoot(context), item.stageKey, item.names) }
    }
    TranscriptEntry(marker = RailMarker.SquareOutline, isFirst = isFirst, isLast = isLast) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.caption ?: stringResource(R.string.user_file_card_title, item.names.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.names.forEachIndexed { index, name ->
                SentFileLine(name, rows?.getOrNull(index), loaded = rows != null, preferView = item.preferView)
            }
        }
    }
}

@Composable
private fun SentFileLine(name: String, row: SentFileRow?, loaded: Boolean, preferView: Boolean?) {
    val context = LocalContext.current
    val file = row?.file
    val kind = fileKindOfName(name)
    PaperCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = file?.let { f -> { openUserFile(context, f, name, view = prefersView(displayOf(preferView), SendUserFileTool.mimeOf(name))) } },
        padding = PaddingValues(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = kind.tint())
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                AnimatedVisibility(visible = loaded, enter = InkMotion.enter, exit = InkMotion.exit) {
                    Text(
                        row?.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                            ?: stringResource(R.string.user_file_gone),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InkIconButton(
                icon = HugeIcons.Share08,
                contentDescription = stringResource(R.string.common_share),
                onClick = { file?.let { openUserFile(context, it, name, view = false) } },
                enabled = file != null,
                size = 36.dp,
                iconSize = 16.dp,
            )
        }
    }
}

private fun displayOf(preferView: Boolean?): UserFileDisplay? = when (preferView) {
    true -> UserFileDisplay.Render
    false -> UserFileDisplay.Attach
    null -> null
}

/**
 * 交给系统：打开或分享，一律走选择器（和通知同一种 Intent，见 [userFileIntent]）。
 * 例外：打开 HTML / Markdown 而系统里没有能看它的应用（Markdown 常见）时，退回 App 自己的预览槽——
 * 与 Artifact 同一个离线查看器（不联网、不读文件），里面有分享；不然选择器只会写「没有应用能执行此操作」
 */
private fun openUserFile(context: Context, file: File, name: String, view: Boolean) {
    val mime = SendUserFileTool.mimeOf(name)
    val intent = userFileIntent(context.fileProviderUri(file), mime, view)
    if (view && mime in IN_APP_PREVIEW_MIMES && intent.resolveActivity(context.packageManager) == null && file.length() <= IN_APP_PREVIEW_MAX_BYTES) {
        ViewerInbox.showArtifact(PreviewFile(name, GuestPath(file.path), mime, file.readText(), null))
        return
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 预览槽能显示的两种（与 Artifact 工具一致） */
private val IN_APP_PREVIEW_MIMES = setOf("text/html", "text/markdown")

/** 点开时在界面线程上读进内存，限 2 MB 免得卡顿（Artifact 工具自己的上限是 8 MB）；更大的仍交给系统 */
private const val IN_APP_PREVIEW_MAX_BYTES = 2L * 1024 * 1024
