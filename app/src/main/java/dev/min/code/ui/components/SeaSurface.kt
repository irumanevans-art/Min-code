package dev.min.code.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.rememberSeaPainter
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaAnchor

/**
 * # 整屏是一片水面
 *
 * 只给启动那一屏（[StartPanel] 的 [HeroCanvas]）。会话、设置、文件页都没有。
 * 手指落在这一屏任何地方都像点了一下水面：一小圈细波，不是铺满整屏的靶心。
 * 只在 Initial 阶段旁观按下，不抢事件，按钮照常响应。
 *
 * [time] 必须在组合阶段读取：只在 draw 里读的话点按不会触发重绘，看起来就像毫无反应。
 * 圈画在 `drawWithContent` 里、内容之上，这样不会多一个挡住点击的兄弟节点。
 *
 * Android 13+ 再给内容加一层 AGSL 折射：波前把纸轻轻推开又吸回。没有涟漪时不挂 shader。
 */
@Stable
class SeaSurfaceState internal constructor() {
    var time by mutableFloatStateOf(0f)
        internal set
    var alive by mutableStateOf(false)
        internal set

    internal var origin = 0L
    internal val ripples = Array(MAX_RIPPLES) { Ripple() }
    private var next = 0
    var generation by mutableIntStateOf(0)
        internal set

    fun splash(at: Offset) {
        // 时钟只在有涟漪时走。空转那一次会把 origin 钉在过去，之后 start=time(仍是 0)
        // 而 now-origin 已经是几十秒，涟漪一出生就超龄——看起来就像点了没反应。
        if (!alive) {
            origin = 0L
            time = 0f
        }
        ripples[next].apply {
            x = at.x
            y = at.y
            start = time
            live = true
        }
        next = (next + 1) % MAX_RIPPLES
        generation++
        alive = true
    }

    internal fun anyLive(): Boolean = ripples.any { it.live && time - it.start < LIFE }

    internal class Ripple {
        var x = 0f
        var y = 0f
        var start = 0f
        var live = false
    }

    internal companion object {
        const val MAX_RIPPLES = 4
        const val LIFE = 1.05f
    }
}

@Composable
fun rememberSeaSurface(): SeaSurfaceState {
    val state = remember { SeaSurfaceState() }
    val animations = rememberAnimationsEnabled()
    LaunchedEffect(animations, state.generation) {
        if (!animations) return@LaunchedEffect
        if (state.origin == 0L) state.origin = withFrameNanos { it }
        while (state.anyLive()) {
            val now = withFrameNanos { it }
            state.time = (now - state.origin) / 1_000_000_000f
        }
        state.alive = false
    }
    return state
}

private const val SHADER = """
uniform shader content;
uniform float2 size;
uniform float density;
uniform float4 r0;
uniform float4 r1;
uniform float4 r2;
uniform float4 r3;
uniform float4 foam;

const float LIFE = 1.05;

float2 ripple(float2 p, float4 r, inout float glow) {
    if (r.z < 0.0 || r.z > LIFE) return float2(0.0);
    float k = r.z / LIFE;
    float ease = 1.0 - (1.0 - k) * (1.0 - k);
    float front = ease * 56.0 * density;
    float width = (7.0 + 14.0 * k) * density;
    float2 v = p - r.xy;
    float d = length(v);
    float2 dir = v / max(d, 1.0);
    float env = exp(-pow((d - front) / width, 2.0)) * (1.0 - k) * (1.0 - k);
    float wave = sin((d - front) * (6.2832 / (width * 1.6)));
    glow += env * (0.45 + 0.35 * wave);
    return dir * wave * env * 1.6 * density;
}

half4 main(float2 p) {
    float glow = 0.0;
    float2 disp = ripple(p, r0, glow) + ripple(p, r1, glow)
                + ripple(p, r2, glow) + ripple(p, r3, glow);
    half4 c = content.eval(p + disp);
    half g = half(clamp(glow, 0.0, 1.0) * 0.08);
    c.rgb = c.rgb * (1.0 - g) + half3(foam.rgb) * g;
    return c;
}
"""

/**
 * 启动页的水面：旁观按下、内容上折射、内容之上画一小圈细波。
 */
@Composable
fun SeaSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val animations = rememberAnimationsEnabled()
    val state = rememberSeaSurface()
    // 必须在组合阶段读：只在 draw 里读 time 不会让这一帧重绘
    val t = state.time
    val alive = state.alive
    val painter = rememberSeaPainter()
    val palette = MaterialTheme.sea
    val foam = if (palette.dark) palette.seaFoam else androidx.compose.ui.graphics.Color.White
    val foamAlpha = if (palette.dark) 0.9f else 0.8f
    val ringColor = if (palette.dark) palette.seaBright else palette.sea
    val density = LocalDensity.current.density
    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(SHADER) else null
    }
    Box(
        modifier
            .fillMaxSize()
            .seaAnchor(painter.anchor)
            .then(
                if (animations) {
                    Modifier.pointerInput(state) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    if (change.changedToDown()) state.splash(change.position)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                drawContent()
                if (!animations || !alive) return@drawWithContent
                val brush = painter.brush(size)
                state.ripples.forEach { r ->
                    if (!r.live) return@forEach
                    val age = t - r.start
                    if (age < 0f || age >= SeaSurfaceState.LIFE) return@forEach
                    val k = age / SeaSurfaceState.LIFE
                    val ease = 1f - (1f - k) * (1f - k)
                    val fade = (1f - k) * (1f - k)
                    val center = Offset(r.x, r.y)
                    val front = ease * 56.dp.toPx()
                    RINGS.forEachIndexed { i, ring ->
                        val radius = front * ring.radius
                        if (radius < 1f) return@forEachIndexed
                        drawCircle(
                            color = ringColor,
                            radius = radius,
                            center = center,
                            alpha = fade * ring.alpha,
                            style = Stroke(width = ring.width.dp.toPx()),
                        )
                        if (i == 0) {
                            drawCircle(
                                color = foam,
                                radius = (radius - 1.1.dp.toPx()).coerceAtLeast(0f),
                                center = center,
                                alpha = fade * foamAlpha * 0.55f,
                                style = Stroke(width = 0.7.dp.toPx()),
                            )
                        }
                    }
                    if (k < 0.28f) {
                        val s = 1f - k / 0.28f
                        drawCircle(brush = brush, radius = 2.2.dp.toPx() * s, center = center, alpha = 0.55f * s)
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (animations && alive && shader != null) {
                        Modifier.graphicsLayer {
                            shader.setFloatUniform("size", size.width, size.height)
                            shader.setFloatUniform("density", density)
                            shader.setFloatUniform("foam", foam.red, foam.green, foam.blue, 1f)
                            state.ripples.forEachIndexed { i, r ->
                                val age = if (r.live) t - r.start else -1f
                                shader.setFloatUniform("r$i", r.x, r.y, age, 0f)
                            }
                            renderEffect = RenderEffect
                                .createRuntimeShaderEffect(shader, "content")
                                .asComposeRenderEffect()
                        }
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )
    }
}

private class Ring(val radius: Float, val width: Float, val alpha: Float)

private val RINGS = listOf(
    Ring(1.00f, 1.15f, 0.42f),
    Ring(0.62f, 0.85f, 0.22f),
)
