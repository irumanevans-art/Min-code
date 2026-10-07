package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.LeftKey
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.ResumeReason
import dev.min.code.ui.components.InkSpinner
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaInk
import java.text.DateFormat
import java.util.Date

/**
 * 输入胶囊左槽里的那一个键：回合在跑是 ■（朱，按下 = 中断），停下来后**原地**变成 ▶（墨，按下 = 接着做）。
 * 设计 §6.2：这是「状态的延续」，不是换一个按钮——■ 的四个角收成 ▶ 的三个顶点（右边两点合一），
 * 颜色从朱渐变到墨（朱只给判定，▶ 是动作，所以用墨）。
 *
 * [left] 是 `composerKeys` 算好的：键怎么摆、点了发什么命令由它定，这里只管画。
 * - [LeftKey.Hidden]：槽收起（淡出时仍按最后一个形状画完）；
 * - [LeftKey.Stop]：■；
 * - [LeftKey.Resume]：▶，无障碍文案「继续：<原因>」；
 * - [LeftKey.Resuming]：▶ 外圈一道墨环转动，禁止重复按（续跑请求已发出、回合还没起来）。
 */
@Composable
internal fun StopResumeKey(
    left: LeftKey,
    onStop: () -> Unit,
    onResume: (ResumeOffer) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 槽收起的那几百毫秒还要画得出内容：记住最后一个非 Hidden 的键
    val last = remember { LastNonNull<LeftKey>(null) }
    val shown = last.update(left.takeIf { it !is LeftKey.Hidden })
    AnimatedVisibility(visible = left !is LeftKey.Hidden, enter = InkMotion.expand, exit = InkMotion.collapse, modifier = modifier) {
        val key = shown ?: return@AnimatedVisibility
        val resumable = key is LeftKey.Resume || key is LeftKey.Resuming
        val morph by animateFloatAsState(if (resumable) 1f else 0f, InkMotion.spatialFast(), label = "stopResumeMorph")
        val interaction = remember { MutableInteractionSource() }
        val description = when (key) {
            is LeftKey.Stop -> stringResource(R.string.composer_interrupt)
            is LeftKey.Resume -> stringResource(R.string.composer_resume_desc, resumeReasonText(key.offer.reason, key.offer.since))
            is LeftKey.Resuming -> stringResource(R.string.composer_resuming)
            LeftKey.Hidden -> ""
        }
        val clickable = key is LeftKey.Stop || key is LeftKey.Resume
        Box(
            Modifier
                .pressScale(interaction, 0.9f)
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .semantics { contentDescription = description; role = Role.Button }
                .clickable(
                    enabled = clickable,
                    interactionSource = interaction,
                    indication = androidx.compose.foundation.LocalIndication.current,
                ) {
                    when (key) {
                        is LeftKey.Stop -> onStop()
                        is LeftKey.Resume -> onResume(key.offer)
                        else -> Unit
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            MorphingGlyph(morph, size = 16.dp)
            if (key is LeftKey.Resuming) InkSpinner(size = 28.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, stroke = 1.4.dp)
        }
    }
}

/**
 * ■ → ▶ 的图形：四个顶点线性插值（方形四角 → 三角形三顶点，右边两点合一），圆角用描边的圆头接合给出。
 * 朱的那一层与墨的那一层是同一个形状：[t] 小时朱占满、大时墨占满（墨层是镂空到取底纹理上的，见 `seaInk`）。
 */
@Composable
private fun MorphingGlyph(t: Float, size: Dp) {
    val vermilion = MaterialTheme.sea.vermilion
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) { drawGlyph(t, vermilion.copy(alpha = 1f - t)) }
        Box(Modifier.size(size).seaInk(alpha = t)) {
            Canvas(Modifier.size(size)) { drawGlyph(t, Color.Black) }
        }
    }
}

private fun DrawScope.drawGlyph(t: Float, color: Color) {
    if (color.alpha <= 0f) return
    val inset = 1.5.dp.toPx()
    val w = this.size.width - inset * 2
    val h = this.size.height - inset * 2
    val points = glyphPoints(t)
    val path = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val px = inset + x * w
            val py = inset + y * h
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color, style = Fill)
    // 圆头描边把尖角磨圆：和 HugeIcons 的 Stop / Play 同一种软角
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * 形状在 [t]（0 = ■，1 = ▶）时的四个顶点，坐标 0..1。▶ 的右边两点重合在右边的中点。
 * 单独成函数是为了单测钉住端点：改了图形，测试会提醒两头仍然是方形和三角形。
 */
internal fun glyphPoints(t: Float): List<Pair<Float, Float>> {
    val square = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
    val triangle = listOf(0f to 0f, 1f to 0.5f, 1f to 0.5f, 0f to 1f)
    return square.indices.map { i ->
        val (x0, y0) = square[i]
        val (x1, y1) = triangle[i]
        (x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t)
    }
}

/** 无障碍文案里「继续：<原因>」的原因部分。时间取设备本地时区的时分 */
@Composable
internal fun resumeReasonText(reason: ResumeReason, since: Long?): String {
    val time = since?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    return when (reason) {
        ResumeReason.UserStopped -> stringResource(R.string.resume_reason_stopped)
        is ResumeReason.Failed -> stringResource(R.string.resume_reason_failed, failureLabel(reason.kind))
        is ResumeReason.ProcessDeath ->
            (reason.atMs ?: since)?.let { stringResource(R.string.resume_reason_process_death_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
                ?: stringResource(R.string.resume_reason_process_death)
        ResumeReason.Limit -> stringResource(R.string.resume_reason_limit)
        ResumeReason.HookStopped -> stringResource(R.string.resume_reason_hook)
        ResumeReason.EngineExited -> stringResource(R.string.resume_reason_engine_exited)
    }.let { text -> if (reason is ResumeReason.UserStopped && time != null) "$text · $time" else text }
}

@Composable
private fun failureLabel(kind: FailureKind?): String = stringResource(
    when (kind) {
        FailureKind.Network -> R.string.min_agent_fail_network
        FailureKind.RateLimited -> R.string.min_agent_fail_rate_limited
        FailureKind.Overloaded -> R.string.min_agent_fail_overloaded
        FailureKind.ContextOverflow -> R.string.min_agent_fail_context
        FailureKind.Auth -> R.string.min_agent_fail_auth
        FailureKind.BadRequest -> R.string.min_agent_fail_bad_request
        FailureKind.ModelUnavailable -> R.string.min_agent_fail_model_unavailable
        FailureKind.Server -> R.string.min_agent_fail_server
        FailureKind.Other, null -> R.string.min_agent_fail_other
    },
)
