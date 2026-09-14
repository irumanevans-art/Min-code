package dev.min.code.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 设备倾斜。闪光海报的机制——"随电子设备晃动产生效果变化"——在这里变成一个全局状态：
 * 金箔高光（[foilSheen]）、洒金（[goldFlecks]）都从它读位置。
 *
 * 两个轴都是**相对**量：不是"手机现在朝哪"，而是"刚才动了多少"。绝对姿态没有用——
 * 手机拿在手里本来就是斜的，按绝对值算高光永远偏在一边。做法是让一条慢基线跟着姿态走
 * （约 2 秒跟上），输出 = 当前姿态 − 基线：一晃，高光流过去，放稳了，两秒内慢慢回中。
 *
 * 值只在绘制阶段读（`mutableFloatStateOf`），每帧最多写一次，不会引起重组。
 */
@Stable
class TiltState {
    /** 左右倾斜 −1..1 */
    var x by mutableFloatStateOf(0f)
        internal set

    /** 前后倾斜 −1..1 */
    var y by mutableFloatStateOf(0f)
        internal set

    /** 本机有没有可用的传感器；没有时 x / y 恒为 0，高光只剩空闲漂移 */
    var available by mutableStateOf(false)
        internal set

    /** 倾斜的幅度 0..1 */
    val magnitude: Float get() = minOf(1f, sqrt(x * x + y * y))
}

val LocalTilt = staticCompositionLocalOf { TiltState() }

/** 多大的倾角算"满格"：36°。再大高光就流出画面了 */
private const val FULL_TILT_RAD = 0.628f

/** 基线跟随速度（每帧）；越小回中越慢 */
private const val BASELINE_FOLLOW = 0.02f

/** 输出平滑（每帧）；传感器 50Hz 的抖动要压掉 */
private const val OUTPUT_SMOOTH = 0.18f

/**
 * 在根上调用一次，把 [TiltState] 提供给整棵树。
 * 只在 RESUMED 时监听传感器（切到后台立刻注销，这是一项耗电的东西），
 * 系统动画关掉时完全不启动。
 */
@Composable
fun rememberTilt(): TiltState {
    val context = LocalContext.current
    val state = remember { TiltState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val animations = rememberAnimationsEnabled()
    if (!animations) return state

    LaunchedEffect(lifecycleOwner, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = manager?.let { m ->
                m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                    ?: m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                    ?: m.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            }
            if (manager == null || sensor == null) {
                state.available = false
                return@repeatOnLifecycle
            }
            state.available = true
            val reader = TiltReader(context)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = reader.feed(event)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            try {
                var outX = 0f
                var outY = 0f
                while (true) {
                    withFrameNanos { }
                    val (tx, ty) = reader.relative()
                    outX += (tx - outX) * OUTPUT_SMOOTH
                    outY += (ty - outY) * OUTPUT_SMOOTH
                    if (abs(outX - state.x) > 0.0008f) state.x = outX
                    if (abs(outY - state.y) > 0.0008f) state.y = outY
                }
            } finally {
                manager.unregisterListener(listener)
            }
        }
    }
    return state
}

/** 把传感器事件折算成 (roll, pitch)，并维护慢基线 */
private class TiltReader(private val context: Context) {
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var roll = 0f
    @Volatile private var pitch = 0f
    private var baseRoll = Float.NaN
    private var basePitch = Float.NaN

    fun feed(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val (ax, ay) = displayAxes()
                SensorManager.remapCoordinateSystem(rotation, ax, ay, remapped)
                SensorManager.getOrientation(remapped, orientation)
                pitch = orientation[1]
                roll = orientation[2]
            }

            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                roll = atan2(-gx, sqrt(gy * gy + gz * gz))
                pitch = atan2(-gy, sqrt(gx * gx + gz * gz))
            }
        }
    }

    /** 当前姿态减去慢基线，归一化到 −1..1 */
    fun relative(): Pair<Float, Float> {
        val r = roll
        val p = pitch
        if (baseRoll.isNaN()) {
            baseRoll = r
            basePitch = p
        } else {
            baseRoll += wrap(r - baseRoll) * BASELINE_FOLLOW
            basePitch += wrap(p - basePitch) * BASELINE_FOLLOW
        }
        val x = (wrap(r - baseRoll) / FULL_TILT_RAD).coerceIn(-1f, 1f)
        val y = (wrap(p - basePitch) / FULL_TILT_RAD).coerceIn(-1f, 1f)
        return x to y
    }

    /** 角度差折回 −π..π，否则横过 ±π 那一瞬基线会被拉飞 */
    private fun wrap(delta: Float): Float {
        var d = delta
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    /** 屏幕转了，坐标系也要跟着转，否则横屏时左右倾斜会变成前后 */
    private fun displayAxes(): Pair<Int, Int> {
        val rotation = runCatching { ContextCompat.getDisplayOrDefault(context).rotation }.getOrDefault(Surface.ROTATION_0)
        return when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}
