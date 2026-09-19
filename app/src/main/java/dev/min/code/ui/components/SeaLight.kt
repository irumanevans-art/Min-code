package dev.min.code.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.MinDisplay
import dev.min.code.ui.theme.SeaWindow
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaInk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * # 首页：光在海里走
 *
 * 对 moonshot.ai 首页的转译。那边是黑底上一枚月亮拖着引力把字扭曲；这边是纸上一个巨大的 Min——
 * 字本身是海（[seaInk]），一束光在海面上巡游：光所在的一圈水面像一枚透镜，把字往里拉、放大，
 * 圈边一弯光的新月，圈外一团柔光晕开在纸上、光里透出一张焦散的水光网；水面的细波把字沿水平方向
 * 撕成一道道并拖出残影，红蓝在光边错开；圈外只剩轻微的涌动。四周有几粒光的碎屑慢慢往上浮。
 * 手指按下去，光就从指尖出发跟手；松手后它从指尖滑回巡游的路线。进场时字从水里浮起、光渐亮。
 *
 * 点按的涟漪不在这里：整屏的水面（[SeaSurface]）统一负责，字标只是水面上被推开的内容之一。
 *
 * 用 AGSL（[RuntimeShader]，Android 13+）逐像素折射。Android 12 及以下没有 RuntimeShader，字静止。
 */
@Stable
class SeaLightState internal constructor() {
    var time by mutableFloatStateOf(0f)
        internal set
    var light by mutableStateOf(Offset.Zero)
        internal set
    var touching by mutableStateOf(false)
        internal set
    internal var size = Offset.Zero

    /** 松手的位置与时刻：光从这里滑回巡游路线，而不是瞬移 */
    internal var releasePoint = Offset.Zero
    internal var releaseTime = -100f

    internal companion object {
        /** 松手后光滑回巡游路线用的时间（秒） */
        const val GLIDE_BACK = 1.6f
    }
}

@Composable
fun rememberSeaLight(): SeaLightState {
    val state = remember { SeaLightState() }
    val animations = rememberAnimationsEnabled()
    LaunchedEffect(animations) {
        if (!animations) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = (now - start) / 1_000_000_000f
            state.time = t
            if (!state.touching && state.size != Offset.Zero) {
                // 自己巡游：慢慢的李萨如，横向走得多、纵向走得少——字是横着排的
                val patrol = Offset(
                    state.size.x * (0.5f + 0.36f * sin(t * 0.11f * 2f * PI.toFloat())),
                    state.size.y * (0.5f + 0.18f * cos(t * 0.07f * 2f * PI.toFloat() + 1f)),
                )
                // 刚松手：从指尖滑回路线，用同一条落墨曲线，不瞬移
                val since = t - state.releaseTime
                state.light = if (since < SeaLightState.GLIDE_BACK) {
                    val k = InkMotion.Ease.transform((since / SeaLightState.GLIDE_BACK).coerceIn(0f, 1f))
                    Offset(
                        state.releasePoint.x + (patrol.x - state.releasePoint.x) * k,
                        state.releasePoint.y + (patrol.y - state.releasePoint.y) * k,
                    )
                } else {
                    patrol
                }
            }
        }
    }
    return state
}

private const val SHADER = """
uniform shader content;
uniform float2 size;
uniform float time;
uniform float2 light;
uniform float strength;
uniform float4 rim;
uniform float dark;
half4 main(float2 p) {
    float R = min(size.x, size.y) * 0.42;
    float2 v = p - light;
    float d = length(v);
    float2 dir = v / max(d, 1.0);
    // 透镜：圈内把采样点往中心拉（放大），中段最强
    float inside = smoothstep(R, R * 0.15, d);
    float pull = strength * R * 0.30 * inside * (1.0 - 0.45 * inside);
    float2 disp = -dir * pull;
    // 水面的细波：光的一圈附近，字沿水平方向被撕成一道道
    float band = exp(-pow((d - R) / (R * 0.4), 2.0));
    float streak = max(band, inside * 0.7);
    disp.x += sin(p.y * 0.085 + time * 5.0) * 8.0 * strength * streak;
    disp.x += sin(p.y * 0.23 - time * 9.0) * 2.5 * strength * streak;
    // 圈外：轻微的涌动
    disp += float2(sin(p.y * 0.012 + time * 1.3), sin(p.x * 0.010 - time * 1.1)) * 2.5 * strength;
    half4 c = content.eval(p + disp);
    // 残影：沿水平拖出的几层影子，光边最明显
    float smear = 9.0 * strength * streak;
    half4 echo = content.eval(p + disp + float2(smear, 0.0)) * 0.5
               + content.eval(p + disp - float2(smear, 0.0)) * 0.5
               + content.eval(p + disp + float2(smear * 2.2, 0.0)) * 0.25
               + content.eval(p + disp - float2(smear * 2.2, 0.0)) * 0.25;
    c = c + echo * half(0.22 * streak) * (1.0 - c.a);
    // 色散：光边红蓝沿水平错开
    float split = 4.0 * strength * max(band, inside);
    c.r = content.eval(p + disp + float2(split, 0.0)).r;
    c.b = content.eval(p + disp - float2(split, 0.0)).b;

    // 氛围：光在纸上晕开的一团柔光（只在光周围一圈内），光里一张慢慢游动、不规则的水光网
    float halo = exp(-pow(d / (R * 0.85), 2.0) * 1.6);
    float n1 = sin(p.x * 0.047 + p.y * 0.021 + time * 1.1);
    float n2 = sin(p.x * 0.019 - p.y * 0.053 - time * 0.9);
    float n3 = sin(p.x * 0.083 + p.y * 0.067 + time * 1.7);
    float n4 = sin(p.x * 0.011 - p.y * 0.037 + time * 0.6);
    float net = pow(0.5 + 0.5 * sin(n1 * 1.9 + n2 * 1.5 + n3 * 0.8 + n4 * 2.3), 5.0);
    float paperLight = halo * (0.06 + 0.14 * net) * strength * (1.0 + 0.8 * dark);
    half3 haloCol = half3(rim.rgb);
    // 只落在纸上（字外）
    c.rgb += haloCol * half(paperLight) * (1.0 - c.a);
    c.a += half(paperLight) * (1.0 - c.a);
    // 字上的水光：光里的字亮一点，网纹更亮
    c.rgb += half3(half(halo * (0.10 + 0.25 * net) * strength)) * c.a;

    // 光的新月：圈边一道细亮，亮的一侧随时间转
    float ringA = exp(-pow((d - R) / 4.5, 2.0));
    float ang = atan(v.y, v.x);
    float crescent = 0.5 + 0.5 * cos(ang - time * 0.7);
    float glow = ringA * pow(crescent, 3.0) * strength;
    half3 rimCol = mix(haloCol, half3(1.0), c.a);
    c.rgb = c.rgb * (1.0 - half(glow)) + rimCol * half(glow);
    c.a = c.a * (1.0 - half(glow)) + half(glow);
    return c;
}
"""

/** 把光与折射作用到这个元素上；按住 / 拖动时光源跟手。[strength] 0 = 静水。 */
@Composable
fun Modifier.seaLight(state: SeaLightState, strength: Float = 1f): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val animations = rememberAnimationsEnabled()
    if (!animations) return this
    val shader = remember { RuntimeShader(SHADER) }
    val palette = MaterialTheme.sea
    val rim = palette.sea
    val dark = if (palette.dark) 1f else 0f
    val density = LocalDensity.current
    val scale = with(density) { 1.dp.toPx() } / 2.75f
    return this
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                state.touching = true
                state.light = down.position
                var last = down.position
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let {
                        state.light = it.position
                        last = it.position
                    }
                } while (event.changes.any { it.pressed })
                state.releasePoint = last
                state.releaseTime = state.time
                state.touching = false
            }
        }
        .graphicsLayer {
            state.size = Offset(size.width, size.height)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("time", state.time)
            shader.setFloatUniform("light", state.light.x, state.light.y)
            shader.setFloatUniform("strength", strength * scale)
            shader.setFloatUniform("rim", rim.red, rim.green, rim.blue, 1f)
            shader.setFloatUniform("dark", dark)
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
}

/**
 * 首页的字标：一个巨大的 Min（Playfair Display 斜体，海做的），光在它上面走；下面一句话。
 * 字的四周留 48dp 给折射用，被拉出去的像素不会被层的边裁掉。
 * 进场：字从水里浮起（下方 18dp、96% 淡入），光在 1.4 秒里渐亮。
 */
@Composable
fun SeaHero(
    modifier: Modifier = Modifier,
    tagline: String? = stringResource(R.string.app_tagline),
    /** 可用的宽 / 高（dp）：字号取宽的 40% 与高的 22% 中较小的一个，矮屏、横屏都放得下 */
    widthDp: Float = LocalConfiguration.current.screenWidthDp.toFloat(),
    heightDp: Float = LocalConfiguration.current.screenHeightDp.toFloat(),
) {
    val light = rememberSeaLight()
    val animations = rememberAnimationsEnabled()
    val enter = remember { Animatable(if (animations) 0f else 1f) }
    LaunchedEffect(animations) {
        if (animations && enter.value < 1f) enter.animateTo(1f, tween(1400, easing = InkMotion.Ease))
    }
    val fontSize = minOf(widthDp * 0.40f, heightDp * 0.22f).coerceIn(72f, 240f).sp
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Glints(Modifier.matchParentSize(), light)
            Box(
                Modifier
                    .graphicsLayer {
                        val k = enter.value
                        alpha = k
                        translationY = (1f - k) * 18.dp.toPx()
                        scaleX = 0.96f + 0.04f * k
                        scaleY = scaleX
                    }
                    .seaLight(light, strength = enter.value)
                    .padding(horizontal = 48.dp, vertical = 44.dp),
            ) {
                MinWordmark(fontSize = fontSize)
            }
        }
        if (tagline != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                tagline,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = enter.value },
            )
        }
    }
}

/**
 * 那个巨大的 Min。
 *
 * i 的点就是**字体自带的那一颗**，不另画记号。曾经这里照桌面图标的构造在原位盖过一枚太极，
 * 图是对上了，代价是首页这个字不再是"一笔写下来的"——一枚平色的两色圆盘压在流动的海上，
 * 光穿过去时它是唯一不动的东西。记号留在桌面图标和启动页（那里它本来就是记号）；
 * 首页这枚是字，整词一次画完，字距、连字、那颗点都交给字体自己。
 *
 * 字身是海（[seaInk]）：同一片海从笔画里透出来，光巡游到哪儿就在哪儿把它拉弯。
 */
@Composable
private fun MinWordmark(fontSize: TextUnit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val style = remember(fontSize) {
        TextStyle(
            fontFamily = MinDisplay,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02f * fontSize.value).sp,
        )
    }
    val layout = remember(style, measurer) {
        measurer.measure(WORDMARK, style, softWrap = false, maxLines = 1)
    }
    val w = with(density) { layout.size.width.toDp() }
    val h = with(density) { layout.size.height.toDp() }

    // 整词一次画完：形状交给字体，颜色是海
    Canvas(
        Modifier
            .width(w)
            .height(h)
            .seaInk(window = SeaWindow.Fixed(0.5f, 0.45f, 0.62f)),
    ) {
        drawText(layout, color = Color.Black)
    }
}

private const val WORDMARK = "Min"

private class Glint(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val drift: Float)

/**
 * 光的碎屑：二十几粒海色的小点在字标周围慢慢往上浮、一明一灭，离光近的亮。
 * 值只在绘制阶段读；系统动画关掉时静止。
 */
@Composable
private fun Glints(modifier: Modifier, light: SeaLightState) {
    val palette = MaterialTheme.sea
    val glints = remember { val rnd = Random(19); List(26) { Glint(rnd.nextFloat(), rnd.nextFloat(), 0.7f + rnd.nextFloat() * 1.3f, 0.02f + rnd.nextFloat() * 0.03f, rnd.nextFloat() * 6.28f, (rnd.nextFloat() - 0.5f) * 0.06f) } }
    val color = if (palette.dark) palette.seaFoam else palette.sea
    Canvas(modifier) {
        val t = light.time
        glints.forEach { g ->
            val y = ((g.y - t * g.speed) % 1f + 1f) % 1f
            val x = ((g.x + sin(t * 0.5f + g.phase) * g.drift) % 1f + 1f) % 1f
            val p = Offset(x * size.width, y * size.height)
            val dl = (p - light.light).getDistance() / (size.minDimension.coerceAtLeast(1f) * 0.6f)
            val near = (1f - dl).coerceIn(0f, 1f)
            val twinkle = 0.5f + 0.5f * sin(t * 1.6f + g.phase * 3f)
            val a = (0.18f + 0.55f * near) * (0.45f + 0.55f * twinkle)
            drawCircle(color, g.r.dp.toPx() * (0.8f + 0.5f * near), p, alpha = a)
        }
    }
}
