package dev.min.code.ui.session

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Attachment01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Stop
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode

/** get_session_cost 返回的是整段文本，这里只摘第一个金额 */
private val COST_AMOUNT = Regex("""\$[0-9]+\.[0-9]+""")

/** 上下文用到这个比例就变告警色 —— CLI 到这一带会自动压缩，值得提前知道 */
private const val CONTEXT_WARN_RATIO = 0.85f

/**
 * 底部输入区。三行，从上到下信息密度递减：
 *
 * ```
 * 上下文 159k/200k ▓▓▓▓▓░                     $0.12
 * 📎  [ 说点什么…                          ]    ↑
 * opus-5 · Manual · high                        ⋯
 * ```
 *
 * 第三行是**一整行摘要**而不是一排 chip：模型/模式/effort/工作目录/斜杠命令这五样
 * 使用频率都很低（权限模式基本开局定一次），为它们常驻一整行还要横划发现，
 * 性价比是负的。点开 [ClaudeCodeSettingsSheet] 就是全部，不需要探索。
 */
@Composable
internal fun ClaudeCodeInputBar(
    session: ClaudeCodeManager.SessionState,
    onSend: (String, List<ClaudeCodeImage>) -> Unit,
    onInterrupt: () -> Unit,
    onSetModel: (String?) -> Unit,
    onSetPermissionMode: (ClaudeCodePermissionMode) -> Unit,
    onApplyEffort: (String?, Boolean) -> Unit,
    onRequestModels: () -> Unit,
    onRefreshUsage: () -> Unit = {},
    onSetCwd: (String) -> Unit = {},
    /** 移除附件时把文件从工作区删掉，否则"删掉输入框里那行路径"并不会真的取消上传 */
    onDeleteWorkspaceFile: (path: String) -> Unit = {},
    /** 把系统选中的文件导入沙箱，回调返回沙箱内路径（失败为 null） */
    onImportFile: (fileName: String, stream: java.io.InputStream, onDone: (String?) -> Unit) -> Unit =
        { _, _, done -> done(null) },
    /** `@` 提及的文件补全，返回 guest 侧绝对路径 */
    onSearchFiles: suspend (String) -> List<String> = { emptyList() },
    /** 相册/截图 → 已缩放编码好的 image block；失败返回 null */
    onLoadImage: suspend (Uri) -> ClaudeCodeImage? = { null },
    /** 打开本地接管的交互式命令面板（/mcp、/agents…） */
    onOpenLocalCommand: (LocalSlash) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 附件独立于输入框文本。之前是把路径直接拼进输入框，于是
    // ①看不出上传了什么文件（只有一行裸路径）②把那行删掉文件其实还在工作区里。
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    // 图片走 image content block，不落盘、不进工作区，所以和 attachments 分开存
    var images by remember { mutableStateOf<List<PendingImage>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        var remaining = uris.size
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            } ?: uri.lastPathSegment ?: "imported_file"
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                remaining -= 1
                if (remaining == 0) importing = false
                return@forEach
            }
            onImportFile(name, stream) { path ->
                if (path != null) attachments = attachments + Attachment(name = name, path = path)
                remaining -= 1
                if (remaining == 0) importing = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            uris.take(MAX_IMAGES - images.size).forEach { uri ->
                val block = onLoadImage(uri)
                if (block != null) {
                    images = images + PendingImage(
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "image",
                        block = block,
                    )
                }
            }
            importing = false
        }
    }

    val running = session.status == ClaudeCodeManager.SessionStatus.Running
    // 输入以 "/" 开头时把斜杠命令顶上来（就地补全，比翻设置面板快）
    val slashQuery = input.takeIf { it.startsWith("/") }?.drop(1)?.substringBefore(' ')
    val matchedCommands = remember(slashQuery, session.slashCommands) {
        if (slashQuery == null) emptyList()
        else session.slashCommands.filter { it.name.contains(slashQuery, ignoreCase = true) }.take(6)
    }

    // `@` 提及：取光标前最后一个 @ 后的连续非空白串当查询词。
    // OutlinedTextField 只回传 String（拿不到光标位置），所以按"最后一个 @"近似；
    // 实际输入里 @ 后面接着打字的场景，这个近似和真实光标位置是一致的。
    val mentionQuery = remember(input) { mentionTokenOf(input) }
    var fileMatches by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(mentionQuery, running) {
        if (mentionQuery == null || !running) {
            fileMatches = emptyList()
            return@LaunchedEffect
        }
        // 抖一下：每敲一个字母就走一次目录遍历，手机上会明显掉帧
        delay(MENTION_DEBOUNCE_MS)
        fileMatches = runCatching { onSearchFiles(mentionQuery) }.getOrDefault(emptyList())
    }

    fun composeMessage(): String {
        if (attachments.isEmpty()) return input
        val refs = attachments.joinToString(separator = System.lineSeparator()) { it.path }
        return if (input.isBlank()) refs else input + System.lineSeparator() + System.lineSeparator() + refs
    }

    fun openSettings(section: SettingsSection?) {
        // 只要开 sheet 就拉一次模型目录（CLI 的 list_models + 中转站的 /v1/models，后者每会话只查一次）。
        // 之前只在直接跳到「模型」分区时才拉，从摘要行进来再点开「模型」永远停在"正在查询"
        onRequestModels()
        settingsSection = section
        settingsOpen = true
    }

    /** 本地接管的斜杠命令：会话设置类的开 sheet，配置编辑类的交给页面开专门的面板 */
    fun handleLocal(target: LocalSlash) {
        val section = target.toSection()
        if (section != null) openSettings(section) else onOpenLocalCommand(target)
    }

    fun submit() {
        val local = localSlashTarget(input)
        if (local != null) {
            handleLocal(local)
            input = ""
            return // 附件留着，用户设置完接着发
        }
        onSend(composeMessage(), images.map { it.block })
        input = ""
        attachments = emptyList()
        images = emptyList()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (attachments.isNotEmpty() || images.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                images.forEach { img ->
                    AttachmentChip(
                        name = img.name,
                        icon = HugeIcons.Image02,
                        onRemove = { images = images - img },
                    )
                }
                attachments.forEach { att ->
                    AttachmentChip(
                        name = att.name,
                        onRemove = {
                            attachments = attachments - att
                            onDeleteWorkspaceFile(att.path) // 真删文件，否则"取消"只是视觉上的
                        },
                    )
                }
            }
        }

        if (matchedCommands.isNotEmpty()) {
            SlashCommandList(matchedCommands) { picked ->
                val local = LOCAL_SLASH_HANDLERS[picked.name.lowercase()]
                if (local != null) {
                    handleLocal(local)
                    input = ""
                } else {
                    input = "/${picked.name} "
                }
            }
            HorizontalDivider()
        }

        if (fileMatches.isNotEmpty()) {
            FileMentionList(fileMatches) { picked ->
                input = replaceMentionToken(input, picked)
                fileMatches = emptyList()
            }
            HorizontalDivider()
        }

        ContextMeter(session = session, onRefresh = onRefreshUsage)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = running && !importing) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(HugeIcons.Attachment01, "添加文件", Modifier.size(20.dp))
                }
            }
            // 图片单独一个入口：它走的是 image content block，和"导入文件到工作区"
            // 是两条完全不同的路径，混在一个按钮里用户无从选择
            IconButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = running && !importing && images.size < MAX_IMAGES,
            ) {
                Icon(HugeIcons.Image02, "添加图片", Modifier.size(20.dp))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    // 只在空会话里教一次用法。会话里已经有内容时，这行提示只是噪音 ——
                    // 它和上面的正文抢注意力，而用户早就知道输入框是干什么的
                    val hint = when {
                        session.items.isNotEmpty() -> ""
                        session.busy -> "生成中，发送会排到下一轮"
                        else -> "说点什么，/ 命令，@ 文件"
                    }
                    if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium)
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                enabled = running,
            )
            // 生成中也允许继续发（CLI 自己排队），所以发送键常驻；
            // 中断是独立按钮，不和发送共用位置 —— 之前想追一句话反而把任务打断了
            if (session.busy) {
                IconButton(onClick = onInterrupt) {
                    Icon(HugeIcons.Stop, "中断当前任务", Modifier.size(20.dp), MaterialTheme.colorScheme.error)
                }
            }
            IconButton(
                onClick = { submit() },
                enabled = (input.isNotBlank() || attachments.isNotEmpty() || images.isNotEmpty()) && running,
            ) {
                Icon(HugeIcons.ArrowUp01, if (session.busy) "排队发送" else "发送", Modifier.size(20.dp))
            }
        }

        SessionSummaryRow(session = session, enabled = running, onClick = { openSettings(null) })
    }

    if (settingsOpen) {
        ClaudeCodeSettingsSheet(
            session = session,
            initialSection = settingsSection,
            onDismiss = { settingsOpen = false },
            onSetModel = { onSetModel(it); settingsOpen = false },
            onSetPermissionMode = { onSetPermissionMode(it); settingsOpen = false },
            onApplyEffort = { effort, ultra -> onApplyEffort(effort, ultra); settingsOpen = false },
            onSetCwd = { onSetCwd(it); settingsOpen = false },
            onPickCommand = { input = "/$it "; settingsOpen = false },
        )
    }
}

/**
 * 上下文余量。用进度条而不是纯数字：`159k / 200k` 要读两个数再算比例，
 * 一条填充度看一眼就懂。逼近上限时变告警色 —— CLI 到那一带会自动压缩上下文，
 * 这件事发生前用户有权知道。
 */
@Composable
private fun ContextMeter(
    session: ClaudeCodeManager.SessionState,
    onRefresh: () -> Unit,
) {
    val used = session.contextTokens
    val cost = session.costText?.let { COST_AMOUNT.find(it)?.value }
    if (used == null && cost == null) return
    val limit = session.contextLimit ?: 200_000
    val ratio = if (used != null && limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val warn = ratio >= CONTEXT_WARN_RATIO

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (used != null) {
            Text(
                text = "${used / 1000}k/${limit / 1000}k",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (warn) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .width(72.dp)
                    .height(3.dp),
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.weight(1f))
        cost?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 底栏那一行摘要：当前模型 · 权限模式 · 思考强度。点开就是全部会话设置。 */
@Composable
private fun SessionSummaryRow(
    session: ClaudeCodeManager.SessionState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOf(
                session.modelChipLabel(),
                session.permissionMode.label,
                session.effortChipLabel(),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            HugeIcons.MoreHorizontal,
            contentDescription = "会话设置",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 已导入工作区、待随下一条消息发出的文件 */
private data class Attachment(val name: String, val path: String)

/** 待随下一条消息发出的图片。不落盘 —— 截图这种一次性图片没有留在工作区的理由。 */
private data class PendingImage(val name: String, val block: ClaudeCodeImage)

@Composable
private fun AttachmentChip(
    name: String,
    onRemove: () -> Unit,
    icon: ImageVector = HugeIcons.Attachment01,
) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, Modifier.size(13.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                Icon(HugeIcons.Cancel01, "移除", Modifier.size(13.dp))
            }
        }
    }
}

/**
 * `@` 文件补全的候选列表。
 *
 * 只显示文件名 + 所在目录两段，不显示完整路径：`/workspace/` 前缀在每一条里都一样，
 * 手机宽度下把真正有区分度的文件名挤没了。
 */
@Composable
private fun FileMentionList(paths: List<String>, onPick: (String) -> Unit) {
    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        paths.forEach { path ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(path) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                )
                Text(
                    path.substringBeforeLast('/', ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SlashCommandList(
    commands: List<ClaudeCodeManager.SlashCommand>,
    onPick: (ClaudeCodeManager.SlashCommand) -> Unit,
) {
    Column(modifier = Modifier.heightIn(max = 200.dp)) {
        commands.forEach { cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "/${cmd.name}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                cmd.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 需要在本地处理、不能转发给 CLI 的斜杠命令。
 *
 * `-p --output-format stream-json` 是**无头模式**，没有 TUI —— `/model`、`/mcp` 这类
 * 交互式命令在真终端里会弹选择器或编辑器，在这里 CLI 只能回一段纯文本摘要，
 * 既点不了也改不了。所以在发送前拦下来，改开 App 自己的面板：
 *
 * - 会话类（[LocalSlash.MODEL] / [LocalSlash.EFFORT] / [LocalSlash.PERMISSION] / [LocalSlash.CWD]）
 *   背后走 `list_models` / `set_model` / `--effort` 这些控制请求；
 * - 配置类（[LocalSlash.MCP] / [LocalSlash.AGENTS] / [LocalSlash.MEMORY] / [LocalSlash.CONFIG] /
 *   [LocalSlash.HOOKS]）背后就是 Rootfs 里那几个文件 —— TUI 的那些编辑器本质上也只是
 *   在改它们，所以原生表单能做到完全等价，而且在手机上更好用。
 */
internal enum class LocalSlash {
    MODEL, EFFORT, PERMISSION, CWD,
    MCP, AGENTS, MEMORY, CONFIG, HOOKS,
}

internal val LOCAL_SLASH_HANDLERS = mapOf(
    "model" to LocalSlash.MODEL,
    "models" to LocalSlash.MODEL,
    "effort" to LocalSlash.EFFORT,
    "thinking" to LocalSlash.EFFORT,
    "permission-mode" to LocalSlash.PERMISSION,
    "cwd" to LocalSlash.CWD,
    "add-dir" to LocalSlash.CWD,
    "mcp" to LocalSlash.MCP,
    "agents" to LocalSlash.AGENTS,
    "memory" to LocalSlash.MEMORY,
    "config" to LocalSlash.CONFIG,
    "statusline" to LocalSlash.CONFIG,
    "output-style" to LocalSlash.CONFIG,
    "hooks" to LocalSlash.HOOKS,
    "permissions" to LocalSlash.HOOKS,
)

/** 输入框内容是不是一条该本地接管的斜杠命令；带参数的（`/model opus`）不拦，原样发下去 */
internal fun localSlashTarget(input: String): LocalSlash? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/") || trimmed.length < 2) return null
    if (trimmed.any { it.isWhitespace() }) return null
    return LOCAL_SLASH_HANDLERS[trimmed.drop(1).lowercase()]
}

/**
 * 取出正在输入的 `@` 提及词；不在提及状态返回 null。
 *
 * 规则：最后一个 `@` 必须在开头或前面是空白（否则 `foo@bar.com` 这种邮箱会误触发），
 * 且它后面不能再有空白（有空白说明这个提及已经打完了）。
 * 返回空串是合法的 —— 刚敲下 `@` 还没输入字符时应该把整个目录列出来。
 */
internal fun mentionTokenOf(input: String): String? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !input[at - 1].isWhitespace()) return null
    val token = input.substring(at + 1)
    if (token.any { it.isWhitespace() }) return null
    return token
}

/** 把正在输入的 `@xxx` 替换成选中的路径，并补一个空格好接着打字 */
internal fun replaceMentionToken(input: String, path: String): String {
    val at = input.lastIndexOf('@')
    if (at < 0) return input
    return input.substring(0, at) + "@$path "
}

/** 一条消息最多带几张图。手机上再多也看不过来，而每张图都实打实吃 token。 */
private const val MAX_IMAGES = 4

/** `@` 补全的输入抖动，避免每敲一个字母就遍历一次目录 */
private const val MENTION_DEBOUNCE_MS = 180L
