package dev.min.code.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.userShellCommand
import dev.min.code.core.claudecode.DraftAttachment
import dev.min.code.core.claudecode.DraftImage
import dev.min.code.core.claudecode.formatUsd
import dev.min.code.core.claudecode.parseSessionCostUsd
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.files.toImportSource
import dev.min.code.ui.theme.InkMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import dev.min.code.core.session.SessionStatus

/** 上下文用到这个比例就变告警色 —— CLI 到这一带会自动压缩，值得提前知道 */
private const val CONTEXT_WARN_RATIO = 0.85f

/** 没有撤回来源时的空事件流。写成常量而不是默认表达式，免得每次重组都换一个实例 */
private val NoWithdrawals: Flow<ComposerDraft> = emptyFlow()

/**
 * Claude 会话页的输入坞：草稿、附件、斜杠命令、粘贴折叠与 `!` 本地执行都在这里。
 * 胶囊本身长什么样在 [ComposerCapsule]，和 Codex 那边共用一只。
 */
@Composable
internal fun ClaudeCodeInputBar(
    session: ClaudeCodeManager.SessionState,
    onSend: (String, List<ClaudeCodeImage>) -> Unit,
    onInterrupt: () -> Unit,
    /** `!` 开头的一行：Min 自己在 rootfs 里跑，不经模型（见 UserShell.kt） */
    onRunShell: (String) -> Unit,
    /** (模型, 是否同时存成新会话默认)。`/model <名字>` 与 CLI 一致按「存成默认」处理 */
    onSetModel: (String?, Boolean) -> Unit,
    onSetPermissionMode: (ClaudeCodePermissionMode) -> Unit,
    onApplyEffort: (String?, Boolean) -> Unit,
    onSetPromptCacheTtl: (String) -> Unit,
    onRequestModels: () -> Unit,
    /** 今天（设备本地日期）的累计花费，跨会话累加 */
    dailyCostUsd: Double = 0.0,
    /** 命令 / 模型说明用中文对照还是 CLI 给的英文原文 */
    chineseDescriptions: Boolean = true,
    onSetChineseDescriptions: (Boolean) -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    onSetCwd: (String) -> Unit = {},
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
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
    /**
     * 输入框拿到 / 失去焦点。会话页要靠它区分"键盘是被划掉的"还是"焦点被会话流的
     * SelectionContainer 拿走了"——后者不能跟着 clearFocus，否则选区当场作废。
     */
    onComposerFocusChange: (Boolean) -> Unit = {},
    /** 杀进程再进同一会话时要恢复的草稿。空 = 从空白开始 */
    restoredDraft: ComposerDraft = ComposerDraft.Empty,
    onDraftChange: (ComposerDraft) -> Unit = {},
    onDraftSnapshot: (ComposerDraft) -> Unit = {},
    /** 按 Esc 撤回的消息，原样退回输入框。一次性事件流，不是状态 */
    withdrawnMessages: Flow<ComposerDraft> = NoWithdrawals,
    /** 占位字换一句（子 agent 视图：「发给 @general-purpose…」）。null = 照常 */
    hintOverride: String? = null,
    /** 停止键跟谁的「在跑」走（子 agent 视图：那个子 agent 在跑才出停止键）。null = 跟会话这一轮 */
    busyOverride: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf(restoredDraft.text) }
    // 面板开关要活得过转屏：recreate 之后 sheet 还开着，人才不会觉得点了没反应。
    // 正文（input）不在这里 saveable——草稿盘管它，两份事实会打架
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 附件独立于输入框文本。之前是把路径直接拼进输入框，于是
    // ①看不出上传了什么文件（只有一行裸路径）②把那行删掉文件其实还在工作区里。
    var attachments by remember {
        mutableStateOf(restoredDraft.attachments.map { Attachment(it.name, it.path) })
    }
    // 图片走 image content block，不落盘、不进工作区，所以和 attachments 分开存
    var images by remember {
        mutableStateOf(restoredDraft.images.map { PendingImage(it.name, ClaudeCodeImage(it.mediaType, it.base64)) })
    }
    // 附件芯片上的 × 会真删工作区里的文件，误触无法挽回 —— 先确认再删
    var pendingAttachmentDelete by remember { mutableStateOf<Attachment?>(null) }
    // 折叠起来的长段粘贴：输入框里只留一个占位符，正文存在这里，发送时还原（见 collapsePaste）
    var pastes by remember { mutableStateOf(restoredDraft.pasteMap) }
    var pasteSeq by remember { mutableIntStateOf(restoredDraft.pasteSeq) }
    var sendSequence by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun currentDraft(): ComposerDraft = ComposerDraft(
        text = input,
        attachments = attachments.map { DraftAttachment(it.name, it.path) },
        images = images.map { DraftImage(it.name, it.block.mediaType, it.block.base64) },
        pastes = pastes.mapKeys { it.key.toString() },
        pasteSeq = pasteSeq,
    )

    LaunchedEffect(input, attachments, images, pastes, pasteSeq) {
        delay(DRAFT_DEBOUNCE_MS)
        onDraftChange(currentDraft())
    }
    // 撤回：把那条消息原样放回框里。用户在那几秒里通常已经在改主意了，
    // 所以**接在**已有内容后面而不是覆盖 —— 覆盖等于把人刚敲的字吃掉。
    LaunchedEffect(withdrawnMessages) {
        withdrawnMessages.collect { draft ->
            input = if (input.isBlank()) draft.text else draft.text + "\n" + input
            images = draft.images.map { PendingImage(it.name, ClaudeCodeImage(it.mediaType, it.base64)) } + images
        }
    }
    DisposableEffect(Unit) {
        // 清后台走进程生命周期，不走页面 ON_STOP：打开文件页也会停掉 Nav 条目。
        val owner = androidx.lifecycle.ProcessLifecycleOwner.get()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) onDraftSnapshot(currentDraft())
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // 切会话也会走到这里。只保存、不拍顺延：空框顺延是「退出 App」的语义，
            // 切到另一个会话时把 carry 打开，会把下一个已有草稿的会话误判成冲突。
            onDraftChange(currentDraft())
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        // 取文件名和开流都要过 ContentProvider —— 文档来自云盘时这一下能卡住几百毫秒，
        // 而这个回调本身跑在主线程上。只把两次 provider 调用挪到 IO，
        // attachments 仍然回到主线程再动
        scope.launch {
            var remaining = uris.size
            uris.forEach { uri ->
                // 和文件页的导入同一个取名 + 开流（toImportSource），
                // 取不到显示名时 SAF 的 `primary:Download/a.txt` 也会被剥成 `a.txt`
                val (name, stream) = withContext(Dispatchers.IO) {
                    uri.toImportSource(context.contentResolver).let { it.name to it.open() }
                }
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
    }

    // 跟系统相册上限走，不再人为卡 4 张。API 33+ 有 MediaStore.getPickImagesMaxLimit()；
    // 更老的系统 Photo Picker 不可用时 androidx 会走 ACTION_OPEN_DOCUMENT 多选，无硬顶。
    val maxImages = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { MediaStore.getPickImagesMaxLimit() }.getOrDefault(Int.MAX_VALUE)
                .coerceAtLeast(2)
        } else {
            Int.MAX_VALUE
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxImages),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val room = (maxImages - images.size).coerceAtLeast(0)
            uris.take(room).forEach { uri ->
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

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    // 回调不在 Composable 作用域里，资源要在这儿先取好
    val photoName = stringResource(R.string.composer_photo)
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val block = onLoadImage(uri)
            if (block != null) {
                images = images + PendingImage(name = photoName, block = block)
            }
            importing = false
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = newCameraUri(context)
            cameraUri = uri
            takePicture.launch(uri)
        }
    }

    val running = session.status == SessionStatus.Running
    // 输入以 "/" 开头时把斜杠命令顶上来（就地补全，比翻设置面板快）
    val slashQuery = input.takeIf { it.startsWith("/") }?.drop(1)?.substringBefore(' ')
    val matchedCommands = remember(slashQuery, session.slashCommands) {
        if (slashQuery == null) emptyList()
        else session.slashCommands.filter { it.name.contains(slashQuery, ignoreCase = true) }.take(6)
    }

    fun composeMessage(): String {
        val body = expandPastes(input, pastes)
        if (attachments.isEmpty()) return body
        val refs = attachments.joinToString(separator = System.lineSeparator()) { it.path }
        return if (body.isBlank()) refs else body + System.lineSeparator() + System.lineSeparator() + refs
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
        sendSequence += 1
        // 终端里 Claude Code 的 bash 模式。附件和图片留着，下一条消息接着发
        userShellCommand(input)?.let { command ->
            onRunShell(command)
            input = ""
            return
        }
        // 带参数的模型命令也必须走控制协议。直接发给无头 CLI 只会得到一条
        // "Set model..." 文本，App 自己的 options/appliedModel 仍然是旧值。
        // CLI 里 `/model <名字>` 等于面板按 Enter（"saved as your default for new sessions"），
        // 所以这里 asDefault = true。
        modelSlashArgument(input)?.let { requested ->
            onSetModel(
                resolveSlashModel(
                    requested,
                    session.relayModels,
                ).takeUnless { it.equals("default", ignoreCase = true) },
                true,
            )
            input = ""
            return
        }
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
        pastes = emptyMap()
        pasteSeq = 0
        onDraftChange(ComposerDraft.Empty)
    }

    // 候选列表收起的那几百毫秒里还要画得出内容，所以记住最后一份非空的
    val lastCommands = remember { LastNonNull(matchedCommands.takeIf { it.isNotEmpty() }) }
    val shownCommands = lastCommands.update(matchedCommands.takeIf { it.isNotEmpty() }).orEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        ComposerChipStrip(visible = attachments.isNotEmpty() || images.isNotEmpty()) {
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
                    // 摘芯片 = 删工作区文件（否则"取消"只是视觉上的），所以先问一句
                    onRemove = { pendingAttachmentDelete = att },
                )
            }
        }

        pendingAttachmentDelete?.let { target ->
            RikkaConfirmDialog(
                show = true,
                title = stringResource(R.string.composer_delete_attachment_title),
                confirmText = stringResource(R.string.common_delete),
                dismissText = stringResource(R.string.common_cancel),
                onConfirm = {
                    attachments = attachments - target
                    onDeleteWorkspaceFile(target.path)
                    pendingAttachmentDelete = null
                },
                onDismiss = { pendingAttachmentDelete = null },
            ) {
                Text(stringResource(R.string.composer_delete_attachment_body, target.name))
            }
        }

        AnimatedVisibility(visible = matchedCommands.isNotEmpty(), enter = InkMotion.expand, exit = InkMotion.collapse) {
            Column {
                SlashCommandList(shownCommands) { picked ->
                    val local = LOCAL_SLASH_HANDLERS[picked.name.lowercase()]
                    if (local != null) {
                        handleLocal(local)
                        input = ""
                    } else {
                        input = "/${picked.name} "
                    }
                }
                InkDivider()
            }
        }

        FileMentionSuggestions(
            text = input,
            enabled = running,
            onSearchFiles = onSearchFiles,
            onTextChange = { input = it },
            showDivider = true,
        )

        // 打了 `!` 就说清这一行会怎么跑：它不发给模型，这和输入框里其余所有东西都不一样
        val shellMode = input.trimStart().let { it.startsWith('!') || it.startsWith('！') }
        AnimatedVisibility(visible = shellMode, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Text(
                stringResource(R.string.composer_shell_hint, session.cwd.ifBlank { "/workspace" }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        val canSend = (input.isNotBlank() || attachments.isNotEmpty() || images.isNotEmpty()) && running
        val usage = composerUsage(session, dailyCostUsd)
        ComposerCapsule(
            value = input,
            onValueChange = { next ->
                // 一次性涌进来的大段文本 = 粘贴。原样留在框里的话，5 行的输入框
                // 会变成一条几百行的滚动条，既看不到自己在写什么，也没法确认贴对了没有
                val collapsed = collapsePaste(input, next, pasteSeq + 1)
                if (collapsed == null) {
                    input = next
                } else {
                    pasteSeq += 1
                    pastes = pastes + (pasteSeq to collapsed.pasted)
                    input = collapsed.text
                }
            },
            enabled = running,
            busy = busyOverride ?: session.busy,
            hint = hintOverride,
            canSend = canSend,
            onSend = { submit() },
            onInterrupt = onInterrupt,
            onFocusChange = onComposerFocusChange,
            plus = {
                ComposerPlus(
                    enabled = running && !importing,
                    busy = importing,
                    canPickImage = images.size < maxImages,
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onPickImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhoto = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            val uri = newCameraUri(context)
                            cameraUri = uri
                            takePicture.launch(uri)
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    contextRatio = usage.ratio,
                    contextText = usage.contextText,
                    contextWarn = usage.warn,
                    costText = usage.costText,
                    modelText = session.modelChipLabel(),
                    modeText = "${session.permissionMode.label} · ${session.effortChipLabel()}",
                    onRefreshUsage = onRefreshUsage,
                    onOpenSettings = { openSettings(null) },
                )
            },
        )
    }

    if (settingsOpen) {
        ClaudeCodeSettingsSheet(
            session = session,
            initialSection = settingsSection,
            chineseDescriptions = chineseDescriptions,
            onSetChineseDescriptions = onSetChineseDescriptions,
            onDismiss = { settingsOpen = false },
            onSetModel = { model, asDefault -> onSetModel(model, asDefault); settingsOpen = false },
            onSetPermissionMode = { onSetPermissionMode(it); settingsOpen = false },
            onApplyEffort = { effort, ultra -> onApplyEffort(effort, ultra); settingsOpen = false },
            onSetPromptCacheTtl = { onSetPromptCacheTtl(it); settingsOpen = false },
            onSetCwd = { onSetCwd(it); settingsOpen = false },
            onListCwd = onListCwd,
            onCreateCwd = onCreateCwd,
            onPickCommand = { input = "/$it "; settingsOpen = false },
        )
    }
}

/**
 * 「+」菜单里那一栏用量的取值。
 *
 * 三个数收在一起算：要不要画这一栏，取决于它们**合起来**有没有内容。
 * 比例不写成分数——`159k / 200k` 要读两个数再自己算，而一圈填充度看一眼就懂；
 * 那两个数留给菜单里的标题行，想看的人在那儿看得到。
 */
private data class ComposerUsage(
    val ratio: Float?,
    val contextText: String?,
    val warn: Boolean,
    val costText: String?,
)

@Composable
private fun composerUsage(
    session: ClaudeCodeManager.SessionState,
    dailyCostUsd: Double,
): ComposerUsage {
    val used = session.contextTokens
    // CLI 对不认识的 id 会报 200k。Fable 5.1 是原生 1M，信 CLI 就会画出 97k/200k。
    val limit = ClaudeCodeModelCatalog.effectiveContextLimit(
        session.contextLimit,
        session.appliedModel ?: session.currentModel ?: session.model,
    )
    val ratio = if (used != null && limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else null
    return ComposerUsage(
        ratio = ratio,
        // 带上分母：用户问"默认是不是 1M"时，这里就是答案
        contextText = used?.let { "${it / 1000}k/${ClaudeCodeModelCatalog.formatContextWindow(limit)}" },
        // 逼近上限转朱砂 —— CLI 到那一带会自动压缩上下文，这件事发生前用户有权知道
        warn = (ratio ?: 0f) >= CONTEXT_WARN_RATIO,
        costText = costLabel(dailyCostUsd, session.costText?.let { parseSessionCostUsd(it) }),
    )
}

/**
 * 底栏那个金额。
 *
 * 主角是**今日合计**：一天里开开停停好几个会话，只显示本次会话的话，每退出一次
 * 那个数字就归零重算，"今天烧了多少"永远看不到（累计逻辑见 ClaudeCodeCostLedger）。
 * 本次会话的花费跟在后面一段 —— 判断"这一轮值不值"时还是要看它，只是不再是主角；
 * 两个数一样时（今天只跑过这一个会话）就不重复写第二遍。
 */
internal fun costParts(dailyUsd: Double, sessionUsd: Double?): Pair<Double, Double?>? = when {
    dailyUsd > 0.0 && sessionUsd != null && sessionUsd < dailyUsd - 1e-6 ->
        dailyUsd to sessionUsd

    dailyUsd > 0.0 -> dailyUsd to null
    // 台账还没记上（这一轮的 get_session_cost 先回来了）时退回会话值：
    // 今天既然只有这一个会话，它就是今日合计
    sessionUsd != null -> sessionUsd to null
    else -> null
}

/**
 * 上面那个判断的文案版。
 *
 * 拆成两半是因为「显示哪几个数」是有分支的业务逻辑（值得单测，见
 * ClaudeCodeCostLabelTest），而「怎么写这句话」是本地化的事。混在一起的话，
 * 要么文案抽不出去，要么单测得跑 Robolectric。
 */
@Composable
internal fun costLabel(dailyUsd: Double, sessionUsd: Double?): String? {
    val (primary, session) = costParts(dailyUsd, sessionUsd) ?: return null
    return if (session != null) {
        stringResource(R.string.composer_cost_both, formatUsd(primary), formatUsd(session))
    } else {
        stringResource(R.string.composer_cost_daily, formatUsd(primary))
    }
}

/** 已导入工作区、待随下一条消息发出的文件 */
private data class Attachment(val name: String, val path: String)

/** 待随下一条消息发出的图片。不落盘 —— 截图这种一次性图片没有留在工作区的理由。 */
private data class PendingImage(val name: String, val block: ClaudeCodeImage)

@Composable
private fun SlashCommandList(
    commands: List<ClaudeCodeManager.SlashCommand>,
    onPick: (ClaudeCodeManager.SlashCommand) -> Unit,
) {
    Column(modifier = Modifier.heightIn(max = 200.dp)) {
        commands.forEach { cmd ->
            SlashSuggestionRow(command = "/${cmd.name}", description = cmd.description, onClick = { onPick(cmd) })
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

/** `/model fable` 的参数；空参数仍由 [localSlashTarget] 打开模型面板。 */
internal fun modelSlashArgument(input: String): String? {
    val trimmed = input.trim()
    val command = trimmed.substringBefore(' ').lowercase()
    if (command != "/model" && command != "/models") return null
    val argument = trimmed.substringAfter(' ', "").trim()
    return argument.takeIf { it.isNotBlank() && !it.any(Char::isWhitespace) }
}

/**
 * 将 `/model fable5.1` 这类便捷写法解析成中转站返回的真实 id。
 * CLI 的 `fable` 是别名，但 `fable5.1` 通常不是合法别名；只有真实 id 才能固定版本。
 * `[1m]` 后缀保留给 Claude Code 的长上下文选择。
 */
internal fun resolveSlashModel(
    requested: String,
    relayModels: List<ClaudeCodeManager.RelayModel>,
): String {
    val longContext = requested.endsWith("[1m]", ignoreCase = true)
    val base = requested.removeSuffix("[1m]")
    val normalized = base.lowercase().filter(Char::isLetterOrDigit)
    // 无版本号时保留 CLI 别名（如 `fable` / `opus`），不要被中转站列表里的
    // 某个具体版本抢先匹配。
    if (normalized.isBlank() || normalized.none(Char::isDigit)) return requested
    val matched = relayModels.firstOrNull { relay ->
        val relayNormalized = relay.id.lowercase().filter(Char::isLetterOrDigit)
        relayNormalized == normalized ||
            relayNormalized.removePrefix("claude") == normalized ||
            relayNormalized.endsWith(normalized)
    }?.id ?: run {
        // 没打开过模型面板时列表可能还没拉取；Fable 的公开 canonical id 有稳定格式，
        // 先给 CLI 一个可用的固定版本入口。
        Regex("(?i)^fable[- ]?(\\d+)(?:[.]?(\\d+))?$").matchEntire(base)?.let { match ->
            "claude-fable-${match.groupValues[1]}" +
                match.groupValues[2].takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        }
    } ?: return requested
    return if (longContext) "$matched[1m]" else matched
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

// ---------------------------------------------------------------------------
// 长段粘贴折叠（被 ClaudeCodePasteTest 覆盖）
//
// 手机上贴一份报错日志、一段配置、半个文件是常事，而输入框只有 5 行高：原样留在里面
// 等于把「我现在要说什么」这件事整个淹掉，而且滑到底去按发送键都要好几下。
// 做法和 Claude Code 自己的终端一致 —— 框里只留一行占位符，正文存在旁边，
// 发送前再还原。占位符是普通文本，用户可以整段删掉（那就等于取消这次粘贴），
// 也可以在它前后接着写字。
// ---------------------------------------------------------------------------

/** 超过这个字数就折叠。低于它的粘贴（一个 URL、一行命令）留在框里更直观 */
internal const val PASTE_COLLAPSE_CHARS = 600

/** 或者超过这个行数。短但很多行的东西（一份 diff、一段栈）同样会撑爆输入框 */
internal const val PASTE_COLLAPSE_LINES = 10

/** 折叠的结果：[text] 是替换后的输入框内容，[pasted] 是被收起来的原文 */
internal data class CollapsedPaste(val text: String, val pasted: String)

/**
 * 判断这次 `onValueChange` 是不是「一次贴进来一大段」，是就换成占位符。
 *
 * 只认**纯插入**：新文本 = 旧文本在某处插进一段。有删改的编辑一律不碰 ——
 * 那多半是用户自己在改字，误折叠会把他刚写的东西藏起来。
 *
 * 返回 null 表示不该折叠，调用方原样接受 [next]。
 */
internal fun collapsePaste(previous: String, next: String, id: Int): CollapsedPaste? {
    if (next.length <= previous.length) return null
    var prefix = 0
    while (prefix < previous.length && previous[prefix] == next[prefix]) prefix++
    var suffix = 0
    while (
        suffix < previous.length - prefix &&
        previous[previous.length - 1 - suffix] == next[next.length - 1 - suffix]
    ) suffix++
    // 前后缀没有把旧文本完全盖住 = 不是纯插入
    if (prefix + suffix != previous.length) return null

    val end = next.length - suffix
    val inserted = next.substring(prefix, end)
    if (!shouldCollapsePaste(inserted)) return null
    return CollapsedPaste(
        text = next.substring(0, prefix) + pastePlaceholder(id, inserted) + next.substring(end),
        pasted = inserted,
    )
}

internal fun shouldCollapsePaste(text: String): Boolean =
    text.length >= PASTE_COLLAPSE_CHARS || text.count { it == '\n' } + 1 >= PASTE_COLLAPSE_LINES

/**
 * 占位符长这样：`[paste #1 · 87 lines · 3.4k chars]`。
 *
 * 带上行数和字数是为了能**核对贴对了没有** —— 只写一句"粘贴文本"的话，贴错剪贴板
 * 要等模型答非所问才发现。
 *
 * ## 为什么这一行**不**走 string 资源
 *
 * 这是整个文件里唯一一处故意保留硬编码英文的地方，理由有三条：
 *
 * 1. **它必须跨语言稳定。** 占位符由这里生成、由 [PASTE_PLACEHOLDER] 认回去。
 *    文案跟着语言走的话，用户在中文界面粘贴、随后把 App 切成英文再发送，
 *    正则就认不出这一段了 —— 发出去的是一行谁也看不懂的占位符，原文彻底丢失。
 *    界面语言是随时可改的，而输入框里的草稿会活过这次改动。
 * 2. **它是内部协议，不是内容。** 发送前 [expandPastes] 一定会把它换回原文，
 *    模型永远看不到这一行；用户也只在输入框里短暂见到它。
 * 3. **保持纯函数才留得住单测。** 取资源要 Context，而这个函数是在
 *    `onValueChange` 里调的，不在 Composable 作用域；硬塞 Resources 进来
 *    会把 ClaudeCodePasteTest 从纯 JVM 拖进 Robolectric，为一行占位符不值当。
 *
 * 真正要紧的是那几个**数字**，它们本来就不随语言变。
 */
internal fun pastePlaceholder(id: Int, text: String): String {
    val lines = text.count { it == '\n' } + 1
    val size = if (text.length >= 1000) {
        String.format(java.util.Locale.US, "%.1fk chars", text.length / 1000.0)
    } else {
        "${text.length} chars"
    }
    return "[$PASTE_TAG #$id · $lines lines · $size]"
}

/** 占位符的锚点。和界面语言无关，见 [pastePlaceholder] 的第 1 条 */
private const val PASTE_TAG = "paste"

/**
 * 占位符匹配。**只按 id 认领**，中间那段摘要允许对不上 —— 用户可能在占位符里
 * 误删了几个字，那时也该还原成原文，而不是把一行乱码发出去。
 */
private val PASTE_PLACEHOLDER = Regex("""\[$PASTE_TAG #(\d+)[^\]]*]""")

/** 发送前把占位符换回原文。被删掉的占位符自然就不还原，等于取消那次粘贴。 */
internal fun expandPastes(input: String, pastes: Map<Int, String>): String {
    if (pastes.isEmpty()) return input
    return PASTE_PLACEHOLDER.replace(input) { match ->
        pastes[match.groupValues[1].toIntOrNull()] ?: match.value
    }
}

/** 拍照落在 cache/，FileProvider 已经把 cache-path 暴露出去。拍完立刻读进 image block，不进工作区。 */
private fun newCameraUri(context: android.content.Context): Uri {
    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
    val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** 草稿落盘抖动。杀进程走 [onDraftSnapshot]，不等这段。 */
private const val DRAFT_DEBOUNCE_MS = 280L
