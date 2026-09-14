package dev.min.code.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import dev.min.code.ui.theme.LocalDarkMode
import kotlin.math.pow
import kotlin.random.Random

/**
 * 纸的肌理：闪光海报的底是粗纹水彩纸，纹理是看得见的。
 * 两层：细颗粒（随机点）+ 粗纤维（沿横向拉长的淡痕）。位图 192×192，进程内生成一次，ImageShader 平铺。
 * 昼夜只保留低对比度纤维，避免阅读区出现脏点。
 */
@Composable
fun Modifier.paperGrain(alpha: Float = 0.04f): Modifier {
    val dark = LocalDarkMode.current
    val bitmap = remember(dark) { PaperGrain.get(dark) }
    val brush = remember(bitmap) { ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)) }
    return drawBehind { drawRect(brush = brush, alpha = alpha) }
}

private object PaperGrain {
    private const val SIZE = 192
    private var light: ImageBitmap? = null
    private var dark: ImageBitmap? = null

    @Synchronized
    fun get(isDark: Boolean): ImageBitmap {
        if (isDark) return dark ?: generate(true).also { dark = it }
        return light ?: generate(false).also { light = it }
    }

    private fun generate(isDark: Boolean): ImageBitmap {
        val random = Random(if (isDark) 5 else 3)
        // Keep the fiber one value closer to the paper plane in night mode;
        // bright specks read as noise against the poster's quiet dark field.
        val rgb = if (isDark) 0xC7D0D4 else 0x0C0E12
        val a = FloatArray(SIZE * SIZE)
        // 细颗粒
        for (i in a.indices) {
            val v = random.nextFloat()
            a[i] = if (v > 0.7f) ((v - 0.7f) / 0.3f).pow(1.8f) * 0.9f else 0f
        }
        // 粗纤维：横向拉长的淡痕，几十条
        repeat(140) {
            val y = random.nextInt(SIZE)
            val x0 = random.nextInt(SIZE)
            val len = 6 + random.nextInt(26)
            val strength = 0.08f + random.nextFloat() * 0.16f
            for (k in 0 until len) {
                val x = (x0 + k) % SIZE
                val yy = (y + (if (random.nextFloat() < 0.15f) 1 else 0)) % SIZE
                val idx = yy * SIZE + x
                a[idx] = (a[idx] + strength * (0.6f + random.nextFloat() * 0.4f)).coerceAtMost(1f)
            }
        }
        val pixels = IntArray(SIZE * SIZE) { i -> ((a[i] * 255f).toInt() shl 24) or rgb }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
