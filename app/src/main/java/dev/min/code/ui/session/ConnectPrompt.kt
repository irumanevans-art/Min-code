package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.Login03

/**
 * 启动面板上的「还没连接」：一句说明 + 一列去处。
 *
 * 向导只装环境、不问 token，所以第一次进会话页多半就是这个状态。不拦「启动会话」按钮：
 * 判定只看 DataStore 里的连接（[dev.min.code.core.settings.AppSettings.claudeConnected]），它没跟上真实情况时，锁死按钮等于把人关在门外；
 * 真起不来，启动失败的报错照样会显示在这张面板上。
 *
 * 去处是一列而不是 Notice 右侧的小字按钮：「用 Claude 订阅登录」和「供应商」并列，
 * 两条都是「选一种连法」，分量一样。订阅那条只报一声，登录面板由调用方挂在这一块外面——
 * 登录一成功这块就随「已连接」收起，面板挂在里面的话会跟着一起没掉，看不到「已登录」那一句。
 */
@Composable
internal fun ConnectPrompt(
    onOpenProviders: () -> Unit,
    onSubscriptionLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 「注意」档：没连接不是出错，朱砂留给真正的判定
        Notice(text = stringResource(R.string.session_connect_notice), tone = NoticeTone.Warn)
        ConnectOption(
            icon = HugeIcons.Exchange01,
            label = stringResource(R.string.session_connect_provider),
            onClick = onOpenProviders,
        )
        ConnectOption(
            icon = HugeIcons.Login03,
            label = stringResource(R.string.session_connect_subscription),
            onClick = onSubscriptionLogin,
        )
    }
}

/** 一种连法。次动作（纸底）：主动作仍是下面的「启动会话」 */
@Composable
private fun ConnectOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    InkButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        tone = InkButtonTone.Paper,
        icon = icon,
    ) { Text(label) }
}
