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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.BrandMark
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.PaperTone
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.util.writeClipboardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

internal data class ChangelogEntry(
    val version: String,
    val date: String? = null,
    val body: String,
)

/**
 * 版本号 `a.b.c` 的归组键。
 * - [major] = `a`：所有 1.x.y 同属 major "1"
 * - [minor] = `a.b`：所有 1.1.x 同属 minor "1.1"
 * 解析不到三段时整串既当 major 也当 minor，避免丢条目。
 */
internal data class VersionKeys(val major: String, val minor: String)

internal fun versionKeys(version: String): VersionKeys {
    val parts = version.trim().split('.')
    return when {
        parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank() ->
            VersionKeys(major = parts[0], minor = "${parts[0]}.${parts[1]}")
        else -> VersionKeys(major = version.trim(), minor = version.trim())
    }
}

/** 同一 `a.b` 下的一组补丁版（`c` 不同） */
internal data class ChangelogMinorGroup(
    val minor: String,
    val entries: List<ChangelogEntry>,
)

/** 同一 `a` 下的一组次版本 */
internal data class ChangelogMajorGroup(
    val major: String,
    val minors: List<ChangelogMinorGroup>,
)

/**
 * 把扁平的版本条目收成 a → a.b → a.b.c。
 * 输入已是新→旧；组内顺序保持不变。
 */
internal fun groupChangelog(entries: List<ChangelogEntry>): List<ChangelogMajorGroup> {
    if (entries.isEmpty()) return emptyList()
    val majorOrder = ArrayList<String>()
    val minorOrderByMajor = LinkedHashMap<String, ArrayList<String>>()
    val bucket = LinkedHashMap<String, ArrayList<ChangelogEntry>>() // key = minor

    for (entry in entries) {
        val keys = versionKeys(entry.version)
        if (keys.major !in minorOrderByMajor) {
            majorOrder += keys.major
            minorOrderByMajor[keys.major] = ArrayList()
        }
        val minors = minorOrderByMajor.getValue(keys.major)
        if (keys.minor !in minors) minors += keys.minor
        bucket.getOrPut(keys.minor) { ArrayList() } += entry
    }

    return majorOrder.map { major ->
        ChangelogMajorGroup(
            major = major,
            minors = minorOrderByMajor.getValue(major).map { minor ->
                ChangelogMinorGroup(minor = minor, entries = bucket.getValue(minor))
            },
        )
    }
}

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

/** 联系作者的 QQ 号：展示与复制用同一个来源，改这里就行 */
private const val CONTACT_QQ = "1114111189"

@Composable
fun AboutPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    // 崩溃记录是两个文件，读它们要碰磁盘 —— 不在组合期同步做，
    // 否则一进关于页就是一次主线程 IO。没读到之前那两块就不显示
    var crash by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (fatal, nonFatal) = withContext(Dispatchers.IO) {
            CrashRecorder.read(context) to CrashRecorder.readNonFatal(context)
        }
        crash = fatal
        lastError = nonFatal
    }
    val scrollState = rememberScrollState()
    val changelog = rememberChangelog(context)
    val groups = remember(changelog) { groupChangelog(changelog) }
    // a → a.b → a.b.c：默认展开最新 major、其下最新 minor、该 minor 里最新一条 patch
    val latestMajor = groups.firstOrNull()?.major
    val latestMinor = groups.firstOrNull()?.minors?.firstOrNull()?.minor
    val latestPatch = groups.firstOrNull()?.minors?.firstOrNull()?.entries?.firstOrNull()?.version
    var expandedMajors by remember(groups) {
        mutableStateOf(setOfNotNull(latestMajor))
    }
    var expandedMinors by remember(groups) {
        mutableStateOf(setOfNotNull(latestMinor))
    }
    var expandedPatches by remember(groups) {
        mutableStateOf(setOfNotNull(latestPatch))
    }

    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(R.string.about_title),
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
                AppUpdateSection()
                Text(
                    stringResource(R.string.about_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SectionTitle(stringResource(R.string.about_contact), modifier = Modifier.padding(top = 8.dp))
                PaperCard(tone = PaperTone.Low) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.writeClipboardText(CONTACT_QQ)
                                // 不少 ROM（vivo 就是）复制之后系统什么都不说，点了跟没点一样
                                toaster.show(context.getString(R.string.about_contact_qq_copied), ToastType.Success)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.about_contact_qq, CONTACT_QQ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = JetbrainsMono,
                        )
                    }
                }

                SectionTitle(stringResource(R.string.about_whats_new), modifier = Modifier.padding(top = 8.dp))
                groups.forEach { majorGroup ->
                    val majorOpen = majorGroup.major in expandedMajors
                    // 只有一个 major 时不必多点一层 a，直接展次版本；多个 a 才折叠
                    val showMajorChrome = groups.size > 1
                    val majorsVisible = !showMajorChrome || majorOpen
                    if (showMajorChrome) {
                        ChangelogFoldHeader(
                            title = majorGroup.major,
                            subtitle = majorGroup.minors.joinToString(" · ") { it.minor },
                            open = majorOpen,
                            onToggle = {
                                expandedMajors =
                                    if (majorOpen) expandedMajors - majorGroup.major
                                    else expandedMajors + majorGroup.major
                            },
                        )
                    }
                    AnimatedVisibility(
                        visible = majorsVisible,
                        enter = InkMotion.expand,
                        exit = InkMotion.collapse,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            majorGroup.minors.forEach { minorGroup ->
                                val minorOpen = minorGroup.minor in expandedMinors
                                PaperCard(tone = PaperTone.Mid, padding = PaddingValues(0.dp)) {
                                    Column {
                                        ChangelogFoldHeader(
                                            title = minorGroup.minor,
                                            subtitle = minorGroup.entries.firstOrNull()?.date,
                                            open = minorOpen,
                                            onToggle = {
                                                expandedMinors =
                                                    if (minorOpen) expandedMinors - minorGroup.minor
                                                    else expandedMinors + minorGroup.minor
                                            },
                                            embedded = true,
                                        )
                                        AnimatedVisibility(
                                            visible = minorOpen,
                                            enter = InkMotion.expand,
                                            exit = InkMotion.collapse,
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(bottom = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                minorGroup.entries.forEach { entry ->
                                                    val patchOpen = entry.version in expandedPatches
                                                    Column {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    expandedPatches =
                                                                        if (patchOpen) {
                                                                            expandedPatches - entry.version
                                                                        } else {
                                                                            expandedPatches + entry.version
                                                                        }
                                                                }
                                                                .padding(
                                                                    horizontal = 12.dp,
                                                                    vertical = 8.dp,
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Text(
                                                                buildString {
                                                                    append(entry.version)
                                                                    entry.date?.let {
                                                                        append(" · ")
                                                                        append(it)
                                                                    }
                                                                },
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontFamily = JetbrainsMono,
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                            Text(
                                                                if (patchOpen) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                        AnimatedVisibility(
                                                            visible = patchOpen,
                                                            enter = InkMotion.expand,
                                                            exit = InkMotion.collapse,
                                                        ) {
                                                            SelectionContainer {
                                                                // 正文是 Markdown 写的：以前按纯文本画，`**加粗**` 的星号
                                                                // 和 `- ` 的减号原样显示在屏幕上。这是给人读的话，
                                                                // 不是机器产物，所以也不该是等宽——版本号那行才是。
                                                                MarkdownBlock(
                                                                    content = entry.body,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    modifier = Modifier.padding(
                                                                        start = 12.dp,
                                                                        end = 12.dp,
                                                                        bottom = 10.dp,
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SectionTitle(stringResource(R.string.about_credits), modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.about_license_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionTitle(stringResource(R.string.about_last_crash), modifier = Modifier.padding(top = 8.dp))
                CrashReport(report = crash, onClear = {
                    CrashRecorder.clear(context)
                    crash = null
                })

                // 被协程 handler 接住的异常（App 没崩）单独一份，堆栈同样值得能看到
                if (lastError != null) {
                    SectionTitle(stringResource(R.string.about_last_error), modifier = Modifier.padding(top = 8.dp))
                    CrashReport(report = lastError, onClear = {
                        CrashRecorder.clearNonFatal(context)
                        lastError = null
                    })
                }
            }
        }
    }
}

/**
 * 折叠行：标题左、展开/收起右。
 * [embedded] 为 true 时不再外套 PaperCard（已经在父卡里）。
 */
@Composable
private fun ChangelogFoldHeader(
    title: String,
    subtitle: String?,
    open: Boolean,
    onToggle: () -> Unit,
    embedded: Boolean = false,
) {
    val row = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = JetbrainsMono,
                    )
                }
            }
            Text(
                if (open) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (embedded) {
        row()
    } else {
        PaperCard(tone = PaperTone.Low, padding = PaddingValues(0.dp)) { row() }
    }
}

/**
 * 崩溃报告。清除时整块收起，而不是瞬间换成一句"没有记录"。
 * 报告本身是机器产物：等宽，装在一张略深的纸条里，可选中。
 */
@Composable
private fun CrashReport(report: String?, onClear: () -> Unit) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    // 收起动画期间 report 已经是 null，留住最后一份让它能收完
    var last by remember { mutableStateOf(report) }
    if (report != null) last = report
    AnimatedVisibility(visible = report == null, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Text(
            stringResource(R.string.about_no_record),
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
                onClick = {
                    context.writeClipboardText(text)
                    toaster.show(context.getString(R.string.common_copied), ToastType.Success)
                },
                icon = HugeIcons.Copy01,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.about_copy_crash)) }
            InkTextButton(onClick = onClear) { Text(stringResource(R.string.common_clear)) }
        }
    }
}
