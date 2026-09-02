package dev.min.code.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Settings02
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表侧边栏。
 *
 * 数据不是 App 自己存的 —— 直接读官方 CLI 写在 Rootfs 里的 transcript
 * (`~/.claude/projects/<cwd>/<uuid>.jsonl`)，所以手机上删掉会话记录和在 CLI 里看到的
 * 始终一致，也不需要额外的数据库迁移。
 *
 * 版式与会话流同一套语言：实心圆点 = 进程还活着（和轨道上"你说的话"用同一个标记形状，
 * 都表示"这一条是活的"），元信息一律等宽字。「新建会话」降级成和会话同级的一行 ——
 * 它不该比列表里的会话本身更抢眼。
 */
@Composable
fun ClaudeCodeSessionDrawer(
    sessions: List<ClaudeCodeVM.SessionEntry>,
    permanent: Boolean = false,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCopyTranscript: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Text(
                "会话",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )
            ActionRow(HugeIcons.PlusSign, "新建会话", onNewSession)
            ActionRow(HugeIcons.Folder01, "工作区文件", onOpenFiles)
            ActionRow(HugeIcons.ComputerTerminal01, "终端", onOpenTerminal)
            // 手机上逐段拖选很难受，而 SelectionContainer 已经占掉了长按手势，
            // 没法再给每条挂一个"复制本条"。整段导出放在这里作为兜底。
            onCopyTranscript?.let { ActionRow(HugeIcons.Copy01, "复制整个会话", it) }
            // CLI 装完之后安装向导就从 UI 上消失了，更新入口只能挂在这儿
            ActionRow(HugeIcons.Package, "环境与更新", onOpenMaintenance)
            ActionRow(HugeIcons.Settings02, "设置", onOpenSettings)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有会话。新建一个开始。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(
                            entry = s,
                            onClick = { onOpenSession(s.id) },
                            onDelete = { onDeleteSession(s.id) },
                        )
                    }
                }
            }
        }
    }
    if (permanent) {
        // 宽屏左栏：固定宽度，右侧描边把它和会话流分开
        PermanentDrawerSheet(
            modifier = Modifier.width(300.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            content = { body() },
        )
    } else {
        ModalDrawerSheet(content = { body() })
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SessionRow(
    entry: ClaudeCodeVM.SessionEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (entry.isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 进程还活着的打个点：切过去是秒切，不用重启也不会丢上下文
        if (entry.isLive) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        } else {
            Spacer(Modifier.size(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (entry.updatedAt != Long.MAX_VALUE) append(formatTime(entry.updatedAt))
                    if (entry.messageCount > 0) {
                        if (isNotEmpty()) append("  ")
                        append("${entry.messageCount} 条")
                    }
                    if (entry.isLive) {
                        if (isNotEmpty()) append("  ")
                        append("运行中")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                HugeIcons.Delete02,
                "删除会话",
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
