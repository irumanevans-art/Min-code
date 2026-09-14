package dev.min.code.ui.about

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.min.code.ui.components.BrandMark
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.PaperTone
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.util.writeClipboardText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

internal data class ChangelogEntry(
    val version: String,
    val date: String? = null,
    val body: String,
)

/**
 * 把 CHANGELOG.md 按 `##` 版本头切成条目。最新版在最前（文件本来就是倒序）。
 * 认 `## 1.0.12 — 2026-09-12` 和 `## 1.0.2` 两种头。
 */
internal fun parseChangelog(md: String): List<ChangelogEntry> {
    val lines = md.lineSequence().toList()
    val entries = ArrayList<ChangelogEntry>()
    var version: String? = null
    var date: String? = null
    val body = StringBuilder()

    fun flush() {
        val v = version ?: return
        entries += ChangelogEntry(
            version = v,
            date = date,
            body = body.toString().trim(),
        )
        version = null
        date = null
        body.setLength(0)
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.startsWith("## ")) {
            flush()
            val header = line.removePrefix("## ").trim()
            // 只认两边带空格的破折号，避免把 1.0.12 里的点横线切开
            val sep = listOf(" — ", " – ", " - ").firstOrNull { it in header }
            if (sep != null) {
                val i = header.indexOf(sep)
                version = header.substring(0, i).trim().ifBlank { header }
                date = header.substring(i + sep.length).trim().takeIf { it.isNotBlank() }
            } else {
                version = header
                date = null
            }
        } else if (version != null) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(line)
        }
    }
    flush()
    return entries
}

@Composable
private fun rememberChangelog(context: android.content.Context): List<ChangelogEntry> = remember {
    val raw = runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }
        .getOrNull()
    val parsed = raw?.let(::parseChangelog).orEmpty()
    parsed.ifEmpty {
        listOf(
            ChangelogEntry(
                version = BuildConfig.VERSION_NAME,
                body = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            ),
        )
    }
}

@Composable
fun AboutPage() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashRecorder.read(context)) }
    val scrollState = rememberScrollState()
    val changelog = rememberChangelog(context)
    var expanded by remember(changelog) {
        mutableStateOf(setOf(0).takeIf { changelog.isNotEmpty() }.orEmpty())
    }

    Scaffold(
        topBar = {
            InkTopBar(
                title = "关于",
                navigationIcon = { BackButton() },
                scrolled = scrollState.canScrollBackward,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandMark(large = true)
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

                SectionTitle("更新", modifier = Modifier.padding(top = 8.dp))
                changelog.forEachIndexed { index, entry ->
                    val open = index in expanded
                    PaperCard(tone = PaperTone.Mid, padding = PaddingValues(0.dp)) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (open) expanded - index else expanded + index
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    buildString {
                                        append(entry.version)
                                        entry.date?.let { append(" · "); append(it) }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetbrainsMono,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (open) "收起" else "展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AnimatedVisibility(
                                visible = open,
                                enter = InkMotion.expand,
                                exit = InkMotion.collapse,
                            ) {
                                SelectionContainer {
                                    Text(
                                        entry.body,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetbrainsMono,
                                        modifier = Modifier.padding(
                                            start = 12.dp,
                                            end = 12.dp,
                                            bottom = 12.dp,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                SectionTitle("来源与许可", modifier = Modifier.padding(top = 8.dp))
                Text(
                    "本 App 以 GNU AGPL-3.0 发布。proot 运行时、Rootfs 安装与终端部分源自 RikkaHub（AGPL-3.0），" +
                        "proot 二进制来自 Termux 项目，终端模拟器来自 Termux 的 terminal-view，" +
                        "语法高亮与 Markdown 解析分别来自 RikkaHub 的 highlight 模块与 JetBrains markdown。" +
                        "Claude Code 是 Anthropic 的产品，本 App 与 Anthropic 无关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionTitle("上次崩溃", modifier = Modifier.padding(top = 8.dp))
                CrashReport(report = crash, onClear = {
                    CrashRecorder.clear(context)
                    crash = null
                })
            }
        }
    }
}

/**
 * 崩溃报告。清除时整块收起，而不是瞬间换成一句"没有记录"。
 * 报告本身是机器产物：等宽，装在一张略深的纸条里，可选中。
 */
@Composable
private fun CrashReport(report: String?, onClear: () -> Unit) {
    val context = LocalContext.current
    // 收起动画期间 report 已经是 null，留住最后一份让它能收完
    var last by remember { mutableStateOf(report) }
    if (report != null) last = report
    AnimatedVisibility(visible = report == null, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Text(
            "没有记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = report != null, enter = InkMotion.expand, exit = InkMotion.collapse) {
        val text = last.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperCard(tone = PaperTone.Mid, padding = PaddingValues(12.dp)) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                    )
                }
            }
            InkButton(
                onClick = { context.writeClipboardText(text) },
                icon = HugeIcons.Copy01,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制崩溃报告") }
            InkTextButton(onClick = onClear) { Text("清除") }
        }
    }
}
