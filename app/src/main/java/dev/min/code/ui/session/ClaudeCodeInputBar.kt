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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.claudecode.ComposerDraft
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
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.PendingResume
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.composerKeys
import dev.min.code.core.engine.codex.CodexStateMapper
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.SlashCommandView
import dev.min.code.core.session.SessionStatus

/** 没有撤回来源时的空事件流。写成常量而不是默认表达式，免得每次重组都换一个实例 */
private val NoWithdrawals: Flow<ComposerDraft> = emptyFlow()

/**
 * Claude 会话页的输入坞：草稿、附件、斜杠命令、粘贴折叠与 `!` 本地执行都在这里。
 * 胶囊本身长什么样在 [ComposerCapsule]，和 Codex 那边共用一只。
 */
@Composable
internal fun ClaudeCodeInputBar(
    session: AgentSessionState,
    /** 这个会话所属引擎的能力位：■ / ▶ 怎么摆、撤回能不能用都看它（设计 §3.8） */
    capabilities: EngineCapabilities,
    /** 子 agent 页上正看着的那个线程；左槽和发送键对应它（■ = 停它、▶ = 它的续跑） */
    viewingThread: AgentThread?,
    /** 用户按过 ▶、续跑请求已发出但回合还没起来（VM 持有） */
    pendingResume: PendingResume?,
    onResume: (ResumeOffer) -> Unit,
    onSend: (String, List<ImageInput>) -> Unit,
    onInterrupt: () -> Unit,
    /** `!` 开头的一行：Min 自己在 rootfs 里跑，不经模型（见 UserShell.kt） */
    onRunShell: (String) -> Unit,
    /** 设置面板（Claude / Codex 两张）要用的全部「改设置」回调；`/model <名字>` 也走其中的 onSetModel，与 CLI 一致按「存成默认」处理 */
    settingsActions: SessionSettingsActions,
    /** Codex 连接配置里的默认模型，设置里的模型栏要列出来 */
    codexProfileModel: String? = null,
    /** `/new`：另起一条会话（Codex 的本地命令） */
    onNewSession: () -> Unit = {},
    onRequestModels: () -> Unit,
    /** 今天（设备本地日期）的累计花费，跨会话累加 */
    dailyCostUsd: Double = 0.0,
    /** 命令 / 模型说明用中文对照还是 CLI 给的英文原文 */
    chineseDescriptions: Boolean = true,
    onRefreshUsage: () -> Unit = {},
    /** 移除附件时把文件从工作区删掉，否则"删掉输入框里那行路径"并不会真的取消上传 */
    onDeleteWorkspaceFile: (path: String) -> Unit = {},
    /** 把系统选中的文件导入沙箱，回调返回沙箱内路径（失败为 null） */
    onImportFile: (fileName: String, stream: java.io.InputStream, onDone: (String?) -> Unit) -> Unit =
        { _, _, done -> done(null) },
    /** `@` 提及的文件补全，返回 guest 侧绝对路径 */
    onSearchFiles: suspend (String) -> List<String> = { emptyList() },
    /** 相册/截图 → 已缩放编码好的 image block；失败返回 null */
    onLoadImage: suspend (Uri) -> ImageInput? = { null },
    /** 打开本地接管的交互式命令面板（/mcp、/agents…） */
    onOpenLocalCommand: (LocalSlash) -> Unit = {},
    /**
     * 输入框拿到 / 失去焦点。会话页要靠它区分"键盘是被划掉的"还是"焦点被会话流的
     * SelectionContainer 拿走了"——后者不能跟着 clearFocus，否则选区当场作废。
     */
    onComposerFocusChange: (Boolean) -> Unit = {},
    /** 设置面板里 MCP 段 / 信任段的状态与动作；引擎没有这两段时页面传 null */
    extras: SessionExtras? = null,
    /** 杀进程再进同一会话时要恢复的草稿。空 = 从空白开始 */
    restoredDraft: ComposerDraft = ComposerDraft.Empty,
    onDraftChange: (ComposerDraft) -> Unit = {},
    onDraftSnapshot: (ComposerDraft) -> Unit = {},
    /** 按 Esc 撤回的消息，原样退回输入框。一次性事件流，不是状态 */
    withdrawnMessages: Flow<ComposerDraft> = NoWithdrawals,
    /** 占位字换一句（子 agent 视图：「发给 @general-purpose…」）。null = 照常 */
    hintOverride: String? = null,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf(restoredDraft.text) }
    // 面板开关要活得过转屏：recreate 之后 sheet 还开着，人才不会觉得点了没反应。
    // 正文（input）不在这里 saveable——草稿盘管它，两份事实会打架
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var codexSection by rememberSaveable { mutableStateOf<CodexSettingsSection?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 附件独立于输入框文本。之前是把路径直接拼进输入框，于是
    // ①看不出上传了什么文件（只有一行裸路径）②把那行删掉文件其实还在工作区里。
    var attachments by remember {
        mutableStateOf(restoredDraft.attachments.map { Attachment(it.name, it.path) })
    }
    // 图片走 image content block，不落盘、不进工作区，所以和 attachments 分开存
    var images by remember {
        mutableStateOf(restoredDraft.images.map { PendingImage(it.name, ImageInput(it.mediaType, it.base64)) })
    }
    // 附件芯片上的 × 会真删工作区里的文件，误触无法挽回 —— 先确认再删
    var pendingAttachmentDelete by remember { mutableStateOf<Attachment?>(null) }
    // 折叠起来的长段粘贴：输入框里只留一个占位符，正文存在这里，发送时还原（见 collapsePaste）
    var pastes by remember { mutableStateOf(restoredDraft.pasteMap) }
    var pasteSeq by remember { mutableIntStateOf(restoredDraft.pasteSeq) }
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
            images = draft.images.map { PendingImage(it.name, ImageInput(it.mediaType, it.base64)) } + images
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

    val running = session.accepting
    // 输入以 "/" 开头时把斜杠命令顶上来（就地补全，比翻设置面板快）
    val slashQuery = input.takeIf { it.startsWith("/") }?.drop(1)?.substringBefore(' ')
    val matchedCommands = remember(slashQuery, session.slashCommands) {
        if (slashQuery == null) emptyList()
        else session.slashCommands.filter { it.name.contains(slashQuery, ignoreCase = true) }.take(SLASH_MENU_MAX)
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
        codexSection = null
        settingsOpen = true
    }

    /** 本地接管的斜杠命令：会话设置类的开 sheet，配置编辑类的交给页面开专门的面板 */
    fun handleLocal(target: LocalSlash) {
        if (target == LocalSlash.NEW) {
            onNewSession()
            return
        }
        if (EngineUi.settingsSheet(session.ref.engine) == SettingsSheetKind.Codex) {
            // Codex 的面板有自己的分区（没有 MCP / 缓存 / 信任，多一个思考摘要）
            onRequestModels()
            codexSection = target.toCodexSection()
            settingsOpen = true
            return
        }
        val section = target.toSection(capabilities)
        if (section != null) openSettings(section) else onOpenLocalCommand(target)
    }

    fun submit() {
        // 去向由 routeSubmit 判（顺序即语义，见它的注释）；这里只管按结果动手
        when (val route = routeSubmit(input, capabilities, session.slashCommands, session.settings.relayModels.map { ClaudeCodeManager.RelayModel(it.id) })) {
            // 终端里 Claude Code 的 bash 模式。附件和图片留着，下一条消息接着发
            is SubmitRoute.Shell -> {
                onRunShell(route.command)
                input = ""
            }
            is SubmitRoute.SetModel -> {
                settingsActions.onSetModel(route.model, true)
                input = ""
            }
            is SubmitRoute.Local -> {
                handleLocal(route.target)
                input = ""
                // 附件留着，用户设置完接着发
            }
            SubmitRoute.SendMessage -> {
                onSend(composeMessage(), images.map { it.block })
                input = ""
                attachments = emptyList()
                images = emptyList()
                pastes = emptyMap()
                pasteSeq = 0
                onDraftChange(ComposerDraft.Empty)
            }
        }
    }

    val closingSettingsActions = remember(settingsActions) {
        settingsActions.closingAfterApply { settingsOpen = false }
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
                    // 与手打回车同一个判定：按引擎认（Codex 的 /permissions 是权限档，不是 CC 的 hooks 面板）
                    val local = localSlashTarget("/${picked.name}", capabilities, session.slashCommands)
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
        val shellMode = EngineUi.userShell(capabilities) && input.trimStart().let { it.startsWith('!') || it.startsWith('！') }
        AnimatedVisibility(visible = shellMode, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Text(
                stringResource(R.string.composer_shell_hint, session.cwd.ifBlank { "/workspace" }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        val inputFilled = input.isNotBlank() || attachments.isNotEmpty() || images.isNotEmpty()
        val canSend = inputFilled && running
        val keys = composerKeys(
            turn = session.turn,
            resume = session.resume,
            viewingThread = viewingThread,
            pendingResume = pendingResume,
            capabilities = capabilities,
            inputFilled = inputFilled,
            queuedCount = session.queuedCount,
            stopping = session.stopping,
            readOnly = session.readOnly,
        )
        val usage = composerUsage(session, if (EngineUi.dailyCost(session.ref.engine)) dailyCostUsd else 0.0)
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
            busy = viewingThread?.running ?: session.busy,
            hint = hintOverride,
            keys = keys,
            onResume = onResume,
            canSend = canSend,
            onSend = { submit() },
            onInterrupt = onInterrupt,
            onFocusChange = onComposerFocusChange,
            plus = {
                ComposerPlus(
                    enabled = running && !importing,
                    busy = importing,
                    canPickImage = images.size < maxImages,
                    // Codex 的 localImage 还没接：没有拍照 / 相册这两项（摆一个永远点不动的菜单项只会让人反复去点）
                    showImageItems = capabilities.images,
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
                    modelText = session.settings.modelChipLabel(),
                    modeText = "${modeTitle(session.ref.engine, session.settings.mode)} · ${session.settings.effortChipLabel()}",
                    onRefreshUsage = onRefreshUsage,
                    onOpenSettings = { openSettings(null) },
                    onRecap = if (capabilities.recap) extras?.let { { it.requestRecap() } } else null,
                )
            },
        )
    }

    if (settingsOpen && EngineUi.settingsSheet(session.ref.engine) == SettingsSheetKind.Codex) {
        CodexSettingsSheet(
            session = session,
            profileModel = codexProfileModel,
            initialSection = codexSection,
            onDismiss = { settingsOpen = false },
            // 点了就算数：值存在 manager 里、下一轮 turn/start 带上，不重启进程，所以不用像别的引擎那样点完就收起
            onSetModel = { settingsActions.onSetModel(it, false) },
            onSetPermission = settingsActions.onSetPermissionMode,
            onSetEffort = { settingsActions.onApplyEffort(it, false) },
            onSetSummary = { settingsActions.onSetEngineOption(CodexStateMapper.OPTION_SUMMARY, it.orEmpty()) },
            onSetCwd = settingsActions.onSetCwd,
            onListCwd = settingsActions.onListCwd,
            onCreateCwd = settingsActions.onCreateCwd,
            onOpenConnection = { settingsOpen = false; settingsActions.onOpenConnection() },
        )
    } else if (settingsOpen) {
        ClaudeCodeSettingsSheet(
            session = session,
            capabilities = capabilities,
            initialSection = settingsSection,
            chineseDescriptions = chineseDescriptions,
            onDismiss = { settingsOpen = false },
            // 选定即收起面板；换语言、列目录这类不算「选定」，原样透传（见 closingAfterApply）
            actions = closingSettingsActions,
            onPickCommand = { input = "/$it "; settingsOpen = false },
            extras = extras,
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
    session: AgentSessionState,
    dailyCostUsd: Double,
): ComposerUsage {
    val limit = EngineUi.contextLimit(
        session.ref.engine,
        session.usage.contextLimit,
        session.settings.applied.model ?: session.settings.requestedModel ?: session.reportedModel,
    )
    val line = statusLineOf(session.usage, limit)
    // 引擎自己算好的花费（Min：内核按目录价格记的整树花费、币种原样）优先；否则是 CC 的老算法：CLI 原文解析成美元 + 今日合计
    val cost = session.usage.costLabel?.let { stringResource(R.string.composer_cost_session, it) }
        ?: costLabel(dailyCostUsd, session.usage.costText?.let { parseSessionCostUsd(it) })
    val tokens = if (line.tokensIn != null && line.tokensOut != null) {
        stringResource(R.string.composer_tokens, compactTokens(line.tokensIn), compactTokens(line.tokensOut))
    } else null
    return ComposerUsage(
        ratio = line.ratio,
        // 带上分母：用户问"默认是不是 1M"时，这里就是答案
        contextText = line.contextUsed?.let { "${it / 1000}k/${ClaudeCodeModelCatalog.formatContextWindow(limit)}" },
        warn = line.warn,
        costText = listOfNotNull(cost, tokens).joinToString(" · ").ifEmpty { null },
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
private data class PendingImage(val name: String, val block: ImageInput)

/** 斜杠菜单最多列几条：够把内置命令加常见的几十个 skill 都放进可滚动的菜单，又不至于把整个清单倒进来 */
private const val SLASH_MENU_MAX = 40

@Composable
private fun SlashCommandList(
    commands: List<SlashCommandView>,
    onPick: (SlashCommandView) -> Unit,
) {
    // 菜单高度封顶 200dp 时第六行会被切掉一截、后面的又够不着（模拟器验收见到 14 条只露 5 条）：封顶不变，改成能滚
    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        commands.forEach { cmd ->
            SlashSuggestionRow(command = "/${cmd.name}", description = cmd.description, onClick = { onPick(cmd) })
        }
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
