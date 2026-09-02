package dev.min.code.ui.components

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.min.code.ui.nav.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    IconButton(onClick = { navController.popBackStack() }, modifier = modifier) {
        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
    }
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
    text: @Composable () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/** 看一张本地图片。全屏、点右上角关。够用——这里不是相册 */
@Composable
fun ImagePreviewDialog(images: List<String>, onDismissRequest: () -> Unit) {
    val path = images.firstOrNull() ?: return
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("无法解码图片", modifier = Modifier.align(Alignment.Center))
            }
            IconButton(onClick = onDismissRequest, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(HugeIcons.Cancel01, contentDescription = "关闭")
            }
        }
    }
}

enum class ToastType { Normal, Success, Error }

/** 简单的 toast 出口，接口对齐搬来的页面里 `toaster.show(text, type)` 的用法 */
class Toaster(private val sink: (String) -> Unit) {
    fun show(message: String, type: ToastType = ToastType.Normal) = sink(message)
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("No Toaster provided") }

@Composable
fun rememberSystemToaster(): Toaster {
    val context = LocalContext.current
    return remember(context) { Toaster { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
}
