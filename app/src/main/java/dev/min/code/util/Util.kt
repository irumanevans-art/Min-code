package dev.min.code.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "Util"

/**
 * App 私有文件的可分享 URI。authority 以前在三四个地方各写一份字面量，
 * 改一次 applicationId 就得全找一遍 —— 收在这里。
 */
fun Context.fileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun Context.writeClipboardText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
    }.onFailure {
        Log.e(TAG, "writeClipboardText failed", it)
        Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
    }
}

fun Long.fileSizeToString(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val precision = when {
        value >= 100 -> 0
        value >= 10 -> 1
        else -> 2
    }
    return "%.${precision}f %s".format(value, units[unitIndex])
}

@Composable
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}

@Composable
fun TextUnit.toDp(): Dp = with(LocalDensity.current) {
    // Density.toDp(TextUnit) 仅支持 Sp 单位，Em/Unspecified 会抛 "Only Sp can convert to Px"
    if (this@toDp.isSp) this@toDp.toDp() else 0.dp
}

@Composable
fun Modifier.onClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        enabled = enabled,
        onClick = onClick,
        interactionSource = remember { MutableInteractionSource() },
        indication = LocalIndication.current,
        role = Role.Button,
    )
)

object ImageUtils {
    /**
     * 按长边 [maxSize] 采样解码，顺带按 EXIF 摆正方向。
     * 手机上随手一张截图就是 1080×2400，原图整张塞进内存再缩没有必要。
     */
    fun loadOptimizedBitmap(context: Context, uri: Uri, maxSize: Int = 1024): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        val rotation = context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }.onFailure { Log.e(TAG, "loadOptimizedBitmap failed", it) }.getOrNull()
}
