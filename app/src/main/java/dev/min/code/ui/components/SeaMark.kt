package dev.min.code.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.theme.SeaMarkPaths
import dev.min.code.ui.theme.SeaWindow
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaInk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 标周围的沙滩怎么收边 */
/**
 * 顶栏那一笔手写 Min。
 *
 * 字是纸上用笔写的（桌面「新」里的照片抠出来的笔画），不是沙滩方块里镂空的标。
 * 笔画本身是海（[seaInk]）：同一片海从字里透出来，右下一线海的深处当笔影。
 * 不加圆角底、不加沙滩——顶栏是纸，字直接落在纸上。
 *
 * [halo]：一圈实垫在笔画底下。顶栏的字会压在滚动的正文上，
 * 没有这圈垫，海和字叠在一起分不开。必须涨出布局（父级不裁）。
 * 浅色是不透明的白；深色是夜纸 #0B1524——白晕叠在深蓝纸上会像一圈灯。
 */
@Composable
fun HandMin(
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    halo: Boolean = false,
) {
    val context = LocalContext.current
    val bitmap = remember {
        ImageBitmap.imageResource(context.resources, R.drawable.min_hand)
    }
    val palette = MaterialTheme.sea
    val haloColor = if (palette.dark) {
        android.graphics.Color.argb(255, 11, 21, 36) // DarkSea.paper #0B1524
    } else {
        android.graphics.Color.WHITE
    }
    val haloImg = remember(bitmap, halo, haloColor) {
        if (halo) letterHaloBitmap(bitmap.asAndroidBitmap(), haloColor) else null
    }
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val letterW = height * aspect
    val shadow = palette.seaDeep.copy(alpha = if (palette.dark) 0.7f else 0.45f)
    val density = LocalDensity.current
    Box(
        modifier.size(letterW, height),
        contentAlignment = Alignment.Center,
    ) {
        if (haloImg != null) {
            val letterWPx = with(density) { letterW.toPx() }
            val scale = letterWPx / haloImg.srcW.toFloat()
            Image(
                bitmap = haloImg.bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier.requiredSize(
                    with(density) { (haloImg.bitmap.width * scale).toDp() },
                    with(density) { (haloImg.bitmap.height * scale).toDp() },
                ),
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                colorFilter = ColorFilter.tint(shadow),
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = (height * 0.04f), y = (height * 0.06f)),
            )
        }
        Image(
            bitmap = bitmap,
            contentDescription = "Min",
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .matchParentSize()
                .seaInk(),
        )
    }
}

/**
 * 实心垫：沿笔画一圈不透明的底色，外边再柔一点。
 * 浅色是白（叠在纸白上等于没有，所以必须不透明实垫）；深色是夜纸，
 * 白晕叠在深蓝纸上会像一圈灯。
 * [graphicsLayer] 会把涨出去的像素裁掉。[BlurMaskFilter] 只在软件 Canvas 上有效。
 */
private class HaloImage(val bitmap: ImageBitmap, val srcW: Int, val srcH: Int)

private fun letterHaloBitmap(src: Bitmap, haloColor: Int): HaloImage {
    val software = if (src.config == Bitmap.Config.HARDWARE) {
        src.copy(Bitmap.Config.ARGB_8888, false) ?: src
    } else {
        src
    }
    // 源图高 256、顶栏画成 28 dp → 0.22 ≈ 6 dp 的实垫
    val r = (software.height * 0.22f).coerceAtLeast(18f)
    val pad = (r + software.height * 0.08f).toInt()
    val out = Bitmap.createBitmap(
        software.width + pad * 2,
        software.height + pad * 2,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(out)
    val fill = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(haloColor, PorterDuff.Mode.SRC_IN)
    }
    val steps = 28
    fun stamp(radius: Float) {
        var i = 0
        while (i < steps) {
            val a = i * (2f * PI.toFloat() / steps)
            canvas.drawBitmap(
                software,
                pad + cos(a) * radius,
                pad + sin(a) * radius,
                fill,
            )
            i++
        }
    }
    stamp(r)
    stamp(r * 0.55f)
    canvas.drawBitmap(software, pad.toFloat(), pad.toFloat(), fill)
    val soft = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(soft).apply {
        drawBitmap(
            out,
            0f,
            0f,
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(r * 0.28f, BlurMaskFilter.Blur.NORMAL)
            },
        )
        drawBitmap(out, 0f, 0f, null)
    }
    return HaloImage(soft.asImageBitmap(), software.width, software.height)
}

enum class SeaSand {
    /** 不铺底，只画字标（已经有纸的地方） */
    None,
    /** 整块两色方砖：就是桌面图标 */
    Tile,
    /** 沙滩向右、向下淡进纸里 */
    Fade,
}

/**
 * 桌面图标那一块：对角线切开的海 / 沙，Cormorant 斜体 Min。
 * M 在海上偏右上，in 在沙上偏左下；i 的点是对角线上的太极。
 * 只有两种填充（[SeaPalette.seaFlat] 与沙 #EFE2C9）。
 */
@Composable
fun SeaMark(
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    @Suppress("UNUSED_PARAMETER") window: SeaWindow = SeaWindow.Screen,
    @Suppress("UNUSED_PARAMETER") foam: Boolean = false,
    sand: SeaSand = SeaSand.Tile,
) {
    val palette = MaterialTheme.sea
    val width = height / SeaMarkPaths.ASPECT
    val seaColor = palette.seaFlat
    val sandColor = Color(0xFFEFE2C9)
    val paper = MaterialTheme.colorScheme.background
    val seaTri = remember { Path() }
    val sandTri = remember { Path() }
    val letters = remember { Path().apply { fillType = PathFillType.NonZero } }
    Canvas(modifier.size(width, height)) {
        val w = size.width
        tri(seaTri, SeaMarkPaths.sea, w)
        tri(sandTri, SeaMarkPaths.sand, w)
        buildPolys(letters, SeaMarkPaths.letters, w)
        fun paintTaiji() {
            val cx = SeaMarkPaths.taijiCx * w
            val cy = SeaMarkPaths.taijiCy * w
            val r = SeaMarkPaths.taijiR * w
            val er = SeaMarkPaths.taijiEyeR * w
            val center = Offset(cx, cy)
            // 圆心就坐在海沙分界线上，所以用 sandTri 去切这枚圆，得到的直线分界
            // 正是那条对角线的延续；两条鱼沿线排开（生成器保证 yangEye/yinEye 在线两侧）。
            drawCircle(sandColor, r, center)
            clipPath(sandTri) { drawCircle(seaColor, r, center) }
            drawCircle(sandColor, r / 2f, Offset(SeaMarkPaths.yangEyeX * w, SeaMarkPaths.yangEyeY * w))
            drawCircle(seaColor, r / 2f, Offset(SeaMarkPaths.yinEyeX * w, SeaMarkPaths.yinEyeY * w))
            drawCircle(seaColor, er, Offset(SeaMarkPaths.yangEyeX * w, SeaMarkPaths.yangEyeY * w))
            drawCircle(sandColor, er, Offset(SeaMarkPaths.yinEyeX * w, SeaMarkPaths.yinEyeY * w))
        }
        fun paintMark() {
            drawPath(seaTri, seaColor)
            clipPath(seaTri) { drawPath(letters, sandColor) }
            clipPath(sandTri) { drawPath(letters, seaColor) }
            paintTaiji()
        }
        when (sand) {
            SeaSand.None -> paintMark()
            SeaSand.Tile -> {
                val radius = CornerRadius(w * 0.22f)
                val clip = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, size.height, radius.x, radius.y))
                }
                clipPath(clip) {
                    drawRect(sandColor)
                    paintMark()
                }
            }
            SeaSand.Fade -> {
                drawRect(sandColor)
                paintMark()
                drawRect(Brush.horizontalGradient(0.62f to paper.copy(alpha = 0f), 1f to paper))
                drawRect(Brush.verticalGradient(0.62f to paper.copy(alpha = 0f), 1f to paper))
            }
        }
    }
}

private fun tri(path: Path, pts: FloatArray, scale: Float) {
    path.reset()
    path.moveTo(pts[0] * scale, pts[1] * scale)
    path.lineTo(pts[2] * scale, pts[3] * scale)
    path.lineTo(pts[4] * scale, pts[5] * scale)
    path.close()
}

private fun buildPolys(path: Path, polys: List<FloatArray>, scale: Float) {
    path.reset()
    polys.forEach { p ->
        var i = 0
        while (i + 1 < p.size) {
            val x = p[i] * scale
            val y = p[i + 1] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        path.close()
    }
}
