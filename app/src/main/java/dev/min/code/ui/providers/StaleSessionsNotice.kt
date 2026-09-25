package dev.min.code.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.theme.InkMotion

/**
 * 换路之后「有 N 个会话正忙，仍在用上一家」+「重启它们 / 先不动」。
 *
 * 供应商页（点了一家）和订阅那几处（登录成功、切回供应商、登出）说的是同一件事，只留这一份。
 * 不自动重起：用户刚才只是换了条路，不该因此丢掉正跑的一轮，见
 * [dev.min.code.core.claudecode.ClaudeCodeSessionRegistry.reloadConnection]。
 *
 * [count] 为 0 时自己收起。
 */
@Composable
fun StaleSessionsNotice(
    count: Int,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 收起动画那几帧 count 已经是 0 了：记住最后一个非零值，免得淡出时字变成「0 个会话」
    // （普通数组不是快照状态：只是个记忆，不该在组合途中写状态再触发一轮重组）
    val last = remember { intArrayOf(count) }
    if (count > 0) last[0] = count
    val shown = last[0]
    AnimatedVisibility(count > 0, modifier = modifier, enter = InkMotion.enter, exit = InkMotion.exit) {
        Notice(
            text = stringResource(R.string.providers_stale_sessions, shown),
            tone = NoticeTone.Warn,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InkTextButton(onClick = onRestart) {
                        Text(stringResource(R.string.providers_stale_restart))
                    }
                    InkTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.providers_stale_dismiss))
                    }
                }
            },
        )
    }
}
