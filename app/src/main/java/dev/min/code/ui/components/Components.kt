package dev.min.code.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.theme.DarkSea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    InkIconButton(
        icon = HugeIcons.ArrowLeft01,
        contentDescription = "返回",
        onClick = { navController.popBackStack() },
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

/** 名字对齐 RikkaHub，搬来的页面不用改 */
@Composable
fun RikkaConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    text: @Composable () -> Unit,
) {
    if (!show) return
    InkDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            InkTextButton(
                onClick = onConfirm,
                tone = if (destructive) InkButtonTone.Vermilion else InkButtonTone.Quiet,
            ) { Text(confirmText) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(dismissText) } },
    ) {
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
    }
}

/** 看一张本地图片。全屏、墨色底、点右上角关。够用——这里不是相册 */
@Composable
fun ImagePreviewDialog(images: List<String>, onDismissRequest: () -> Unit) {
    val path = images.firstOrNull() ?: return
    // 解一张整图要几十到几百毫秒，摆在组合里就是一次主线程卡顿。挪到 IO 上，
    // null 表示「还在解」—— 和「解不出来」分开，否则每次打开都先闪一下错误文案
    val decoded by produceState<Result<android.graphics.Bitmap>?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) ?: error("decode returned null") }
        }
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(DarkSea.paper)) {
            val bitmap = decoded?.getOrNull()
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else if (decoded != null) {
                Text("无法解码图片", color = DarkSea.ink, modifier = Modifier.align(Alignment.Center))
            }
            InkIconButton(
                icon = HugeIcons.Cancel01,
                contentDescription = "关闭",
                onClick = onDismissRequest,
                tint = DarkSea.ink,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            )
        }
    }
}
