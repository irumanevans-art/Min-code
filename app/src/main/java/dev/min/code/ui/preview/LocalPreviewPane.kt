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
import androidx.compose.runtime.DisposableEffect
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
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalUrls
import dev.min.code.util.openExternalUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Refresh01

/**
 * 本机 loopback **预览位**的展开面板。
 *
 * - 关掉 = 藏位（[onCollapse]），不是拆功能；WebView 由调用方在 url 仍绑定时可保活，
 *   本 composable 仅在 [url] 从树里卸掉时 destroy。
 * - 进程表「打开」与铬件预览键共用这一块，不另起第二套 WebView。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPreviewPane(
    url: String,
    onCollapse: () -> Unit,
) {
    val context = LocalContext.current
    val normalized = remember(url) { LocalUrls.normalizeLoopback(url) }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    var title by remember(normalized) { mutableStateOf(normalized) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 面板被卸掉（collapse 或离开会话）时再拆引擎；同一 url 再展开可新建，
    // 但绝不能在「只是藏」的路径上由外层把 url 清掉。
    DisposableEffect(normalized) {
        onDispose {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webView = null
        }
    }

    InkSheet(
        onDismissRequest = onCollapse,
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
                    contentDescription = stringResource(R.string.preview_collapse),
                    onClick = onCollapse,
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
                    update = { view ->
                        val current = view.url
                        if (current == null || current != normalized) {
                            view.loadUrl(normalized)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        // destroy 交给 DisposableEffect(normalized)，避免 collapse 重组时误拆
                        webView = null
                    },
                )
            }
        }
    }
}

/** @deprecated 用 [LocalPreviewPane]；保留别名避免外部引用炸编译 */
@Deprecated("Use LocalPreviewPane", ReplaceWith("LocalPreviewPane(url, onCollapse)"))
@Composable
fun LocalPreviewSheet(url: String, onDismiss: () -> Unit) {
    LocalPreviewPane(url = url, onCollapse = onDismiss)
}
