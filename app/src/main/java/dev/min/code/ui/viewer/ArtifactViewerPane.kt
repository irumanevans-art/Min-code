package dev.min.code.ui.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.min.agent.spi.PreviewFile
import dev.min.code.R
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.fileProviderUri
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Share08

/**
 * Artifact 的本机预览面板：HTML 在离线 WebView 里打开，Markdown 用 App 自己的渲染器。
 *
 * HTML 的安全设置全在 [ArtifactViewerPolicy]（纯配置，有单测）：不给 origin、不读文件、不连网、不留 DOM storage。
 * 面板里的分享把文件写进缓存目录再经 FileProvider 交给系统分享面板——只在用户点了分享时才发生，没有任何自动发送。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactViewerPane(
    file: PreviewFile,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val heading = file.title ?: file.name

    InkSheet(
        onDismissRequest = onClose,
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(Modifier.fillMaxWidth().height(screenH * 0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.sea.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InkIconButton(
                    icon = HugeIcons.Share08,
                    contentDescription = stringResource(R.string.viewer_artifact_share),
                    onClick = { scope.launch { shareArtifact(context, file) } },
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                InkIconButton(
                    icon = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.viewer_artifact_close),
                    onClick = onClose,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
            }
            Box(Modifier.fillMaxSize()) {
                if (file.mime == "text/markdown") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        MarkdownBlock(content = file.text)
                    }
                } else {
                    // key(file)：换一个文件就新建 WebView，旧页面的脚本与状态不带过去
                    key(file) { SandboxedHtml(file.text) }
                }
            }
        }
    }
}

/** 离线 WebView：按 [ArtifactViewerPolicy] 配置，不给 origin，子请求只放行 data: 与 about: */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedHtml(html: String) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.WHITE)
                val s = ArtifactViewerPolicy.settings
                settings.javaScriptEnabled = s.javaScriptEnabled
                settings.domStorageEnabled = s.domStorageEnabled
                settings.allowFileAccess = s.allowFileAccess
                settings.allowContentAccess = s.allowContentAccess
                settings.allowFileAccessFromFileURLs = s.allowFileAccessFromFileUrls
                settings.allowUniversalAccessFromFileURLs = s.allowUniversalAccessFromFileUrls
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    // 子请求（图片、脚本、样式、字体、连接）只放行内联数据：其它一律空 403，网络与本机文件都碰不到
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return blocked()
                        return if (ArtifactViewerPolicy.allowRequest(url)) null else blocked()
                    }

                    // 页面里的导航一律不走：只放行同文档的锚点（data:/about:），其它链接不在面板里打开
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return !ArtifactViewerPolicy.allowRequest(url)
                    }
                }
                loadDataWithBaseURL(ArtifactViewerPolicy.BASE_URL, html, "text/html", "utf-8", null)
                webView = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** 分享：写进缓存目录（先清空，不长期占空间），经 FileProvider 交给系统分享面板。只在用户点了分享时调用 */
private suspend fun shareArtifact(context: android.content.Context, file: PreviewFile) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "artifacts").apply {
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }
        val target = File(dir, file.name).apply { writeText(file.text, Charsets.UTF_8) }
        context.fileProviderUri(target)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
