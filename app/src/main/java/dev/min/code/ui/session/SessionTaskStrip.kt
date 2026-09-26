package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.PanelChip
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.sea

/**
 * 输入胶囊正上方的一条「任务条」：这个会话在后台还有什么在跑。
 *
 * 对齐官方终端底栏那个蓝色的「1 shell」：右侧一枚可点的标记，点开是 [BackgroundShellSheet]。
 * 左边留给 main / 子 agent 的切换（[agents] 槽，由那一侧填）。两边都没东西时整条不占高度 ——
 * 手机上胶囊上方这一行是最金贵的横向空间，不为「没有」常驻一行。
 *
 * @param shellCount 在跑的后台 shell 数（CLI 的 + 本会话托管的，见 [dev.min.code.core.claudecode.backgroundShells]）
 * @param showAgents [agents] 槽里有没有东西。槽本身是 Composable，量不出空不空，由调用方说
 */
@Composable
internal fun SessionTaskStrip(
    shellCount: Int,
    onOpenShells: () -> Unit,
    modifier: Modifier = Modifier,
    showAgents: Boolean = false,
    agents: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = shellCount > 0 || showAgents,
        modifier = modifier,
        enter = InkMotion.expand,
        exit = InkMotion.collapse,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                content = agents,
            )
            // 数到 0 时标记先沉下去，整条再收；退场期间仍写着消失前那个数，不闪成「0 shells」
            val shown = remember { LastNonNull(shellCount.takeIf { it > 0 }) }.update(shellCount.takeIf { it > 0 }) ?: 1
            AnimatedVisibility(visible = shellCount > 0, enter = InkMotion.rise, exit = InkMotion.sink) {
                PanelChip(
                    icon = null,
                    label = pluralStringResource(R.plurals.shell_count, shown, shown),
                    onClick = onOpenShells,
                    modifier = Modifier.animateContentSize(InkMotion.spatial()),
                    leading = { LiveDot(MaterialTheme.sea.sea) },
                )
            }
        }
    }
}
