package dev.min.code.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.sea
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02

/** 左滑删除的两个锚点：原位，和「过了删除线」 */
private enum class SwipeAnchor { Rest, Open }

/**
 * 左滑删除手势容器：包住一行，向左滑过行宽的 40% 松手就回调 [onDelete]，没过就弹回。
 *
 * 只认向左：右滑没有锚点可去，视觉上钳在原位。滑开时行底下露出朱砂底和右缘的
 * 「删除」提示。行**永远弹回原位** —— 真正的删除由 [onDelete] 的接收方先弹确认，
 * 确认后从数据层删，这一层只负责手势和反馈。
 *
 * 40% 的判定在 `confirmValueChange` 里按**松手那一刻的偏移**裁决：默认的落锚规则
 * 是「最近的锚点」，那样 20% 就会触发；放行 Open 之前多看一眼位置，才是任务要的
 * 「过线才删」。弹回动画统一走 [InkMotion.spatial]。
 */
@Composable
fun SwipeToDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.clip(MaterialTheme.shapes.small)) {
        val density = LocalDensity.current
        // 删除线：行宽的 40%，开锚点就钉在它上面
        val openOffset = with(density) { (maxWidth * 0.4f).toPx() }

        // confirmValueChange 要看松手时的偏移，而那一刻 state 还没构造完，用持有者绕一圈。
        // 锚点在构造时就钉上：offset 才不是 NaN，首帧布局读它不会炸
        var stateRef: AnchoredDraggableState<SwipeAnchor>? by remember { mutableStateOf(null) }
        val state = remember(openOffset) {
            AnchoredDraggableState(
                initialValue = SwipeAnchor.Rest,
                confirmValueChange = { target ->
                    target != SwipeAnchor.Open ||
                        stateRef?.offset?.let { it <= -openOffset } == true
                },
            ).also { fresh ->
                fresh.updateAnchors(DraggableAnchors {
                    SwipeAnchor.Rest at 0f
                    SwipeAnchor.Open at -openOffset
                })
                stateRef = fresh
            }
        }

        // 稳到 Open = 松手时过了线。请上层确认删除，行随即弹回原位
        LaunchedEffect(state) {
            snapshotFlow { state.settledValue }.collect { settled ->
                if (settled == SwipeAnchor.Open) {
                    onDelete()
                    state.animateTo(SwipeAnchor.Rest, InkMotion.spatial())
                }
            }
        }

        // 底：朱砂一层，右缘摆删除图标和字，行向左滑开时露出来
        Row(
            modifier = Modifier.matchParentSize().background(MaterialTheme.sea.vermilion),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.Delete02,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.common_delete),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.width(14.dp))
        }

        // 行本体。offset 按锚点走；向右拖会超到正数，钳回 0 才算「右滑不动」。
        // NaN 兜一道：万一行宽还没量出来，就先停在原位
        Box(
            Modifier
                .offset {
                    val x = state.offset
                    IntOffset(if (x.isNaN()) 0 else x.roundToInt().coerceAtMost(0), 0)
                }
                .anchoredDraggable(state, Orientation.Horizontal, enabled = enabled),
        ) {
            content()
        }
    }
}
