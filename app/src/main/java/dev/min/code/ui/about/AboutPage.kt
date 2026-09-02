package dev.min.code.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.min.code.BuildConfig
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.theme.CustomColors
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.util.writeClipboardText

@Composable
fun AboutPage() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashRecorder.read(context)) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("关于") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Min", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "把官方 Claude Code CLI 装进手机上的 Ubuntu（proot）里，用一个像工作日志的界面驱动它。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                Text("来源与许可", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "本 App 以 GNU AGPL-3.0 发布。proot 运行时、Rootfs 安装与终端部分源自 RikkaHub（AGPL-3.0），" +
                        "proot 二进制来自 Termux 项目，终端模拟器来自 Termux 的 terminal-view，" +
                        "语法高亮与 Markdown 解析分别来自 RikkaHub 的 highlight 模块与 JetBrains markdown。" +
                        "Claude Code 是 Anthropic 的产品，本 App 与 Anthropic 无关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                Text("上次崩溃", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val report = crash
                if (report == null) {
                    Text("没有记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                        SelectionContainer {
                            Text(
                                report,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                            )
                        }
                    }
                    Button(onClick = { context.writeClipboardText(report) }, modifier = Modifier.fillMaxWidth()) {
                        Text("复制崩溃报告")
                    }
                    TextButton(onClick = { CrashRecorder.clear(context); crash = null }) { Text("清除") }
                }
            }
        }
    }
}
