package dev.min.code.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap

private const val TAG = "GpuWarmup"

/**
 * 启动后在后台线程把海纹理画进一张离屏 bitmap，逼 GPU / 驱动把
 * BitmapShader 管线建好。第一次用户点海窗时就少一次「冷编译」。
 *
 * 不碰 Compose RuntimeShader（那要主线程 View）；只预热最贵的那张 1.7MB 纹理采样。
 */
object GpuWarmup {
    @Volatile
    private var done = false

    fun warm(context: Context) {
        if (done) return
        runCatching {
            SeaPlate.preload(context)
            // 同步取一下缓存位图：SeaPlate.bitmap() 是 @Composable，这里走 preload 的缓存
            val plate = SeaPlate.cachedOrNull() ?: return
            val src = plate.asAndroidBitmap()
            val out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix().apply {
                setScale(64f / src.width.coerceAtLeast(1), 64f / src.height.coerceAtLeast(1))
            }
            shader.setLocalMatrix(matrix)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            canvas.drawRect(0f, 0f, 64f, 64f, paint)
            // 读一个像素，确保 draw 真的发生
            out.getPixel(0, 0)
            out.recycle()
            done = true
            Log.i(TAG, "sea shader warmed (sdk=${Build.VERSION.SDK_INT})")
        }.onFailure {
            Log.w(TAG, "GpuWarmup failed", it)
        }
    }
}
