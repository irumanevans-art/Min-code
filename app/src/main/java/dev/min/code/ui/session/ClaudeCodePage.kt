package dev.min.code.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.CHECKPOINT_TOOLS
import dev.min.code.core.claudecode.subagentTypeName
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.session.PathScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.min.code.core.engine.awaitedToolName
import dev.min.code.core.engine.switcherThreads
import dev.min.code.ui.components.BrandMark
import dev.min.code.ui.components.LocalFrost
import dev.min.code.ui.components.frostSource
import dev.min.code.ui.components.frostBand
import dev.min.code.ui.components.rememberFrostState
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.HeroCanvas
import dev.min.code.ui.components.SeaSurface
import dev.min.code.ui.components.HorizonLine
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.TideLine
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.LoadingScreen
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.PaperDisc
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.preview.LocalPreviewPane
import dev.min.code.ui.viewer.ArtifactViewerPane
import dev.min.code.ui.viewer.FeedbackDraftPane
import dev.min.code.ui.viewer.ViewerInbox
import dev.min.code.ui.setup.SetupPage
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalUrls
import dev.min.code.ui.components.rememberExternalBrowserOpener
import dev.min.code.util.writeClipboardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Browser
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.changedFiles
import dev.min.code.core.session.undoCandidates
import dev.min.code.core.session.SessionStatus

/**
 * Claude Code 页：官方 claude CLI 跑在工作区 Rootfs 里，stream-json 协议桥接为界面。
 *
 * 版式是**工作日志**而不是聊天：一条左轨道贯穿会话流，每条记录挂一个标记符
 * （见 [TranscriptEntry]）。理由写在那里 —— 简单说，Claude Code 干的是按顺序做事，
 * 不是对话，而工具活动才是这一页的主体内容。
 *
 * - 左侧抽屉 = 会话列表 + 工作区文件（数据直接读 CLI 自己写的 transcript）
 * - 底部 = 上下文余量 / 输入 / 一行会话设置摘要
 * - 计划、权限确认 = 按需出现的横幅与底部 sheet，不常驻占位
 *
 * 物质分配（见 DESIGN.md）：顶栏是带金箔的品牌字；停止是朱砂；正在跑的东西是湛；
 * 你写的话是金。其余全是纸和墨。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaudeCodePage(
    vm: SessionVM = koinViewModel(),
    listVm: SessionListVM = koinViewModel(),
    toolsVm: WorkspaceToolsVM = koinViewModel(),
    maintenanceVm: MaintenanceVM = koinViewModel(),
    configVm: ClaudeConfigVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val setup by maintenanceVm.setup.collectAsStateWithLifecycle()
    // 正在长的那段正文 / 思考不在 [AgentSessionState] 里，而在 [LiveStream]（[stream]）：流式期间只有会话流里
    // 那两个条目读它，整页不会跟着每个 token 重组。
    // 剩下的逐消息跳变是本轮输出 token 数（每条消息结束时涨一次）：顶栏、抽屉、输入坞、起始面板都不关心它，
    // 所以它们读的是剥掉这个计数之后的 [session]，按值判等，计数涨的时候不动；只有会话流末尾那行计时 / 吐字量读完整的 [liveSession]
    // （见 SessionContent）。两者从同一个快照派生，不会出现「流式条目已经收起、定稿条目还没进列表」那一帧空白。
    val liveSession = vm.session.collectAsStateWithLifecycle()
    val stream = vm.stream.collectAsStateWithLifecycle()
    val session by remember(liveSession) {
        derivedStateOf(structuralEqualityPolicy()) { liveSession.value.withoutLiveCounters() }
    }
    val sessions by listVm.sessions.collectAsStateWithLifecycle()
    val drawerSessions = remember(sessions, liveSession.value.ref) {
        val shown = liveSession.value.ref
        sessions.map { entry ->
            // Min 的行按引用对（它们的 isActive 在列表 VM 里没法定）；Claude 的行只有显示的确实是 Claude 才亮
            val active = EngineUi.isEntryActive(entry.ref, entry.isActive, shown)
            if (active == entry.isActive) entry else entry.copy(isActive = active)
        }
    }
    val maintenance by maintenanceVm.maintenance.collectAsStateWithLifecycle()
    val useNpmMirror by maintenanceVm.useNpmMirror.collectAsStateWithLifecycle()
    val runtime by toolsVm.runtime.collectAsStateWithLifecycle()
    val localServiceList by toolsVm.localServiceList.collectAsStateWithLifecycle()
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()
    val dailyCostUsd by vm.dailyCostUsd.collectAsStateWithLifecycle()
    // 英文界面下这张表整个不生效，见 isChineseUi —— 在这里合掉，
    // 下游每一处消费点（输入坞的命令表、会话设置里那一列）就都不必各自再判一次
    val zhDescriptionSetting by vm.chineseDescriptions.collectAsStateWithLifecycle()
    val chineseDescriptions = zhDescriptionSetting && isChineseUi()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val minEnabled by vm.minEnabled.collectAsStateWithLifecycle()
    val engineAvailability by vm.availability.collectAsStateWithLifecycle()
    val startChoices by vm.startChoices.collectAsStateWithLifecycle()
    val startEngine by vm.startEngine.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val codexStatus by vm.codexStatus.collectAsStateWithLifecycle()
    val codexProfile by vm.codexProfile.collectAsStateWithLifecycle()
    val codexProfiles by vm.codexProfiles.collectAsStateWithLifecycle()
    // 上次用的是 Codex：开始面板一出来就读它的安装 / 登录状态（选中它那一刻读的在 rememberStartEngine）
    LaunchedEffect(startEngine) { if (startEngine == EngineKind.Codex) vm.refreshCodex() }
    var showCodexConnection by rememberSaveable { mutableStateOf(false) }
    // 环境状态读完就重扫磁盘会话：原来 refresh() 末尾直接调 refreshSessions()，拆成两个 VM 后由页面转发
    LaunchedEffect(maintenanceVm, listVm) {
        maintenanceVm.sessionsStale.collect { listVm.refreshSessions() }
    }
    val toaster = LocalToaster.current
    LaunchedEffect(vm) {
        vm.signalNotices.collect { toaster.show(it.text, if (it.isError) ToastType.Error else ToastType.Success) }
    }
    LaunchedEffect(vm) {
        vm.sessionOpenRequests.collect { ref ->
            listVm.refreshSessions()
            vm.openSession(ref)
        }
    }
    LaunchedEffect(vm) {
        vm.extras.notices.collect { toaster.show(it.text, if (it.isError) ToastType.Error else ToastType.Success) }
    }
    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        toaster.show(text, ToastType.Error)
        vm.dismissNotice()
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 抽屉一开就收键盘：输入框的焦点不会因为抽屉盖上来而丢，键盘会一直立在那里挡住抽屉下半截
    val focusManager = LocalFocusManager.current
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) focusManager.clearFocus() }
    // 切到后台时丢掉焦点：否则系统会在下次回到前台时把键盘弹回来
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, focusManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) focusManager.clearFocus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 用户把键盘划掉之后，输入框还握着焦点，光标会一直闪。键盘刚收起时清掉焦点。
    //
    // **必须先确认焦点还在输入框上**。键盘收起有两种来路：用户划掉（焦点没动，该清），
    // 和焦点被别处拿走（不该清）。后者正是"长按会话流选中文字"：SelectionContainer 先
    // requestFocus，输入框失焦 → 键盘开始收 → 几帧后这里 clearFocus，把 SelectionContainer
    // 刚拿到的焦点一起清掉。Compose 的 SelectionManager 没有焦点就 `hasFocus=false`，
    // 选区当场作废，于是长按要么根本选不中，要么弹出来的工具栏只剩一个「全选」没有「复制」
    // （copy 只在 isNonEmptySelection() 时才挂上去）。切去别的页面再回来之所以"好了"，
    // 只是 imeWasVisible 跟着重建成 false，下一次长按逃过这一刀而已。
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible && composerFocused) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showMaintenance by rememberSaveable { mutableStateOf(false) }
    var showRuntime by rememberSaveable { mutableStateOf(false) }
    val previewSlot by toolsVm.previewSlot.collectAsStateWithLifecycle()

    // 通知权限（Android 13+ 要运行时申请）在环境就绪那一刻要一次：后台等审批、任务完成、
    // 会话中断全靠通知，没有它用户切出去就是聋的。放在向导之后而不是启动时——先让人看到
    // 这个 App 是干什么的，再要权限
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(setup.ready) {
        if (setup.ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 会话跑起来后周期性拉一次用量/花费，状态条才有东西显示。
    // 会话列表也在这里重扫：CLI 的自动拟名（ai-title）是首轮结束后**异步**写入
    // transcript 的，result 帧落地那一瞬文件里往往还没有，所以立刻扫一次、再晚几秒扫一次。
    LaunchedEffect(session.status, session.busy) {
        if (session.status == SessionStatus.Running && !session.busy) {
            vm.refreshUsage()
            vm.refreshPlan()
            listVm.refreshSessions()
            delay(2_500)
            if (session.status == SessionStatus.Running) {
                listVm.refreshSessions()
            }
        }
    }

    // 宽屏（平板 / 横屏）：可收起、可拖宽、可拉满盖住主区的会话栏。
    // 窄屏仍是模态抽屉。840dp 是 Material 的 expanded 断点。
    val wide = LocalConfiguration.current.screenWidthDp >= 840 && setup.ready
    // 展开态与宽度（dp）在转屏 / 进程重建后仍保留。
    var wideSidebarOpen by rememberSaveable { mutableStateOf(true) }
    var wideSidebarWidthDp by rememberSaveable { mutableFloatStateOf(SIDEBAR_DEFAULT_DP) }
    // 进程已经没了、但还有一个 ▶ 能把它接回来（CC 的 CLI 被杀、Codex 的 app-server 退出、重开停在半路的旧会话）：
    // 页面不能退回开始面板，否则 ▶ 无处可按、对话也看不到了
    val live = setup.ready &&
        (session.accepting ||
            session.status == SessionStatus.Starting ||
            session.resume != null)

    val openSessionList: () -> Unit = {
        focusManager.clearFocus()
        if (wide) {
            wideSidebarOpen = true
        } else {
            scope.launch { drawerState.open() }
        }
    }
    val toggleSessionList: () -> Unit = {
        focusManager.clearFocus()
        if (wide) {
            wideSidebarOpen = !wideSidebarOpen
        } else {
            scope.launch {
                if (drawerState.isOpen) drawerState.close() else drawerState.open()
            }
        }
    }
    // 从侧栏点进某项：窄屏关抽屉；宽屏若已拉满盖住主区则收回常规宽度，露出会话页。
    val afterSidebarNav: () -> Unit = {
        if (wide) {
            if (wideSidebarWidthDp >= SIDEBAR_COVER_DP - 0.5f) {
                wideSidebarWidthDp = SIDEBAR_DEFAULT_DP
            }
        } else {
            scope.launch { drawerState.close() }
        }
    }

    // 导出用的字头在这里取好：toTranscriptText 在 onClick 里跑，那儿拿不到资源
    val transcriptLabels = rememberTranscriptLabels()
    val sessionTransfer = rememberSessionTransfer(listVm, sessions)
    // 会话内容页下命令的出口，按 ViewModel 建一份记住（见 SessionActions）。
    // Codex 连接方式面板住在本函数顶层，设置里的入口经它回来开
    val sessionActions = remember(vm, toolsVm) {
        SessionActions.from(vm, toolsVm, onOpenCodexConnection = { showCodexConnection = true })
    }

    val drawer: @Composable (paneWidth: Dp?) -> Unit = { paneWidth ->
        ClaudeCodeSessionDrawer(
            permanent = wide,
            visible = if (wide) wideSidebarOpen else drawerState.isOpen,
            paneWidth = paneWidth,
            // 两边（旧注册表 / 统一注册表）各有一个活跃项，哪行亮由页面按当前会话的引用盖章
            sessions = drawerSessions,
            onNewSession = {
                afterSidebarNav()
                vm.newSession()
            },
            onOpenSession = { ref ->
                afterSidebarNav()
                vm.openSession(ref)
            },
            onDeleteSession = { ref -> listVm.deleteSession(ref, onActiveDeleted = vm::resetComposerDraft) },
            onPinSession = listVm::pinSession,
            onRenameSession = listVm::renameSession,
            onSetCategory = listVm::setSessionCategory,
            categories = listVm.sessionCategories(),
            onExportSession = sessionTransfer.export,
            onEndSession = vm::endSession,
            onBranchSession = { ref ->
                listVm.branchSession(
                    ref,
                    onBranched = { branched ->
                        afterSidebarNav()
                        vm.openSession(branched)
                    },
                    onRefused = { reason -> toaster.show(reason ?: context.getString(R.string.session_branch_unsupported), ToastType.Error) },
                )
            },
            onImportSession = sessionTransfer.import,
            onOpenFiles = {
                afterSidebarNav()
                navController.navigate(Screen.Files)
            },
            // 「系统」里的这几项不关抽屉：面板盖在抽屉上，页面压在它上面，
            // 关掉 / 返回时回到的都是抽屉的「系统」页（见 ClaudeCodeSessionDrawer）
            onOpenTerminal = { navController.navigate(Screen.Terminal) },
            onOpenRuntime = {
                toolsVm.refreshNetworkSnapshot()
                showRuntime = true
            },
            onOpenSettings = { navController.navigate(Screen.Settings) },
            onOpenProviders = {
                afterSidebarNav()
                navController.navigate(Screen.Providers)
            },
            onOpenMaintenance = {
                maintenanceVm.loadEnvironment()
                showMaintenance = true
            },
            // 没内容时不给这一行 —— 一个复制出来是空字符串的按钮只会让人以为坏了
            onCopyTranscript = if (session.items.isNotEmpty()) {
                {
                    afterSidebarNav()
                    context.writeClipboardText(session.items.toTranscriptText(transcriptLabels))
                    toaster.show(context.getString(R.string.common_copied), ToastType.Success)
                }
            } else null,
        )
    }

    AdaptiveDrawer(
        wide = wide,
        wideOpen = wideSidebarOpen,
        wideWidthDp = wideSidebarWidthDp,
        onWideWidthDp = { wideSidebarWidthDp = it },
        onWideOpen = { wideSidebarOpen = it },
        drawerState = drawerState,
        gesturesEnabled = setup.ready,
        drawer = drawer,
    ) {
        val previewHandler = rememberLocalPreviewUriHandler { url ->
            toolsVm.openPreview(url)
        }
        CompositionLocalProvider(LocalUriHandler provides previewHandler) {
        if (live) {
            SessionContent(
                session = session,
                liveSession = liveSession,
                stream = stream,
                vm = vm,
                toolsVm = toolsVm,
                configVm = configVm,
                actions = sessionActions,
                dailyCostUsd = dailyCostUsd,
                chineseDescriptions = chineseDescriptions,
                // 宽屏也始终给汉堡：展开=收起，收起=展开（对齐 Kimi / DeepSeek）
                showMenu = setup.ready,
                onOpenDrawer = if (wide) toggleSessionList else openSessionList,
                onOpenPlan = { showPlan = true },
                onComposerFocusChange = { composerFocused = it },
                codexProfileModel = codexProfile.model.trim().takeIf { it.isNotBlank() },
            )
        } else {
            Scaffold(
                topBar = {
                    InkTopBar(
                        title = "Min",
                        brand = true,
                        subtitle = session.subtitle(),
                        active = session.busy,
                        navigationIcon = {
                            when {
                                setup.ready -> {
                                    InkIconButton(
                                        icon = HugeIcons.Menu03,
                                        contentDescription = stringResource(
                                            if (wide) R.string.session_sidebar_toggle
                                            else R.string.session_drawer,
                                        ),
                                        onClick = if (wide) toggleSessionList else openSessionList,
                                    )
                                }
                                else -> {
                                    InkIconButton(
                                        icon = HugeIcons.Settings02,
                                        contentDescription = stringResource(R.string.settings_title),
                                        onClick = { navController.navigate(Screen.Settings) },
                                    )
                                }
                            }
                        },
                        actions = {
                            InkIconButton(
                                icon = HugeIcons.Refresh01,
                                contentDescription = stringResource(R.string.session_refresh_status),
                                onClick = { maintenanceVm.refresh() },
                            )
                        },
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { innerPadding ->
                Box(Modifier.padding(innerPadding)) {
                    when {
                        setup.loading -> LoadingScreen(
                            status = stringResource(R.string.session_loading),
                            detail = setup.cliVersion?.let { "claude $it" },
                        )
                        !setup.ready -> SetupPage(onReady = maintenanceVm::refresh)
                        else -> StartPanel(
                            session = session,
                            cliVersion = setup.cliVersion,
                            onStart = vm::start,
                            defaultCwd = vm.defaultCwd(),
                            onListCwd = vm::listCwdFolders,
                            onCreateCwd = vm::createCwdFolder,
                            connected = connected,
                            onOpenProviders = { navController.navigate(Screen.Providers) },
                            sessions = sessions,
                            onContinue = { entry -> vm.openSession(entry.ref) },
                            engines = StartEngines(
                                minVisible = minEnabled,
                                availability = engineAvailability,
                                minChoices = startChoices,
                                initial = startEngine,
                                onChoose = vm::rememberStartEngine,
                                codex = CodexStart(
                                    status = codexStatus,
                                    onInstall = vm::installCodex,
                                    // 登录要在终端里做（codex login --device-auth），去终端页
                                    onOpenTerminal = { navController.navigate(Screen.Terminal) },
                                    onOpenConnection = { showCodexConnection = true },
                                ),
                            ),
                        )
                    }
                }
            }
        }
        } // CompositionLocalProvider LocalUriHandler
    }

    session.headInteraction?.let { pending ->
        InteractionSheet(pending = pending, onAnswer = { answer -> vm.answer(pending.id, answer) })
    }
    if (showCodexConnection) {
        CodexConnectionSheet(
            profile = codexProfile,
            profiles = codexProfiles,
            onSave = {
                vm.saveCodexProfile(it)
                showCodexConnection = false
            },
            onActivate = vm::activateCodexProfile,
            onAdd = vm::addCodexProfile,
            onManage = {
                showCodexConnection = false
                navController.navigate(Screen.Providers)
            },
            onDismiss = { showCodexConnection = false },
        )
    }
    // 信任卡 / MCP 批准卡 / 登录确认：谁排在前面见 SessionExtrasOverlays
    SessionExtrasOverlays(vm.extras)

    if (showPlan) {
        ClaudeCodePlanSheet(plan = session.plan, onDismiss = { showPlan = false })
    }

    if (showMaintenance) {
        ClaudeCodeMaintenanceSheet(
            state = maintenance,
            // 只数进程还活着的：注册表里会留着刚结束的壳，按 size 数会把更新按钮锁死
            liveSessionCount = liveSessions.count { it.keepAlive },
            onDismiss = {
                showMaintenance = false
                maintenanceVm.dismissMaintenanceError()
            },
            onCheckUpdate = maintenanceVm::checkCliUpdate,
            onUpdateCli = maintenanceVm::updateCli,
            useNpmMirror = useNpmMirror,
            onSetUseNpmMirror = maintenanceVm::setUseNpmMirror,
            onUpgradeApt = maintenanceVm::upgradeApt,
            onReinstallNode = maintenanceVm::reinstallNode,
            onOpenWorkspace = {
                showMaintenance = false
                // 去的是另一处（工作区文件），不是「系统」的下一层：抽屉跟着收起
                afterSidebarNav()
                navController.navigate(Screen.Files)
            },
        )
    }

    if (showRuntime) {
        ClaudeCodeRuntimeSheet(
            snapshot = runtime.snapshot,
            loading = runtime.loading,
            services = localServiceList,
            expandedLogId = runtime.expandedLogId,
            logOf = toolsVm::logOf,
            onDismiss = { showRuntime = false },
            onRefresh = toolsVm::refreshNetworkSnapshot,
            onStopService = toolsVm::stopLocalService,
            onToggleLog = toolsVm::toggleServiceLog,
            // 进程表「打开」= 按预览位键，不是第二套 WebView；这张表和抽屉由下面的让位统一收掉
            onOpenPreview = toolsVm::openPreview,
        )
    }

    // 预览位一展开，会话页上盖着的东西一律让开：「系统」下一层的面板收掉、抽屉关上。
    // 预览是会话页的东西 —— 人点「打开」（进程表、rootfs 开网页那张卡、终端里的本机链接）是要看它，
    // 关掉预览该落在会话页上，而不是露出一个停在「系统」页的抽屉（抽屉一关，「系统」页也跟着回第一层）。
    // 挂在展开这个状态上而不是各个入口：展开只可能是人点的（见 PreviewSlot），
    // 从终端页翻回来时会话页是重新组合的，状态在、入口的回调早没了。权限 / 提问面板不动，那是在等人答
    LaunchedEffect(previewSlot.expanded) {
        if (!previewSlot.expanded) return@LaunchedEffect
        showRuntime = false
        showMaintenance = false
        showPlan = false
        afterSidebarNav()
    }

    // 预览位展开：关掉只藏，url 仍在 slot 里，铬件键可再开
    val slotUrl = previewSlot.url
    if (previewSlot.expanded && slotUrl != null) {
        LocalPreviewPane(
            url = slotUrl,
            onCollapse = toolsVm::collapsePreview,
        )
    }

    // 模型交来的 Artifact 预览（工具调用即「在预览位打开」，所以到了就开）与诊断包草稿（只在本机，导出与丢弃都由人点）。
    // 草稿面板关掉只是藏：记住关的时候看过哪几份（进程级，页面重建不重弹），有没看过的新草稿才再出现
    val artifactFile by ViewerInbox.artifact.collectAsState()
    val drafts by ViewerInbox.drafts.collectAsState()
    val dismissedDrafts by ViewerInbox.dismissedDrafts.collectAsState()
    artifactFile?.let { file ->
        ArtifactViewerPane(file = file, onClose = ViewerInbox::closeArtifact)
    }
    if (drafts.any { it.id !in dismissedDrafts }) {
        FeedbackDraftPane(drafts = drafts, onClose = { ViewerInbox.dismissDrafts(drafts.map { it.id }) })
    }
}

/**
 * Markdown / 链接：loopback 走应用内预览，其它走外部浏览器（Custom Tab）。
 * 由 [SessionContent] 或外层 CompositionLocal 注入。
 */
@Composable
internal fun rememberLocalPreviewUriHandler(
    onPreview: (String) -> Unit,
): UriHandler {
    val openExternal = rememberUpdatedState(rememberExternalBrowserOpener())
    val latest = rememberUpdatedState(onPreview)
    return remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LocalUrls.isLoopbackHttp(uri)) {
                    latest.value(LocalUrls.normalizeLoopback(uri))
                } else {
                    openExternal.value(uri)
                }
            }
        }
    }
}

/**
 * 宽屏：可收起 / 拖宽 / 拉满盖住主区的分栏（参考平板上的 Kimi、DeepSeek）。
 * 窄屏：模态抽屉。
 *
 * 宽度单位是 dp，存在调用方的 rememberSaveable 里；拖到 [SIDEBAR_SNAP_COLLAPSE_DP]
 * 以下松手会收起，拖到接近全宽会吸附到 [SIDEBAR_COVER_DP]（语义上的「盖住右边」）。
 */
private const val SIDEBAR_DEFAULT_DP = 300f
private const val SIDEBAR_MIN_DP = 220f
/** 大于屏宽比例后视为「盖住」；用很大的 dp 哨兵，实际 clamp 到 maxWidth */
private const val SIDEBAR_COVER_DP = 100_000f
private const val SIDEBAR_SNAP_COLLAPSE_DP = 160f
/** 视觉竖线宽度 */
private const val SIDEBAR_HANDLE_LINE_DP = 12f
/** 实际可点可拖的热区（比竖线宽，不然手指很难按住） */
private const val SIDEBAR_HANDLE_HIT_DP = 28f

@Composable
private fun AdaptiveDrawer(
    wide: Boolean,
    wideOpen: Boolean,
    wideWidthDp: Float,
    onWideWidthDp: (Float) -> Unit,
    onWideOpen: (Boolean) -> Unit,
    drawerState: androidx.compose.material3.DrawerState,
    gesturesEnabled: Boolean,
    drawer: @Composable (paneWidth: Dp?) -> Unit,
    content: @Composable () -> Unit,
) {
    if (wide) {
        WideSidebarScaffold(
            open = wideOpen,
            widthDp = wideWidthDp,
            onWidthDp = onWideWidthDp,
            onOpen = onWideOpen,
            drawer = drawer,
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = { drawer(null) },
            // 抽屉后面那层是墨，不是 Material 的黑纱
            scrimColor = MaterialTheme.sea.scrim,
            content = content,
        )
    }
}

@Composable
private fun WideSidebarScaffold(
    open: Boolean,
    widthDp: Float,
    onWidthDp: (Float) -> Unit,
    onOpen: (Boolean) -> Unit,
    drawer: @Composable (paneWidth: Dp?) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val sea = MaterialTheme.sea
    val resizeDesc = stringResource(R.string.session_sidebar_resize)
    // 拖动中的实时宽度：写在这里而不是父级 saveable。
    // 以前 pointerInput 的 key 里带了 widthDp，每拖一帧父级 setState → key 变 →
    // 手势块被取消重建，看起来就像「一次只能挪几毫米」。
    var dragWidthDp by remember { mutableFloatStateOf(Float.NaN) }
    var dragging by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxDp = maxW.value
        // 未盖住时给主区至少留一点；盖住态允许到全宽（把手仍占 hit 宽）
        val softMax = (maxDp - 120f).coerceAtLeast(SIDEBAR_MIN_DP)
        val coverPaneDp = (maxDp - SIDEBAR_HANDLE_HIT_DP).coerceAtLeast(SIDEBAR_MIN_DP)
        val settledCovering = open && widthDp >= SIDEBAR_COVER_DP - 0.5f
        val settledDp = when {
            !open -> 0f
            settledCovering -> coverPaneDp
            else -> widthDp.coerceIn(SIDEBAR_MIN_DP, softMax)
        }
        // 跟手：拖的时候直接用 dragWidth，不走弹簧；松手 / 点菜单收展才动画
        val displayTarget = if (dragging && !dragWidthDp.isNaN()) dragWidthDp else settledDp
        val animatedWidth by animateDpAsState(
            targetValue = displayTarget.dp,
            animationSpec = if (dragging) {
                androidx.compose.animation.core.snap()
            } else {
                InkMotion.spatial()
            },
            label = "sidebar-width",
        )
        val covering = open && (
            (dragging && !dragWidthDp.isNaN() && dragWidthDp >= softMax) ||
                (!dragging && settledCovering)
            )
        // 给 pointerInput 用的稳定引用：key 里绝不能放 widthDp / dragWidth
        val onWidthDpRef = rememberUpdatedState(onWidthDp)
        val onOpenRef = rememberUpdatedState(onOpen)
        val widthDpRef = rememberUpdatedState(widthDp)

        Row(Modifier.fillMaxSize()) {
            if (animatedWidth > 0.dp) {
                Box(
                    Modifier
                        .width(animatedWidth)
                        .fillMaxHeight(),
                ) {
                    drawer(animatedWidth)
                }
            }
            // 分界把手：热区 28dp，竖线仍细。按住横拖跟手调宽。
            if (open) {
                Box(
                    Modifier
                        .width(SIDEBAR_HANDLE_HIT_DP.dp)
                        .fillMaxHeight()
                        .semantics { contentDescription = resizeDesc }
                        // key 只跟屏宽相关；宽度变化不能拆掉手势
                        .pointerInput(maxDp, softMax, coverPaneDp) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    val start = widthDpRef.value
                                    dragWidthDp = when {
                                        start >= SIDEBAR_COVER_DP - 0.5f -> coverPaneDp
                                        else -> start.coerceIn(SIDEBAR_MIN_DP, softMax)
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val deltaDp = dragAmount / density.density
                                    val cur = if (dragWidthDp.isNaN()) {
                                        widthDpRef.value.coerceIn(0f, coverPaneDp)
                                    } else {
                                        dragWidthDp
                                    }
                                    dragWidthDp = (cur + deltaDp).coerceIn(0f, coverPaneDp)
                                },
                                onDragEnd = {
                                    val end = if (dragWidthDp.isNaN()) settledDp else dragWidthDp
                                    dragging = false
                                    dragWidthDp = Float.NaN
                                    when {
                                        end < SIDEBAR_SNAP_COLLAPSE_DP -> {
                                            onOpenRef.value(false)
                                            onWidthDpRef.value(SIDEBAR_DEFAULT_DP)
                                        }
                                        end >= softMax -> onWidthDpRef.value(SIDEBAR_COVER_DP)
                                        else -> onWidthDpRef.value(
                                            end.coerceIn(SIDEBAR_MIN_DP, softMax),
                                        )
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragWidthDp = Float.NaN
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 热区透明，中间画细线；拖动时线略加重
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val lineHalf = (SIDEBAR_HANDLE_LINE_DP.dp.toPx()) / 2f
                        drawLine(
                            color = sea.ink.copy(alpha = if (dragging) 0.22f else 0.14f),
                            start = Offset(cx, 0f),
                            end = Offset(cx, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val mid = size.height / 2f
                        drawRoundRect(
                            color = sea.ink.copy(alpha = if (dragging) 0.45f else 0.28f),
                            topLeft = Offset(cx - lineHalf.coerceAtMost(1.5f.dp.toPx()), mid - 22.dp.toPx()),
                            size = Size(
                                width = (lineHalf * 2f).coerceAtLeast(3.dp.toPx()),
                                height = 44.dp.toPx(),
                            ),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
            if (!covering) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content()
                }
            }
        }
        // 全覆盖时主区被侧栏盖住，右上角留一枚菜单把宽度收回常规值
        if (covering && !dragging) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(10.dp),
            ) {
                PaperDisc {
                    InkIconButton(
                        icon = HugeIcons.Menu03,
                        contentDescription = stringResource(R.string.session_sidebar_toggle),
                        onClick = { onWidthDp(SIDEBAR_DEFAULT_DP) },
                    )
                }
            }
        }
    }
}

/**
 * 去掉本轮正在长的输出 token 计数（当前这条消息的累计 + 由它加总出来的本轮总数），见 ClaudeCodePage 顶部。
 * 空闲时 [AgentSessionState.turn] 为 null，计数也不会被谁读，原样返回。
 */
private fun AgentSessionState.withoutLiveCounters(): AgentSessionState {
    val turn = turn ?: return this
    if (turn.outputTokensCurrent == 0 && usage.turnOutputTokens == null) return this
    return copy(turn = turn.copy(outputTokensCurrent = 0), usage = usage.copy(turnOutputTokens = null))
}

@Composable
private fun AgentSessionState.subtitle(): String = when {
    stopping -> stringResource(R.string.session_settings_busy_stopping)
    settings.applying -> stringResource(R.string.session_settings_busy)
    status == SessionStatus.Running -> {
        // 模型名是机器产物，原样；工具计数的量词是人话，走资源
        val tools = if (toolCount > 0) {
            stringResource(R.string.session_subtitle_tools, toolCount)
        } else {
            null
        }
        val running = stringResource(R.string.session_meta_live)
        listOfNotNull(reportedModel, tools).joinToString(" · ").ifBlank { running }
    }

    status == SessionStatus.Starting -> stringResource(R.string.session_status_starting_cli)
    status == SessionStatus.Closed -> stringResource(R.string.session_status_closed)
    status == SessionStatus.Failed -> stringResource(R.string.session_status_failed)
    // 产品名，不翻
    else -> "Claude Code"
}

// ---------------------------------------------------------------------------
// 会话内容
// ---------------------------------------------------------------------------

@Composable
private fun SessionContent(
    /** 剥掉了本轮输出 token 计数的会话状态，见 ClaudeCodePage 顶部的说明 */
    session: AgentSessionState,
    /** 完整状态。只在最小的作用域里读（当前轮进度），别在函数体顶层读 */
    liveSession: State<AgentSessionState>,
    /** 逐字增量（正在长的正文 / 思考）。只在最小的作用域里读（流式条目），别在函数体顶层读 */
    stream: State<LiveStream>,
    vm: SessionVM,
    toolsVm: WorkspaceToolsVM,
    configVm: ClaudeConfigVM,
    /** 下命令的出口；读状态（Flow）仍直接读 [vm]，见 [SessionActions] */
    actions: SessionActions,
    dailyCostUsd: Double,
    chineseDescriptions: Boolean,
    onOpenPlan: () -> Unit,
    showMenu: Boolean = true,
    onOpenDrawer: () -> Unit = {},
    /** 输入框的焦点变化，一路报到页面顶层的"键盘收起就清焦点"那条效应里 */
    onComposerFocusChange: (Boolean) -> Unit = {},
    codexProfileModel: String? = null,
) {
    val listState = rememberLazyListState()
    // 「跳到改动的那一轮」要滚列表，滚完就不跟底了（见 [onJumpTo]）
    val scope = rememberCoroutineScope()
    // 只在「有没有流式内容」翻转时才让本函数重组，正文每长一截不算
    val streaming by remember(stream) {
        derivedStateOf { stream.value.let { it.text.isNotBlank() || it.thinking.isNotBlank() } }
    }
    // 工具卡的字头在组合期取好传下去：TranscriptItem 的调用方（搜索、导出）不是 Composable
    val transcriptLabels = rememberTranscriptLabels()
    val composerDraft by vm.composerDraft.collectAsStateWithLifecycle()
    // 设置里「添加附加目录」被内核拒绝（已在工作目录里、不存在……）：把原因告诉用户
    val toaster = LocalToaster.current
    LaunchedEffect(vm) { vm.addDirectoryRejections.collect { toaster.show(it, ToastType.Error) } }
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val pendingResume by vm.pendingResume.collectAsStateWithLifecycle()
    val activeKey by vm.activeSessionKey.collectAsStateWithLifecycle()
    val showLiveTurn = !streaming && (
        session.busy ||
            session.stopping ||
            session.settings.applying ||
            session.status == SessionStatus.Starting ||
            session.turn?.retry != null ||
            // 后台 shell 不算「这一轮在跑」：它们跨轮活着，归底栏的「N shell」管
            session.runningThreads.isNotEmpty()
    )

    // 连续的思考/工具活动折成块，跑完自动收起（见 [TranscriptBlock]）。
    // 分组是纯计算，但会话长起来之后每帧重算不划算，按条目数和最后一条的身份缓存
    val blocks = remember(session.items) { groupTranscript(session.items) }
    val lastIndex = blocks.lastIndex
    // 生成中新落进来的工具卡、提示长出来而不是整块冒出来（见 [FreshEntries]）。
    // 换会话时整个换一份：另一条会话里的 id 一条都不该算见过
    val freshEntries = remember(activeKey) { FreshEntries() }
    freshEntries.armed = session.busy
    freshEntries.tail = remember(session.items) {
        session.items.takeLast(FRESH_TAIL).mapTo(HashSet()) { it.id }
    }

    // 本会话改过的文件（底部浮层用）。纯函数，只读 items；会话长起来之后按 items 缓存
    // 能撤销 = 编辑类工具且存过快照：批量问一次磁盘，结果出来之前谁都不给撤销钮。
    // CC 里子 agent 的编辑不走快照（见 [undoCandidates]），面板上只能看、能跳转，不能撤；Min 的编辑记录覆盖整棵树，它们也能撤
    val candidateIds = remember(session.items, capabilities.rewind) { undoCandidates(session.items, includeSubagents = capabilities.rewind) }
    var snapshotted by remember(activeKey) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(candidateIds) { snapshotted = actions.checkpointed(candidateIds) }
    val changed = remember(session.items, snapshotted, transcriptLabels) {
        changedFiles(
            session.items,
            canUndo = { it.toolUseId in snapshotted },
            // 老会话回放里没有 tool_use_result，Edit / Write 卡的 editDiff 是空的：按入参现算
            fallbackDiff = { boundedDiffOf(it.name, it.input, transcriptLabels) },
        )
    }
    val changedLabels = rememberChangedFilesLabels()
    // onUndo 不在组合里跑，文件名兜底在这里取好
    val fallbackFileName = stringResource(R.string.changed_files_fallback_name)
    // 撤销过的 toolUseId（面板和工具卡共用同一种 id）：撤销只动文件、不动对话历史，所以这件事只在本页面会话内记得
    // —— 重开会话就忘了，那时文件本来也已经被改回去了（快照可以重复 restore，状态是幂等的）。
    // **还原成功之后才记**，失败的不记
    var undoneIds by remember(activeKey) { mutableStateOf(emptySet<String>()) }

    // 用户手动开合过的块（key = 块内第一条的 id）。null = 没动过，跟默认走。
    // 提在这里而不是各块内部：LazyColumn 会把滚出屏幕的条目连同它的 remember 一起回收，
    // 状态放在条目里的话，往回滑一趟展开的块就自己合上了
    val manualExpanded = remember { mutableStateMapOf<String, Boolean>() }

    // 正在看哪个子 agent（发起它的 Agent 调用的 toolUseId），null = main。按会话记：换会话回到 main。
    // 切换条和子 agent 视图的数据都从聊天流 + 任务表现推（SubagentThreads.kt），不另存
    var selectedAgent by rememberSaveable(activeKey) { mutableStateOf<String?>(null) }
    val agentThreads = session.threads
    val viewedAgent = agentThreads.firstOrNull { it.threadId == selectedAgent }

    // 交互式斜杠命令（/mcp、/agents、/memory…）打开的配置面板。它们改的是 Rootfs 里的
    // 配置文件，和「会话设置」那张 sheet 是两回事，所以状态提在这里而不是输入栏内部。
    var configCommand by rememberSaveable { mutableStateOf<LocalSlash?>(null) }

    // 终端浮层。和会话同一个 rootfs 里的真 bash，见 [ClaudeCodeTerminalSheet]
    var showTerminal by rememberSaveable { mutableStateOf(false) }
    // 底栏「N shell」点开的面板。按会话记：换了会话不该还开着上一个会话的 shell
    var showShells by rememberSaveable(activeKey) { mutableStateOf(false) }
    // 任务条上「团队 · N」点开的团队页（成员、共享清单、消息流）。按会话记，理由同上
    var showTeam by rememberSaveable(activeKey) { mutableStateOf(false) }
    // 任务条上「◎ 目标」点开的详情（条件、状态、最近理由、清除）。按会话记，理由同上
    var showGoal by rememberSaveable(activeKey) { mutableStateOf(false) }
    val shells = session.tasks
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // 贴底跟随：在底就跟，往上翻了就不动，规则和时序见 [TranscriptFollow]。
    // 换会话时重置成跟随，新打开的会话照旧从底部读起
    val follow = rememberTranscriptFollow(listState)
    LaunchedEffect(activeKey) { follow.reset() }
    // 盯的是列表布局而不是正文长度：不必在这里读逐 token 变的状态，SessionContent 也就不跟着重组
    FollowTailEffect(listState, follow)

    // 键盘弹起时把尾巴重新顶到底。
    //
    // LazyColumn 的滚动位置锚在**第一个可见项**上，所以视口从下方缩掉一截时，列表
    // 一动不动，底部内容直接被盖住 —— 现象就是"贴着底看正文，一点输入框，最后几行没了"。
    // imePadding 只负责把输入栏抬上来，它管不了这件事。
    //
    // 盯 viewportSize 而不是 ime 高度：分屏、转屏、折叠屏展开都是同一个问题，一个信号全覆盖。
    //
    // **必须是 scrollBy，不能是 scrollToItem。** 键盘动画的每一帧都会走到这里，而
    // scrollToItem 是把锚点整个挪到最后一项再往回填视口 —— LazyColumn 得反向重新测量、
    // 重组一整屏条目。尾部是几屏 Markdown 时这一下并不便宜，每帧做一次就是 120 帧掉成个位数。
    // scrollBy 只动锚点的偏移量，已经组好的条目原地复用，代价和手指拖动一模一样。
    //
    // 只处理"视口变小"：变大（键盘收起）时 LazyColumn 自己会把越界的滚动位置夹回来，
    // 这时再跟着 scrollBy 反而会把内容从底部拽走。
    LaunchedEffect(listState) {
        var previous = listState.layoutInfo.viewportSize.height
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { height ->
                val shrunk = previous - height
                previous = height
                if (follow.following && shrunk > 0) listState.scrollBy(shrunk.toFloat())
            }
    }

    val density = LocalDensity.current
    // 量按钮行 / 输入坞（含系统栏）。列表留白只垫铬件，内容才能滚进纸色里。
    // 纸色比铬件多出一截（见 [frostBand]），切线处收到 0，才没有硬边。
    var topChromePx by remember { mutableIntStateOf(with(density) { 72.dp.roundToPx() }) }
    var bottomChromePx by remember { mutableIntStateOf(with(density) { 96.dp.roundToPx() }) }
    val topReserve = with(density) { topChromePx.toDp() }
    val bottomReserve = with(density) { bottomChromePx.toDp() }
    val frost = rememberFrostState()
    // 挂着权限请求时，那张卡要显示成「等你批准」而不是转圈（见 [LocalAwaitingToolUseId]）
    val awaitingToolUseId = awaitingToolUseId(
        items = session.items,
        pendingToolName = session.headInteraction?.awaitedToolName,
        pendingToolUseId = session.headInteraction?.toolUseId,
    )

    CompositionLocalProvider(
        LocalFrost provides frost,
        LocalAwaitingToolUseId provides awaitingToolUseId,
        LocalFreshEntries provides freshEntries,
        LocalOpenSubagent provides { toolUseId: String -> selectedAgent = toolUseId },
        LocalRewindActions provides (if (capabilities.rewind) vm.extras.rewindActions else null),
        LocalWithdrawQueued provides (if (EngineUi.tapToWithdraw(capabilities, session.ref.engine)) actions.withdrawQueued else null),
    ) {
    Box(Modifier.fillMaxSize().imePadding()) {
        // 会话流铺满，顶栏 / 输入框浮在上面。铬件高度量完再垫进列表，字不会被挡住。
        val blank = blocks.isEmpty() && !streaming
        Box(Modifier.fillMaxSize().frostSource(frost)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = blank,
            enter = fadeIn(InkMotion.effect()),
            exit = fadeOut(tween(600, easing = InkMotion.Ease)),
        ) {
            BlankPage(starting = session.status == SessionStatus.Starting)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // **不能加 verticalArrangement 间距**：条目之间一留白，左轨道就断成一节一节。
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topReserve,
                bottom = bottomReserve,
            ),
        ) {
                itemsIndexed(blocks, key = { _, block -> block.key }) { index, block ->
                    val isFirst = index == 0
                    // 有流式内容时最后一条不是尾巴，轨道要继续往下延伸
                    val isLast = index == lastIndex && !streaming && !showLiveTurn
                    // 新条目淡入（落墨）：只做出现，不做位移。
                    // 流式/忙时关掉 animateItem——每来一截 token 都做布局动画 = 首帧卡、越聊越顿。
                    val itemMod = if (session.busy || streaming) {
                        Modifier
                    } else {
                        Modifier.animateItem(
                            fadeInSpec = tween(280, easing = InkMotion.Ease),
                            placementSpec = null,
                            fadeOutSpec = null,
                        )
                    }
                    Box(itemMod) {
                        TranscriptBlockEntry(
                            block = block,
                            isFirst = isFirst,
                            isLast = isLast,
                            live = session.busy && index == lastIndex,
                            manualExpanded = manualExpanded,
                            labels = transcriptLabels,
                            onRevert = { id -> actions.revertToolCall(id) { undoneIds = undoneIds + id } },
                            canRevert = { it in snapshotted },
                        )
                    }
                }
                if (streaming) {
                    item(key = "streaming") {
                        // 整页只有这里和下面的当前轮进度逐 token 读完整状态
                        val live = stream.value
                        Column {
                            if (live.thinking.isNotBlank()) {
                                ThinkingEntry(
                                    text = live.thinking,
                                    id = "streaming-thinking",
                                    isFirst = blocks.isEmpty(),
                                    isLast = live.text.isBlank() && !showLiveTurn,
                                    streaming = true,
                                )
                            }
                            if (live.text.isNotBlank()) {
                                AssistantEntry(
                                    text = live.text,
                                    isFirst = false,
                                    isLast = !showLiveTurn,
                                    streaming = true,
                                )
                            }
                        }
                    }
                }
                if (showLiveTurn) {
                    item(key = "live-turn") {
                        // 流式内容一落成正式条目，这一行就接着出现在尾部 —— 长出来，
                        // 别把"思考完了"那一刻变成整行往下一跳
                        GrowIn(enabled = true) {
                            LiveTurnEntry(
                                session = liveSession.value,
                                isFirst = blocks.isEmpty() && !streaming,
                                isLast = true,
                            )
                        }
                    }
                }
        }
        // 子 agent 视图：一张纸盖在主会话流上。主列表一直在底下、状态不动，所以选回 main 时
        // 滚动位置原样还在；在两个子 agent 之间切换是淡入淡出
        AnimatedContent(
            targetState = viewedAgent?.threadId,
            transitionSpec = { fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effect()) },
            label = "subagentView",
        ) { shownId ->
            val shown = agentThreads.firstOrNull { it.threadId == shownId }
            if (shown != null) {
                SubagentConversation(
                    thread = shown,
                    labels = transcriptLabels,
                    pendingInteraction = session.headInteraction,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topReserve, bottom = bottomReserve),
                    modifier = Modifier.background(MaterialTheme.sea.paper),
                )
            }
        }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .frostBand(fromTop = true, chrome = topReserve),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topChromePx = it.height },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // 底边贴圆钮下沿：纸色从这条切线往屏沿收。不裁，Min 的白垫要涨出来。
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 0.dp)
                    .graphicsLayer { clip = false },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMenu) {
                    PaperDisc {
                        InkIconButton(
                            icon = HugeIcons.Menu03,
                            contentDescription = stringResource(R.string.session_sidebar_toggle),
                            onClick = onOpenDrawer,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                BrandMark()
                Spacer(Modifier.weight(1f))
                // 预览位：始终在。有 loopback 服务才有效；关掉是藏，不是拆。
                // 不要用 Globe（抽屉「进程」已占用）。
                val preview by toolsVm.previewSlot.collectAsStateWithLifecycle()
                val previewArmed = !preview.url.isNullOrBlank()
                Spacer(Modifier.width(6.dp))
                PaperDisc {
                    Box {
                        InkIconButton(
                            icon = HugeIcons.Browser,
                            contentDescription = stringResource(
                                if (previewArmed) R.string.session_preview
                                else R.string.session_preview_empty,
                            ),
                            onClick = {
                                if (!previewArmed) return@InkIconButton
                                focusManager.clearFocus()
                                actions.togglePreview()
                            },
                            enabled = previewArmed,
                            tint = if (preview.expanded && previewArmed) {
                                MaterialTheme.sea.seaDeep
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (previewArmed && !preview.expanded) {
                            Seal(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            )
                        }
                    }
                }
                // 终端：和会话同一个 Linux 里的一个真 bash。抽屉里那个入口留着，
                // 但"边看 agent 跑边自己敲一条命令"是会话页上的动作，要够得着。
                // 搜索只在会话记录抽屉里——进行页不再放放大镜。
                Spacer(Modifier.width(6.dp))
                PaperDisc {
                    InkIconButton(
                        icon = HugeIcons.ComputerTerminal01,
                        contentDescription = stringResource(R.string.session_terminal),
                        onClick = {
                            focusManager.clearFocus()
                            showTerminal = true
                        },
                    )
                }
                if (session.accepting || session.stopping) {
                    Spacer(Modifier.width(6.dp))
                    PaperDisc {
                        InkIconButton(
                            icon = HugeIcons.Stop,
                            contentDescription = stringResource(R.string.session_stop),
                            onClick = { actions.stop() },
                            tint = MaterialTheme.sea.vermilion,
                            busy = session.stopping,
                            enabled = !session.stopping,
                        )
                    }
                }
            }
            val lastViewed = remember { LastNonNull(viewedAgent) }
            val headerThread = lastViewed.update(viewedAgent)
            AnimatedVisibility(visible = viewedAgent != null, enter = InkMotion.expand, exit = InkMotion.collapse) {
                headerThread?.let { SubagentViewHeader(it, allThreads = agentThreads, onBack = { selectedAgent = null }) }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .frostBand(fromTop = false, chrome = bottomReserve),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomChromePx = it.height },
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 改动过的文件：收起态是一条细条，点开成可拖的浮层（不挡会话流）
                ChangedFilesLayer(
                    files = changed,
                    undoneIds = undoneIds,
                    labels = changedLabels,
                    onJumpTo = { itemId ->
                        val index = blockIndexOf(blocks, itemId)
                        if (index >= 0) {
                            // 子 agent 视图是盖在主列表上的一张纸：不退出的话滚了也看不见
                            selectedAgent = null
                            follow.detach()
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    },
                    onUndo = { ids: List<String> ->
                        val file = changed.firstOrNull { f -> f.changes.any { it.toolUseId in ids } }
                        actions.revertFile(file?.name ?: fallbackFileName, ids) { restored -> undoneIds = undoneIds + restored }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                val hasPlan = !session.plan.isNullOrBlank()
                AnimatedVisibility(visible = hasPlan, enter = InkMotion.rise, exit = InkMotion.sink) {
                    PlanBanner(onOpenPlan)
                }
                val lastError = remember { LastNonNull(session.errorMessage) }
                val shownError = lastError.update(session.errorMessage)
                AnimatedVisibility(visible = session.errorMessage != null, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    Text(
                        shownError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                // 切换条挂在任务条的 leading 槽里，和「N shell」同一行；没有子 agent 时槽是空的
                val switcherThreads = switcherThreads(agentThreads, selectedAgent)
                val team = session.team
                SessionTaskStrip(
                    shellCount = shells.count { it.isRunning },
                    showAgents = switcherThreads.isNotEmpty(),
                    teamLabel = team?.let {
                        if (it.active && it.stalledCount > 0) stringResource(R.string.team_chip_stalled, it.stalledCount)
                        else stringResource(R.string.team_chip, it.members.size)
                    },
                    onOpenTeam = {
                        focusManager.clearFocus()
                        showTeam = true
                    },
                    onOpenShells = {
                        focusManager.clearFocus()
                        showShells = true
                    },
                    goalLabel = session.goal?.let { goal ->
                        stringResource(R.string.goal_chip, goal.condition, goal.evaluations, stringResource(goalStatusRes(goal.status)))
                    },
                    onOpenGoal = {
                        focusManager.clearFocus()
                        showGoal = true
                    },
                    agents = {
                        AgentSwitcher(
                            threads = switcherThreads,
                            selected = selectedAgent,
                            onSelect = { selectedAgent = it },
                            allThreads = agentThreads,
                        )
                    },
                )
                key(activeKey ?: "idle") {
                    val boundId = activeKey
                    ClaudeCodeInputBar(
                        session = session,
                        capabilities = capabilities,
                        viewingThread = viewedAgent,
                        pendingResume = pendingResume,
                        onResume = actions.resume,
                        // 发送不主动滚到底：在底时新消息按贴底跟随自然跟上，在上面翻着就保持原位
                        onRunShell = actions.runShell,
                        // 子 agent 视图里：话说给它（收件箱 / 经主会话转交），停止键只停它
                        onSend = { text, images ->
                            val target = viewedAgent
                            if (target != null) actions.sendToSubagent(target.threadId, text, images) else actions.send(text, images)
                        },
                        onInterrupt = {
                            val target = viewedAgent
                            if (target != null) actions.stopSubagent(target.threadId) else actions.interrupt()
                        },
                        hintOverride = viewedAgent?.let { stringResource(R.string.agent_composer_hint, subagentTypeLabel(it.agentType)) },
                        settingsActions = actions.settings,
                        codexProfileModel = codexProfileModel,
                        onNewSession = actions.newSession,
                        onRequestModels = actions.refreshModels,
                        dailyCostUsd = dailyCostUsd,
                        chineseDescriptions = chineseDescriptions,
                        onRefreshUsage = actions.refreshUsage,
                        onImportFile = actions.importFile,
                        onDeleteWorkspaceFile = actions.deleteWorkspaceFile,
                        onSearchFiles = actions.searchFiles,
                        onLoadImage = actions.loadImage,
                        onOpenLocalCommand = { configCommand = it },
                        extras = vm.extras,
                        onComposerFocusChange = onComposerFocusChange,
                        restoredDraft = composerDraft,
                        onDraftChange = { actions.saveComposerDraft(boundId, it) },
                        onDraftSnapshot = { actions.snapshotComposerOnStop(boundId, it) },
                        withdrawnMessages = vm.withdrawnMessages,
                    )
                }
            }
        }
    }
    }

    configCommand?.let { command ->
        ClaudeCodeConfigSheet(
            command = command,
            vm = configVm,
            onRunInit = actions.runInit,
            onDismiss = { configCommand = null },
        )
    }

    if (showShells) {
        BackgroundShellSheet(
            shells = shells,
            onDismiss = { showShells = false },
            readOutput = { actions.readTaskOutput(it.id) },
            onStop = { actions.stopTask(it.id) },
        )
    }

    session.goal?.takeIf { showGoal }?.let { goal ->
        GoalSheet(goal = goal, onClear = { actions.send("/goal clear", emptyList()) }, onDismiss = { showGoal = false })
    }

    session.team?.takeIf { showTeam }?.let { team ->
        TeamSheet(
            team = team,
            onOpenMember = { threadId ->
                selectedAgent = threadId
                showTeam = false
            },
            onRelease = vm.extras::releaseStalled,
            onDisband = vm.extras::disbandTeam,
            onDismiss = { showTeam = false },
        )
    }

    if (showTerminal) {
        ClaudeCodeTerminalSheet(
            liveSessions = liveSessions,
            onDismiss = { showTerminal = false },
        )
    }
}

/**
 * 一条会话记录按类型分派到各自的渲染器。折叠块展开后走的也是这里，两边一模一样。
 *
 * [onRevert] 收的是 `toolUseId`。这里不直接吃 ViewModel，是因为 Codex 的会话页
 * 复用的就是这个分发器 —— 撤销是 Claude 侧的快照功能，Codex 传 null 即可，
 * 而不必为此另起一套一模一样的 when。
 * [canRevert] 问这个 toolUseId 有没有快照：没有就不给按钮（子 agent 里的编辑、没存上快照的，按了只会报「找不到快照」）。
 */
@Composable
internal fun TranscriptItem(
    item: ChatItem,
    isFirst: Boolean,
    isLast: Boolean,
    labels: TranscriptLabels,
    onRevert: ((String) -> Unit)? = null,
    canRevert: (String) -> Boolean = { true },
) {
    when (item) {
        // ▶ 代发的续跑提示不是人说的话：画成细分隔线（设计 §6.6），与 Min 的续跑分隔同一句「— 继续 —」
        is ChatItem.UserText -> if (item.continuation) {
            NoteEntry("— ${stringResource(R.string.min_agent_continued)} —", isError = false, isFirst = isFirst, isLast = isLast)
        } else {
            UserEntry(item.text, isFirst, isLast, item.queued, item.id, item.handoff, rewindId = item.id.takeIf { item.rewindable })
        }
        is ChatItem.AssistantText -> AssistantEntry(
            text = item.text,
            isFirst = isFirst,
            isLast = isLast,
            durationMs = item.durationMs,
            outputTokens = item.outputTokens,
            finishedAt = item.finishedAt,
        )
        is ChatItem.Thinking -> ThinkingEntry(item.text, item.id, isFirst, isLast)
        is ChatItem.Note -> NoteEntry(item.text, item.isError, isFirst, isLast)
        is ChatItem.Folded -> FoldedEntry(item, isFirst, isLast, labels, onRevert, canRevert)
        is ChatItem.AgentReport -> AgentReportEntry(item, isFirst, isLast)
        is ChatItem.SentFiles -> SentFilesEntry(item, isFirst, isLast)
        is ChatItem.ProcessOutput -> ProcessOutputEntry(item, isFirst, isLast)
        is ChatItem.ToolCall -> ToolEntry(
            item = item,
            isFirst = isFirst,
            isLast = isLast,
            labels = labels,
            // 只有编辑类工具才有快照。工具名在这里过滤；磁盘上到底有没有，由页面一次批量查好（canRevert），
            // 不在每条展开时各发一次 IO 查询，滑动列表时会打出一串无谓的读盘。
            onRevert = if (onRevert != null && item.name in CHECKPOINT_TOOLS && canRevert(item.toolUseId)) {
                { onRevert(item.toolUseId) }
            } else null,
        )
    }
}

/**
 * 这一轮正发生的事，挂在会话流末尾。
 *
 * 曾经是输入框上面那一行「请求中 / 停止」：占位、和右下角停止键重复，
 * 而且把精确阶段（思考 / 执行工具 / 压缩）糊成一个词。停止留在输入坞右下角，
 * 这里只说它在干什么。
 */
@Composable
private fun LiveTurnEntry(
    session: AgentSessionState,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val starting = session.status == SessionStatus.Starting
    val agents = session.runningThreads
    val retry = session.turn?.retry?.text
    val runningTool = session.items
        .asReversed()
        .filterIsInstance<ChatItem.ToolCall>()
        .firstOrNull { it.status == ChatItem.ToolCall.Status.Running }
    // 工具名是机器产物，只有外面那句「正在执行 …」走资源
    val runningToolLabel = runningTool?.name?.let { name ->
        stringResource(R.string.session_live_running_tool, name)
    }
    val label = listOfNotNull(
        when {
            session.stopping -> stringResource(R.string.session_live_stopping)
            session.settings.hotSwapping -> stringResource(R.string.session_live_applying)
            session.settings.relaunching -> stringResource(R.string.session_live_applying_relaunch)
            starting -> stringResource(R.string.session_live_starting)
            else -> statusPhaseLabel(session.turn?.phase)
                ?: runningToolLabel
                ?: stringResource(R.string.session_live_generating)
        },
        session.turn?.detail?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    TranscriptEntry(
        marker = RailMarker.SquareFilled,
        isFirst = isFirst,
        isLast = isLast,
        tone = RailTone.Sea,
        active = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.sea.sea,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TurnMeter(session)
        }
        retry?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 主模型倒了、这一回合在用回退模型（Min 的回退链）：一行轻提示，不是错误——回合照常在跑
        session.turn?.fallback?.let { fallback ->
            Text(
                text = fallbackNoticeText(fallback),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        agents.forEach { thread -> SubagentLine(thread) }
    }
}

/** 「正在用回退模型 X（主模型 Y 过载）」：原因认得的写出来，认不得的只写主模型的名字 */
@Composable
internal fun fallbackNoticeText(notice: dev.min.code.core.engine.FallbackNotice): String {
    val reason = when (notice.reason) {
        dev.min.code.core.engine.FailureKind.Overloaded -> R.string.fallback_reason_overloaded
        dev.min.code.core.engine.FailureKind.RateLimited -> R.string.fallback_reason_rate_limited
        dev.min.code.core.engine.FailureKind.ModelUnavailable -> R.string.fallback_reason_unavailable
        dev.min.code.core.engine.FailureKind.Network -> R.string.fallback_reason_network
        else -> null
    }
    return if (reason == null) stringResource(R.string.fallback_notice_plain, notice.model, notice.primary)
    else stringResource(R.string.fallback_notice, notice.model, notice.primary, stringResource(reason))
}

/**
 * 运行中的计时与吐字量：`24s · ↓488`。
 *
 * 计时在本地跑（每秒一跳）而不是等 CLI 报 —— CLI 只在**结束时**才给 `duration_ms`，
 * 而"它到底卡了多久"恰恰是还没结束时才想知道的。
 *
 * token 数来自 SSE 的 `message_delta.usage`，是真实计费值，不是按字数估的；
 * 代价是它随消息边界跳变（一次工具往返一跳）而不是平滑增长。宁可跳，不可编。
 */
@Composable
private fun TurnMeter(session: AgentSessionState) {
    val startedAt = session.turn?.startedAt
    // 每秒重算一次。key 带上 startedAt：新一轮开始时要立刻从 0 重新计
    var elapsedMs by remember(startedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAt, session.busy) {
        if (startedAt == null || !session.busy) return@LaunchedEffect
        while (true) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(1_000)
        }
    }

    val parts = listOfNotNull(
        elapsedMs.takeIf { startedAt != null && it >= 1_000 }?.let { formatDuration(it) },
        session.usage.turnOutputTokens?.takeIf { it > 0 }?.let { "↓${formatTokens(it)}" },
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetbrainsMono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * `status` 帧的运行阶段。idle 不显示——它等于"没在干活"。
 *
 * 只转文案，不动 CLI 那边的阶段名；认不出来的原样显示。
 */
@Composable
private fun statusPhaseLabel(phase: String?): String? = when (phase) {
    "waiting" -> stringResource(R.string.session_phase_waiting)
    "working" -> stringResource(R.string.session_phase_working)
    "thinking" -> stringResource(R.string.session_phase_thinking)
    "responding" -> stringResource(R.string.session_phase_responding)
    "requesting" -> stringResource(R.string.session_phase_requesting)
    "compacting" -> stringResource(R.string.session_phase_compacting)
    "busy" -> stringResource(R.string.session_phase_busy)
    "idle", null -> null
    else -> phase
}

/**
 * 一个在跑的子 agent。信息密度对齐终端：类型 · 在做什么 · 最近工具 · 用量。
 * 数据来自 `task_started` / `task_progress` / `task_updated`，按 task_id 原地更新。
 */
@Composable
private fun SubagentLine(thread: AgentThread) {
    val progress = thread.progress
    val toolUsesLabel = progress?.toolUses?.takeIf { it > 0 }?.let {
        stringResource(R.string.session_subagent_tool_uses, it)
    }
    val meta = listOfNotNull(
        progress?.lastTool,
        toolUsesLabel,
        // token 数是机器产物。和回执行同一个口径（formatTokens 有一位小数），
        // 1500 tok 曾在这里被整数除法截成 "1k tok"
        progress?.tokens?.takeIf { it > 0 }?.let { formatTokens(it) },
    ).joinToString(" · ")
    val azure = MaterialTheme.sea.sea
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubagentMarker(azure)
        Text(
            text = thread.agentType.let { subagentTypeLabel(it) },
            style = MaterialTheme.typography.labelSmall,
            color = azure,
            maxLines = 1,
        )
        Text(
            text = progress?.summary?.takeIf { it.isNotBlank() } ?: progress?.detail?.takeIf { it.isNotBlank() } ?: progress?.description.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 子任务前面那个标记。
 *
 * **不用图标**：这一页的类型区分全部由左轨道上那套几何标记承担
 * （见 [RailMarker] —— 点 / 环 / 方块 / 叉 / 短横），这里塞一个写实的图标进来，
 * 既和整页版式打架，12dp 下也只是一团糊掉的笔画（之前那个"脑子"就是这样）。
 *
 * 外圈方框沿用工具调用的方块，内点沿用"正在跑"的实心 —— 合起来读作
 * "一个任务里套着的任务"，和轨道上的符号系统是同一套语言。颜色是湛：它活着。
 */
@Composable
private fun SubagentMarker(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2f
        drawRect(
            color = color,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(color, radius = r * 0.36f, center = center)
    }
}

/**
 * 空白与启动中的会话共享干净的阅读区，背景流线停留在上缘。
 */
@Composable
internal fun BlankPage(starting: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (starting) {
                Text(
                    stringResource(R.string.session_blank_starting),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                // 潮句：周末 "I wait, with no hurry"，工作日 "Till the tide turns"（TideLine.kt）
                TideLine(Modifier.fillMaxWidth())
            }
            HorizonLine(Modifier.width(48.dp).padding(vertical = 2.dp), fade = false)
            Text(
                if (starting) "Claude Code" else "/workspace",
                style = MaterialTheme.typography.bodySmall,
                // 路径是机器产物，走等宽（「三种字」）；启动中那行是产品名，仍是人话
                fontFamily = if (starting) null else JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(visible = starting, enter = InkMotion.expand, exit = InkMotion.collapse) {
                InkLineProgress(
                    progress = null,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .width(120.dp),
                )
            }
        }
    }
}

/**
 * 计划只在真有计划时出现，不像以前那样在底栏常驻一个多数时候点不动的 chip。
 * 金底：这是一份等**你**看的东西。
 */
@Composable
private fun PlanBanner(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.sea.seaWash)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Seal()
        Text(
            stringResource(R.string.session_plan_banner),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.sea.seaDeep,
        )
    }
}

// ---------------------------------------------------------------------------
// 权限确认
// ---------------------------------------------------------------------------
