package dev.min.code.ui.theme

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * # 海
 *
 * 「蓝色取底」那张纹理是整个 App 里唯一的蓝。界面上每一块蓝色都是同一个动作：
 * **把一张纸镂空成那个形状，盖在纹理的某一块上**。所以这里没有"蓝色"这个色值，只有一扇扇窗。
 *
 * - [SeaField]：整屏共用的那片海。它铺在所有纸的下面，**静止**：滚动列表时纸在海上滑，窗里看到的海就跟着变——
 *   这正是镂空的意思。海本身不流动；只有发送键那一扇窗里的海在流（[seaInk] 的 `flow`）。
 * - 大面积的窗（超过 1800 dp²）不取纹理：一大块随机的纹理是无意义的突兀，[seaFill] 自动改用 [SeaPalette.seaFlat]。
 * - [Modifier.seaInk]：把任何内容（字、图标、Canvas 里画的线）变成海：内容只提供形状（alpha）。
 * - [Modifier.seaFill]：在元素后面铺一块海（按钮底、选中块、气泡边）。
 * - [DrawScope.seaBrush]：自定义绘制里直接拿画笔。
 *
 * 窗口位置按元素在窗口里的真实坐标算（[onGloballyPositioned]），于是相邻两扇窗看到的是连续的海。
 * 少数展示性的地方可以指定看哪一块（[SeaWindow.Fixed]）：首页那个巨大的字标要落在这张底
 * 最好看的地方——海是金脉，云是那片破光，陶是丝络最密的一带。坐标按风格给（[Skin.heroWindow]），
 * 写死一个的话，换了底它指到的只是随便一处。
 */
object SeaPlate {
    /**
     * 按资源 id 缓存。三张底（海 / 云 / 陶，见 [Skins]）都是整屏级的大位图，
     * 解一次留着——重解会在切换动画里卡一帧。
     *
     * 但**同一时刻只该留一张**：一张 1200×1130 的 ARGB_8888 解出来常驻约 5.4 MB，
     * 而任何时刻只有当前风格那张在用。切风格时由 [evictExcept] 放掉旧的。
     */
    private val cached = HashMap<Int, ImageBitmap>()

    /**
     * 后台预解码纹理（海 1.7MB / 云 0.6MB）。首帧点窗时若还没好，仍会同步 decode 一次；
     * 启动后 IO 预热能把「第一次操作卡一下」削掉大半。
     */
    fun preload(context: android.content.Context, res: Int = R.drawable.sea_plate) {
        synchronized(this) {
            if (cached.containsKey(res)) return
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bmp = android.graphics.BitmapFactory.decodeResource(
                context.applicationContext.resources,
                res,
                opts,
            ) ?: return
            cached[res] = bmp.asImageBitmap()
        }
    }

    /** 给 [GpuWarmup] 等非 Compose 路径读已预热的位图；未 preload 时为 null。 */
    fun cachedOrNull(res: Int = R.drawable.sea_plate): ImageBitmap? = synchronized(this) { cached[res] }

    /**
     * 放掉除 [keep] 以外的所有底。切风格之后调一次。
     *
     * 一张 1200×1130 的 ARGB_8888 解出来常驻约 5.4 MB。三套风格全留着就是十几 MB，
     * 而任何时刻只有一张在用 —— 这里是「口袋里的 Claude Code」，那块内存本来就紧。
     */
    fun evictExcept(keep: Int) {
        synchronized(this) {
            cached.keys.filter { it != keep }.forEach { cached.remove(it) }
        }
    }

    @Composable
    fun bitmap(res: Int = LocalSeaPlate.current): ImageBitmap {
        val context = LocalContext.current
        return remember(res) {
            synchronized(this) {
                cached.getOrPut(res) { ImageBitmap.imageResource(context.resources, res) }
            }
        }
    }
}

/**
 * 这棵子树的底是哪一张。由 [MinTheme] 按当前风格提供，默认是海。
 *
 * 「强调色不是色值」这条规则换了底之后原样成立——[seaInk] / [seaFill] / [rememberSeaPainter]
 * 做的还是同一个动作，只是镂空底下那张纹理不同。所以整套 Ink* 组件、**会话流左边那条轨道**
 * （连同它的螺旋与白沫）、发送键里流动的窗，全部跟着这一个值走，没有一处要单独改。
 * 换纸不换刀。
 */
val LocalSeaPlate = staticCompositionLocalOf { R.drawable.sea_plate }

/** 这扇窗看海的哪一块 */
sealed interface SeaWindow {
    /** 按元素在屏幕上的位置：整屏一片连续的海（默认） */
    data object Screen : SeaWindow

    /**
     * 固定看纹理的某一块：[x] / [y] 是纹理的归一化坐标（0..1），[span] 是窗口宽占纹理宽的比例。
     * 元素越小 span 越小，海的纹理才看得出来。
     */
    data class Fixed(val x: Float, val y: Float, val span: Float = 0.5f) : SeaWindow
}

/**
 * 整屏那片海的状态：纹理原点相对屏幕原点的偏移（px）与缩放。静止。
 */
@Stable
class SeaField {
    var offsetX by mutableFloatStateOf(0f)
        internal set
    var offsetY by mutableFloatStateOf(0f)
        internal set

    /** 一个纹理像素画成多少屏幕像素。纹理宽铺到屏幕宽的 1.3 倍 */
    var scale by mutableFloatStateOf(1f)
        internal set
    var ready by mutableStateOf(false)
        internal set
}

val LocalSeaField = staticCompositionLocalOf { SeaField() }

/** 超过这个面积的窗不取纹理，改用平色 */
private const val FLAT_AREA_DP2 = 1800f

/** 发送键里的海：流动的幅度与周期 */
private const val FLOW_DP = 9f
private const val FLOW_PERIOD_MS = 7_000

/**
 * 海与陶都是横构图（1200×1130），1.3 倍就够，剩下的靠 MIRROR 平铺补，墨纹看不出对称。
 * 云是竖构图且形态连贯得多，镜像接缝一眼能认出来，所以那边是 [CLOUD_WIDTH_FACTOR]。
 */
internal const val SEA_WIDTH_FACTOR = 1.3f

/**
 * 在根上调用一次。纹理宽铺到屏宽的 [widthFactor] 倍、居中、起点略往上，之后不动。
 *
 * @param widthFactor 见 [SEA_WIDTH_FACTOR]。按风格取（`Skin.plateWidthFactor`），
 *   因为它是这张图的构图属性，不是一个可调的口味。
 */
@Composable
fun rememberSeaField(widthFactor: Float = SEA_WIDTH_FACTOR): SeaField {
    val field = remember { SeaField() }
    val bitmap = SeaPlate.bitmap()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    LaunchedEffect(bitmap, screenWidthPx, widthFactor) {
        field.scale = screenWidthPx * widthFactor / bitmap.width
        field.offsetX = -(bitmap.width * field.scale - screenWidthPx) / 2f
        field.offsetY = -bitmap.height * field.scale * 0.08f
        field.ready = true
    }
    return field
}

/** 一支能画海的笔：位图 + 当前窗口的局部矩阵。每次取都是新的（矩阵会变），不要缓存 */
private class SeaShaderBrush(private val bitmap: ImageBitmap, private val matrix: Matrix) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        BitmapShader(bitmap.asAndroidBitmap(), Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
            setLocalMatrix(matrix)
        }
}

/**
 * 在绘制阶段取一支海笔。[rootPosition] 是当前元素在窗口里的位置，[window] 决定看哪一块。
 * [seaAlphaTint] 之类的调子由调用方在 drawRect 的 alpha 上处理。
 */
fun seaBrush(field: SeaField, bitmap: ImageBitmap, rootPosition: Offset, window: SeaWindow, size: Size, flow: Offset = Offset.Zero): ShaderBrush {
    val m = Matrix()
    when (window) {
        SeaWindow.Screen -> {
            val s = field.scale
            m.setScale(s, s)
            m.postTranslate(field.offsetX - rootPosition.x + flow.x, field.offsetY - rootPosition.y + flow.y)
        }

        is SeaWindow.Fixed -> {
            // 窗口宽 = span × 纹理宽，铺满元素宽；纹理上的 (x, y) 对准元素中心
            val s = size.width / (bitmap.width * window.span)
            m.setScale(s, s)
            val cx = window.x * bitmap.width * s
            val cy = window.y * bitmap.height * s
            m.postTranslate(size.width / 2f - cx + field.offsetX * 0.15f, size.height / 2f - cy + field.offsetY * 0.15f)
        }
    }
    return SeaShaderBrush(bitmap, m)
}

/** 追踪元素在窗口里的位置，放在 seaInk / seaFill 之前 */
@Stable
class SeaAnchor {
    var position by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberSeaAnchor(): SeaAnchor = remember { SeaAnchor() }

fun Modifier.seaAnchor(anchor: SeaAnchor): Modifier =
    onGloballyPositioned { anchor.position = it.positionInRoot() }

/**
 * 把内容变成海：内容只提供形状。文字、图标、Canvas 里的线都可以。
 * 需要一个离屏层（SrcIn），所以一屏几十个没问题，几百个就该改用 [seaFill]。
 *
 * @param flow 这扇窗里的海在流（只给发送键）：一个 7 秒一圈的小环，系统动画关掉时静止
 */
@Composable
fun Modifier.seaInk(alpha: Float = 1f, window: SeaWindow = SeaWindow.Screen, flow: Boolean = false): Modifier {
    val field = LocalSeaField.current
    val bitmap = SeaPlate.bitmap()
    val anchor = rememberSeaAnchor()
    val clock = flowClock(flow)
    val flowPx = with(LocalDensity.current) { FLOW_DP.dp.toPx() }
    return this
        .seaAnchor(anchor)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f || size.height <= 0f) return@drawWithContent
            val t = clock.value * 2f * PI.toFloat()
            val flowOffset = if (flow) Offset(sin(t) * flowPx, cos(t * 0.7f) * flowPx * 0.6f) else Offset.Zero
            drawRect(
                brush = seaBrush(field, bitmap, anchor.position, window, size, flowOffset),
                alpha = alpha,
                blendMode = BlendMode.SrcIn,
            )
        }
}

/**
 * 在元素后面铺一块海，按 [shape] 裁。[alpha] 低于 1 就是海的水洗版。
 * 面积超过 1800 dp² 时不取纹理，改用 [SeaPalette.seaFlat]。
 */
@Composable
fun Modifier.seaFill(shape: Shape? = null, alpha: Float = 1f, window: SeaWindow = SeaWindow.Screen): Modifier {
    val field = LocalSeaField.current
    val bitmap = SeaPlate.bitmap()
    val anchor = rememberSeaAnchor()
    val flat = MaterialTheme.sea.seaFlat
    var m = this.seaAnchor(anchor)
    if (shape != null) m = m.clip(shape)
    return m.drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        val areaDp2 = size.width * size.height / (density * density)
        if (areaDp2 > FLAT_AREA_DP2) {
            drawRect(flat, alpha = alpha)
        } else {
            drawRect(brush = seaBrush(field, bitmap, anchor.position, window, size), alpha = alpha)
        }
    }
}

/** 0→1 循环；不流或系统动画关掉时停在 0 */
@Composable
private fun flowClock(enabled: Boolean): androidx.compose.runtime.State<Float> {
    if (!enabled || !rememberAnimationsEnabled()) return remember { mutableFloatStateOf(0f) }
    return androidx.compose.animation.core.rememberInfiniteTransition(label = "seaFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(FLOW_PERIOD_MS, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "seaFlowPhase",
    )
}

/** 给自定义 Canvas 用：在 DrawScope 里拿到这个元素对应的海笔 */
@Stable
class SeaPainter internal constructor(
    private val field: SeaField,
    private val bitmap: ImageBitmap,
    val anchor: SeaAnchor,
) {
    fun brush(size: Size, window: SeaWindow = SeaWindow.Screen): ShaderBrush =
        seaBrush(field, bitmap, anchor.position, window, size)

    fun DrawScope.brush(window: SeaWindow = SeaWindow.Screen): ShaderBrush = brush(size, window)
}

@Composable
fun rememberSeaPainter(): SeaPainter {
    val field = LocalSeaField.current
    val bitmap = SeaPlate.bitmap()
    val anchor = rememberSeaAnchor()
    return remember(field, bitmap) { SeaPainter(field, bitmap, anchor) }
}

/** 海上的字色 */
val onSea: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.sea.onSea
