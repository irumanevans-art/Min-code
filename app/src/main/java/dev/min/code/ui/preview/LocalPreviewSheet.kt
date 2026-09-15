package dev.min.code.ui.preview

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.min.code.R
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalUrls
import dev.min.code.util.openExternalUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Refresh01

/**
 * 本机 loopback 预览。默认路径：App 内 WebView，不是系统浏览器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPreviewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val normalized = remember(url) { LocalUrls.normalizeLoopback(url) }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    var title by remember(normalized) { mutableStateOf(normalized) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    InkSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(screenH * 0.92f),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.sea.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InkIconButton(
                    icon = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.preview_reload),
                    onClick = { webView?.reload() },
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                InkIconButton(
                    icon = HugeIcons.Link01,
                    contentDescription = stringResource(R.string.preview_open_external),
                    onClick = { context.openExternalUrl(normalized) },
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                InkIconButton(
                    icon = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.preview_close),
                    onClick = onDismiss,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        @SuppressLint("SetJavaScriptEnabled")
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(AndroidColor.WHITE)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, t: String?) {
                                    if (!t.isNullOrBlank()) title = t
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    return if (LocalUrls.isLoopbackHttp(u)) {
                                        false
                                    } else {
                                        context.openExternalUrl(u)
                                        true
                                    }
                                }
                            }
                            loadUrl(normalized)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        it.stopLoading()
                        it.destroy()
                        webView = null
                    },
                )
            }
            InkTextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.preview_close))
            }
        }
    }
}
