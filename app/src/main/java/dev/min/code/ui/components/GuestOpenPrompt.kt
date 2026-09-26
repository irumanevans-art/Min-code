package dev.min.code.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.browser.GuestOpenInbox
import dev.min.code.core.browser.GuestOpenTarget
import dev.min.code.core.browser.guestOpenDisplayUrl
import dev.min.code.core.browser.guestOpenTarget
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.util.LocalPreviewBus
import org.koin.compose.koinInject

/**
 * rootfs 里有程序要开网页时弹的那张卡。挂在 [dev.min.code.MainActivity] 的根上：
 * 请求可能从会话页（agent 跑的）、终端页（人敲的）或任何别的页面上来，卡片得在哪都看得见。
 *
 * **点一下才打开，从不自动打开** —— 发起者可能是 agent，它一句命令就能在用户手机上打开任意网页。
 * 点「打开」时本机地址进预览槽（经 [LocalPreviewBus]：翻回会话页、收掉抽屉和面板再展开），
 * 外网走外部浏览器的 Custom Tab（见 [openInExternalBrowser] 的头注释：登录页绝不能进预览槽的 WebView）。
 */
@Composable
fun GuestOpenPrompt() {
    val inbox: GuestOpenInbox = koinInject()
    val queue by inbox.queue.collectAsStateWithLifecycle()
    val request = queue.showing ?: return
    val context = LocalContext.current
    val toaster = LocalToaster.current

    InkDialog(
        onDismissRequest = inbox::resolve,
        title = stringResource(
            if (request.source != null) R.string.guest_open_title else R.string.guest_open_title_unknown,
            request.source ?: request.host,
            request.host,
        ),
        confirmButton = {
            InkTextButton(
                onClick = {
                    // 先收卡再开：外部浏览器是另一个 Activity，卡片留着等回来没有意义
                    inbox.resolve()
                    when (val target = guestOpenTarget(request.url)) {
                        // 翻回会话页、收抽屉由总线另一头负责（MainActivity 根部、ClaudeCodePage），
                        // 和终端里点本机链接走的是同一条路
                        is GuestOpenTarget.Preview -> LocalPreviewBus.offer(target.url)
                        is GuestOpenTarget.External -> {
                            if (!context.openInExternalBrowser(target.url)) {
                                toaster.show(context.getString(R.string.guest_open_no_browser), ToastType.Error)
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.guest_open_open)) }
        },
        dismissButton = {
            InkTextButton(onClick = inbox::resolve) { Text(stringResource(R.string.guest_open_dismiss)) }
        },
    ) {
        // 完整 URL 给人核对：域名写在标题里，但授权地址的后半截才是它真正要去的地方
        Text(
            guestOpenDisplayUrl(request.url),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (queue.waiting.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "+${queue.waiting.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
