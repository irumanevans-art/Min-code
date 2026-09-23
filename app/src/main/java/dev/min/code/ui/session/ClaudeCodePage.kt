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
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import dev.min.code.core.claudecode.ASK_USER_QUESTION_TOOL
import dev.min.code.core.claudecode.CHECKPOINT_TOOLS
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.PermissionSuggestion
import dev.min.code.ui.components.BrandMark
import dev.min.code.ui.components.FrostFade
import dev.min.code.ui.components.LocalFrost
import dev.min.code.ui.components.frostSource
import dev.min.code.ui.components.frostVeil
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
import dev.min.code.ui.setup.SetupPage
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalUrls
import dev.min.code.util.openExternalUrl
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
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.session.escapedPaths
import dev.min.code.core.session.touchesDeviceStorage

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
fun ClaudeCodePage(vm: ClaudeCodeVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val setup by vm.setup.collectAsStateWithLifecycle()
    // 流式输出时 SessionState 每个 token 换一个新对象。页面上几乎所有地方（顶栏、抽屉、输入坞、
    // 起始面板）都不关心正在长的那段正文，所以它们读的是剥掉逐 token 字段之后的 [session]：
    // 按值判等，流式期间一次都不变，整页不再跟着每个 token 重组。真要看正文的只有会话流里
    // 那两个条目，它们直接读 [liveSession]（见 SessionContent）。
    // 两者从同一个快照派生，不会出现「流式条目已经收起、定稿条目还没进列表」那一帧空白。
    val liveSession = vm.session.collectAsStateWithLifecycle()
    val session by remember(liveSession) {
        derivedStateOf(structuralEqualityPolicy()) { liveSession.value.withoutStream() }
    }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val maintenance by vm.maintenance.collectAsStateWithLifecycle()
    val useNpmMirror by vm.useNpmMirror.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val localServiceList by vm.localServiceList.collectAsStateWithLifecycle()
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()
    val dailyCostUsd by vm.dailyCostUsd.collectAsStateWithLifecycle()
    // 英文界面下这张表整个不生效，见 isChineseUi —— 在这里合掉，
    // 下游每一处消费点（输入坞的命令表、会话设置里那一列）就都不必各自再判一次
    val zhDescriptionSetting by vm.chineseDescriptions.collectAsStateWithLifecycle()
    val chineseDescriptions = zhDescriptionSetting && isChineseUi()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
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
    val previewSlot by vm.previewSlot.collectAsStateWithLifecycle()

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
            vm.refreshSessions()
            delay(2_500)
            if (session.status == SessionStatus.Running) {
                vm.refreshSessions()
            }
        }
    }

    // 宽屏（平板 / 横屏）：可收起、可拖宽、可拉满盖住主区的会话栏。
    // 窄屏仍是模态抽屉。840dp 是 Material 的 expanded 断点。
    val wide = LocalConfiguration.current.screenWidthDp >= 840 && setup.ready
    // 展开态与宽度（dp）在转屏 / 进程重建后仍保留。
    var wideSidebarOpen by rememberSaveable { mutableStateOf(true) }
    var wideSidebarWidthDp by rememberSaveable { mutableFloatStateOf(SIDEBAR_DEFAULT_DP) }
    val live = setup.ready &&
        (session.status == SessionStatus.Running ||
            session.status == SessionStatus.Starting)

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

    val drawer: @Composable (paneWidth: Dp?) -> Unit = { paneWidth ->
        ClaudeCodeSessionDrawer(
            permanent = wide,
            visible = if (wide) wideSidebarOpen else drawerState.isOpen,
            paneWidth = paneWidth,
            sessions = sessions,
            onNewSession = {
                afterSidebarNav()
                vm.newSession()
            },
            onOpenSession = { id ->
                afterSidebarNav()
                vm.openSession(id)
            },
            onDeleteSession = vm::deleteSession,
            onPinSession = vm::pinSession,
            onRenameSession = vm::renameSession,
            onSetCategory = vm::setSessionCategory,
            categories = vm.sessionCategories(),
            onOpenCodex = {
                afterSidebarNav()
                navController.navigate(Screen.Codex)
            },
            onOpenFiles = {
                afterSidebarNav()
                navController.navigate(Screen.Files)
            },
            // 「系统」里的这几项不关抽屉：面板盖在抽屉上，页面压在它上面，
            // 关掉 / 返回时回到的都是抽屉的「系统」页（见 ClaudeCodeSessionDrawer）
            onOpenTerminal = { navController.navigate(Screen.Terminal) },
            onOpenRuntime = {
                vm.refreshNetworkSnapshot()
                showRuntime = true
            },
            onOpenSettings = { navController.navigate(Screen.Settings) },
            onOpenProviders = {
                afterSidebarNav()
                navController.navigate(Screen.Providers)
            },
            onOpenMaintenance = {
                vm.loadEnvironment()
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
            vm.bindPreview(url, expand = true, fromAgent = false)
        }
        CompositionLocalProvider(LocalUriHandler provides previewHandler) {
        if (live) {
            SessionContent(
                session = session,
                liveSession = liveSession,
                vm = vm,
                dailyCostUsd = dailyCostUsd,
                chineseDescriptions = chineseDescriptions,
                // 宽屏也始终给汉堡：展开=收起，收起=展开（对齐 Kimi / DeepSeek）
                showMenu = setup.ready,
                onOpenDrawer = if (wide) toggleSessionList else openSessionList,
                onOpenPlan = { showPlan = true },
                onComposerFocusChange = { composerFocused = it },
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
                                onClick = { vm.refresh() },
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
                        !setup.ready -> SetupPage(onReady = vm::refresh)
                        else -> StartPanel(
                            session = session,
                            cliVersion = setup.cliVersion,
                            onStart = vm::start,
                            lastOptions = vm.lastOptions(),
                            onListCwd = vm::listCwdFolders,
                            onCreateCwd = vm::createCwdFolder,
                        )
                    }
                }
            }
        }
        } // CompositionLocalProvider LocalUriHandler
    }

    session.pendingPermission?.let { pending ->
        // AskUserQuestion 走的是同一个 can_use_tool 帧，但要的是「在选项里挑」而不是
        // 「允许/拒绝」，答案还得经 updatedInput 回传 —— 两种面板不能混用
        if (pending.toolName == ASK_USER_QUESTION_TOOL) {
            ClaudeCodeQuestionSheet(
                pending = pending,
                onAnswer = { answers -> vm.answerQuestions(answers) },
                // 跳过 = 允许工具带着空答案跑完。不能回 deny：那会被模型当成
                // "用户拒绝了这次提问"，它多半会停下来问为什么，而不是继续干活
                onDismiss = { vm.answerQuestions(emptyMap()) },
            )
        } else {
            PermissionSheet(
                pending = pending,
                onAnswer = { allow, suggestion -> vm.answerPermission(allow, suggestion = suggestion) },
            )
        }
    }

    if (showPlan) {
        ClaudeCodePlanSheet(plan = session.plan, onDismiss = { showPlan = false })
    }

    if (showMaintenance) {
        ClaudeCodeMaintenanceSheet(
            state = maintenance,
            // 只数进程还活着的：注册表里会留着刚结束的壳，按 size 数会把更新按钮锁死
            liveSessionCount = liveSessions.count { it.isLive },
            onDismiss = {
                showMaintenance = false
                vm.dismissMaintenanceError()
            },
            onCheckUpdate = vm::checkCliUpdate,
            onUpdateCli = vm::updateCli,
            useNpmMirror = useNpmMirror,
            onSetUseNpmMirror = vm::setUseNpmMirror,
            onUpgradeApt = vm::upgradeApt,
            onReinstallNode = vm::reinstallNode,
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
            logOf = vm::logOf,
            onDismiss = { showRuntime = false },
            onRefresh = vm::refreshNetworkSnapshot,
            onStopService = vm::stopLocalService,
            onToggleLog = vm::toggleServiceLog,
            onOpenPreview = { url ->
                showRuntime = false
                // 要看的是会话页上的预览位，抽屉得让开
                afterSidebarNav()
                // 进程表「打开」= 按预览位键，不是第二套 WebView
                vm.bindPreview(url, expand = true, fromAgent = false)
            },
        )
    }

    // 预览位展开：关掉只藏，url 仍在 slot 里，铬件键可再开
    val slotUrl = previewSlot.url
    if (previewSlot.expanded && slotUrl != null) {
        LocalPreviewPane(
            url = slotUrl,
            onCollapse = vm::collapsePreview,
        )
    }
}

/**
 * Markdown / 链接：loopback 走应用内预览，其它走系统浏览器。
 * 由 [SessionContent] 或外层 CompositionLocal 注入。
 */
@Composable
internal fun rememberLocalPreviewUriHandler(
    onPreview: (String) -> Unit,
): UriHandler {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPreview)
    return remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LocalUrls.isLoopbackHttp(uri)) {
                    latest.value(LocalUrls.normalizeLoopback(uri))
                } else {
                    context.openExternalUrl(uri)
                }
            }
        }
    }
}

/**
 * 自动跟随时把最后一项的底部顶到视口下沿。animateScrollToItem 的语义是把目标项对齐到
 * 视口**顶部**，直接用会导致"滚到最后一条却停在屏幕上方"，看着像弹回去了。
 */
private const val TAIL_SCROLL_OFFSET = 100_000

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

/** 去掉逐 token 在变的字段（流式正文、思考、本条消息的输出计数），见 ClaudeCodePage 顶部 */
private fun ClaudeCodeManager.SessionState.withoutStream(): ClaudeCodeManager.SessionState =
    if (streamingText.isEmpty() && streamingThinking.isEmpty() && outputTokensCurrent == 0) this
    else copy(streamingText = "", streamingThinking = "", outputTokensCurrent = 0)

@Composable
private fun ClaudeCodeManager.SessionState.subtitle(): String = when {
    stopping -> stringResource(R.string.session_settings_busy_stopping)
    applyingSettings || applyingEffort -> stringResource(R.string.session_settings_busy)
    status == SessionStatus.Running -> {
        // 模型名是机器产物，原样；工具计数的量词是人话，走资源
        val tools = if (toolCount > 0) {
            stringResource(R.string.session_subtitle_tools, toolCount)
        } else {
            null
        }
        val running = stringResource(R.string.session_meta_live)
        listOfNotNull(model, tools).joinToString(" · ").ifBlank { running }
    }

    status == SessionStatus.Starting -> stringResource(R.string.session_status_starting_cli)
    status == SessionStatus.Closed -> stringResource(R.string.session_status_closed)
    status == SessionStatus.Failed -> stringResource(R.string.session_status_failed)
    // 产品名，不翻
    else -> "Claude Code"
}

/**
 * 整条会话导出成 markdown。
 *
 * 会话流里每条都包了 `SelectionContainer`，但**跨条选择是做不到的** —— LazyColumn 会把
 * 滚出屏幕的条目回收掉，选区跟着断，所以每条只能各管各的。想把一整轮工作贴到别处时，
 * 逐条选逐条复制不现实，这里一次性给全。
 *
 * 工具调用只导出摘要不导出完整结果：一次 Read 的结果动辄上千行，全塞进去会把真正想要的
 * 对话内容淹掉。要完整结果的话展开那条工具卡单独选中复制。
 */
internal fun List<ChatItem>.toTranscriptText(labels: TranscriptLabels): String = buildString {
    this@toTranscriptText.forEach { item ->
        when (item) {
            is ChatItem.UserText -> append("## ${labels.user}\n\n${item.text}\n\n")
            // 说话人是产品名，不翻
            is ChatItem.AssistantText -> append("## Claude\n\n${item.text}\n\n")
            is ChatItem.Thinking -> append("### ${labels.thinking}\n\n${item.text}\n\n")
            is ChatItem.Note -> append("> ${item.text}\n\n")
            // 进程输出导成代码块：贴到别处去查的时候，等宽和原样换行都要保住
            is ChatItem.ProcessOutput ->
                append("```\n${item.lines.joinToString("\n")}\n```\n\n")
            is ChatItem.ToolCall -> {
                val status = when (item.status) {
                    ChatItem.ToolCall.Status.Running -> labels.toolRunning
                    ChatItem.ToolCall.Status.Done -> labels.toolDone
                    ChatItem.ToolCall.Status.Error -> labels.toolError
                }
                append("- `${item.name}` ${toolSummary(item.name, item.input, labels)} — $status\n\n")
            }
        }
    }
}.trim()

/**
 * 会话流表层用到的全部字头。[toTranscriptText]、[ToolEntry]、[ToolCallDetail]、
 * [ToolInputPreview] 和会话搜索都不是 @Composable（或不该逐条取资源），取不到
 * `stringResource`，由调用侧在组合时取好一次性传下去。
 *
 * 带 `%1$d` 的是格式模板，消费方用 `String.format` 现场填数。
 */
internal data class TranscriptLabels(
    val user: String,
    val thinking: String,
    val toolRunning: String,
    val toolDone: String,
    val toolError: String,
    /** 折叠行右侧：这次调用卡在等人批准 */
    val toolAwaiting: String,
    /** Read 折叠行：「101–200 行」 */
    val readRange: String,
    /** Read 折叠行：「从 101 行」 */
    val readFrom: String,
    /** Read 折叠行：「前 50 行」 */
    val readFirst: String,
    /** Edit 折叠行：replace_all 时的「全部替换」 */
    val replaceAll: String,
    /** 行数（Write 的行数、结果行数共用）：「N 行」 */
    val nLines: String,
    /** MultiEdit 的编辑处数：「N 处」 */
    val nEdits: String,
    /** TodoWrite 的进度：「1/3 已完成」 */
    val todoDone: String,
    /** AskUserQuestion 摘不出去内容时的兜底 */
    val askUser: String,
    /** 计划模式工具没有 plan 正文时的兜底 */
    val planMode: String,
    /** 子任务角标：「N 步」（复用工作块的步数文案） */
    val nSteps: String,
    /** diff 公共前缀省略的头，`@@` 前缀是 DiffView 着色的依据，两种语言都得保留 */
    val diffElidedAbove: String,
    /** diff 公共后缀省略的头，同上 */
    val diffElidedBelow: String,
    /** diff 源字段超长截断的尾巴 */
    val diffTruncated: String,
    // 设备操控那六把工具的折叠行摘要。它们经 MCP 挂进来，入参是 index / direction
    // 这种裸值，走不到按文件名 / 按命令那套摘要上去
    val deviceTap: String,
    val deviceInput: String,
    val deviceSwipe: String,
    val deviceOpenApp: String,
    val deviceBack: String,
    val deviceUiTree: String,
)

@Composable
internal fun rememberTranscriptLabels(): TranscriptLabels = TranscriptLabels(
    user = stringResource(R.string.session_export_user),
    thinking = stringResource(R.string.session_export_thinking),
    toolRunning = stringResource(R.string.session_tool_status_running),
    toolDone = stringResource(R.string.session_tool_status_done),
    toolError = stringResource(R.string.session_tool_status_error),
    toolAwaiting = stringResource(R.string.session_tool_status_awaiting),
    readRange = stringResource(R.string.tool_summary_range),
    readFrom = stringResource(R.string.tool_summary_from_line),
    readFirst = stringResource(R.string.tool_summary_first_lines),
    replaceAll = stringResource(R.string.tool_summary_replace_all),
    nLines = stringResource(R.string.tool_summary_n_lines),
    nEdits = stringResource(R.string.tool_summary_n_edits),
    todoDone = stringResource(R.string.tool_summary_todo_done),
    askUser = stringResource(R.string.tool_summary_ask_user),
    planMode = stringResource(R.string.tool_summary_plan),
    nSteps = stringResource(R.string.transcript_work_steps),
    diffElidedAbove = stringResource(R.string.tool_diff_elided_above),
    diffElidedBelow = stringResource(R.string.tool_diff_elided_below),
    diffTruncated = stringResource(R.string.tool_diff_truncated),
    deviceTap = stringResource(R.string.device_tool_tap),
    deviceInput = stringResource(R.string.device_tool_input),
    deviceSwipe = stringResource(R.string.device_tool_swipe),
    deviceOpenApp = stringResource(R.string.device_tool_open_app),
    deviceBack = stringResource(R.string.device_tool_back),
    deviceUiTree = stringResource(R.string.device_tool_ui_tree),
)

/**
 * 记住最后一个非空值。给"退场动画"用：AnimatedVisibility 在收起的那几百毫秒里还要能
 * 画出刚才的内容，而状态本身这时已经空了。不是 State——它只在同一次重组里被读，
 * 不需要自己触发重组。
 */
internal class LastNonNull<T : Any>(initial: T?) {
    var value: T? = initial
    fun update(next: T?): T? {
        if (next != null) value = next
        return value
    }
}

// ---------------------------------------------------------------------------
// 会话内容
// ---------------------------------------------------------------------------

@Composable
private fun SessionContent(
    /** 剥掉了逐 token 字段的会话状态，见 ClaudeCodePage 顶部的说明 */
    session: ClaudeCodeManager.SessionState,
    /** 完整状态。只在最小的作用域里读（流式条目、当前轮进度），别在函数体顶层读 */
    liveSession: State<ClaudeCodeManager.SessionState>,
    vm: ClaudeCodeVM,
    dailyCostUsd: Double,
    chineseDescriptions: Boolean,
    onOpenPlan: () -> Unit,
    showMenu: Boolean = true,
    onOpenDrawer: () -> Unit = {},
    /** 输入框的焦点变化，一路报到页面顶层的"键盘收起就清焦点"那条效应里 */
    onComposerFocusChange: (Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // 只在「有没有流式内容」翻转时才让本函数重组，正文每长一截不算
    val streaming by remember(liveSession) {
        derivedStateOf { liveSession.value.let { it.streamingText.isNotBlank() || it.streamingThinking.isNotBlank() } }
    }
    // 工具卡的字头在组合期取好传下去：TranscriptItem 的调用方（搜索、导出）不是 Composable
    val transcriptLabels = rememberTranscriptLabels()
    val composerDraft by vm.composerDraft.collectAsStateWithLifecycle()
    val activeKey by vm.activeSessionKey.collectAsStateWithLifecycle()
    val showLiveTurn = !streaming && (
        session.busy ||
            session.stopping ||
            session.applyingSettings ||
            session.applyingEffort ||
            session.status == SessionStatus.Starting ||
            session.retryNotice != null ||
            session.runningTasks.isNotEmpty()
    )

    // 连续的思考/工具活动折成块，跑完自动收起（见 [TranscriptBlock]）。
    // 分组是纯计算，但会话长起来之后每帧重算不划算，按条目数和最后一条的身份缓存
    val blocks = remember(session.items) { groupTranscript(session.items) }
    val lastIndex = blocks.lastIndex

    // 用户手动开合过的块（key = 块内第一条的 id）。null = 没动过，跟默认走。
    // 提在这里而不是各块内部：LazyColumn 会把滚出屏幕的条目连同它的 remember 一起回收，
    // 状态放在条目里的话，往回滑一趟展开的块就自己合上了
    val manualExpanded = remember { mutableStateMapOf<String, Boolean>() }

    // 交互式斜杠命令（/mcp、/agents、/memory…）打开的配置面板。它们改的是 Rootfs 里的
    // 配置文件，和「会话设置」那张 sheet 是两回事，所以状态提在这里而不是输入栏内部。
    var configCommand by rememberSaveable { mutableStateOf<LocalSlash?>(null) }

    // 终端浮层。和会话同一个 rootfs 里的真 bash，见 [ClaudeCodeTerminalSheet]
    var showTerminal by rememberSaveable { mutableStateOf(false) }
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // 只有当用户本来就贴在底部时才自动跟随。之前无条件滚动，一往回翻就被拽走。
    //
    // **只在用户自己拖过之后才重新判定。** isScrollInProgress 对程序滚动（下面那段键盘补偿的
    // scrollBy、自动跟随的 animateScrollToItem）同样会跳一下，而键盘动画期间视口每帧在缩，
    // 补偿刚停那一瞬 canScrollForward 多半是 true —— 于是跟随被自己关掉，发出去的新消息
    // 就卡在折叠线下面看不见。
    var followTail by remember { mutableStateOf(true) }
    var userDragged by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userDragged = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            // 手停下来（含惯性滑完）那一刻看还能不能往下滚：不能 = 在底部 = 继续跟随
            if (!scrolling && userDragged) {
                followTail = !listState.canScrollForward
                userDragged = false
            }
        }
    }
    LaunchedEffect(blocks.size, showLiveTurn, followTail) {
        if (!followTail) return@LaunchedEffect
        // 正文每长 64 个字符跟一次底。放在 snapshotFlow 里读而不是当 key：
        // 当 key 就得在函数体里读正文长度，整个 SessionContent 又会跟着每个 token 重组
        snapshotFlow { liveSession.value.streamingText.length / 64 }.collect {
            val extra = (if (streaming) 1 else 0) + (if (showLiveTurn) 1 else 0)
            val total = blocks.size + extra
            if (total > 0) listState.animateScrollToItem(total - 1, scrollOffset = TAIL_SCROLL_OFFSET)
        }
    }

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
                if (followTail && shrunk > 0) listState.scrollBy(shrunk.toFloat())
            }
    }

    val density = LocalDensity.current
    // 量按钮行 / 输入坞（含系统栏）。列表留白只垫铬件，内容才能滚进纸色里。
    // 纸色比铬件多出 [FrostFade]，切线处收到 0，才没有硬边。
    var topChromePx by remember { mutableIntStateOf(with(density) { 72.dp.roundToPx() }) }
    var bottomChromePx by remember { mutableIntStateOf(with(density) { 96.dp.roundToPx() }) }
    val topReserve = with(density) { topChromePx.toDp() }
    val bottomReserve = with(density) { bottomChromePx.toDp() }
    val topBand = topReserve + FrostFade
    val bottomBand = bottomReserve + FrostFade
    val topHold = (topReserve / topBand).coerceIn(0.2f, 0.92f)
    val bottomHold = (bottomReserve / bottomBand).coerceIn(0.2f, 0.92f)
    val frost = rememberFrostState()
    // 挂着权限请求时，那张卡要显示成「等你批准」而不是转圈（见 [LocalAwaitingToolUseId]）
    val awaitingToolUseId = awaitingToolUseId(
        items = session.items,
        pendingToolName = session.pendingPermission?.toolName,
        pendingToolUseId = session.pendingPermission?.toolUseId,
    )

    CompositionLocalProvider(
        LocalFrost provides frost,
        LocalAwaitingToolUseId provides awaitingToolUseId,
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
                        when (block) {
                            is TranscriptBlock.Single ->
                                TranscriptItem(block.item, isFirst, isLast, transcriptLabels, onRevert = { vm.revertToolCall(it) })

                            is TranscriptBlock.Work -> {
                                // 默认只有"最后一块 + 还在跑"才展开：那时候"它现在在干什么"
                                // 正是唯一重要的事。跑完就收起来，手动开合优先于默认
                                val live = session.busy && index == lastIndex
                                val expanded = manualExpanded[block.key] ?: live
                                if (!expanded) {
                                    CollapsedWorkEntry(
                                        items = block.items,
                                        isFirst = isFirst,
                                        isLast = isLast,
                                        onExpand = { manualExpanded[block.key] = true },
                                    )
                                } else {
                                    Column {
                                        block.items.forEachIndexed { i, item ->
                                            TranscriptItem(
                                                item = item,
                                                isFirst = isFirst && i == 0,
                                                // 展开时块尾还挂着一条"收起"，轨道不能在这里断
                                                isLast = false,
                                                labels = transcriptLabels,
                                                onRevert = { vm.revertToolCall(it) },
                                            )
                                        }
                                        CollapseWorkFooter(
                                            isLast = isLast,
                                            onCollapse = { manualExpanded[block.key] = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (streaming) {
                    item(key = "streaming") {
                        // 整页只有这里和下面的当前轮进度逐 token 读完整状态
                        val live = liveSession.value
                        Column {
                            if (live.streamingThinking.isNotBlank()) {
                                ThinkingEntry(
                                    text = live.streamingThinking,
                                    id = "streaming-thinking",
                                    isFirst = blocks.isEmpty(),
                                    isLast = live.streamingText.isBlank() && !showLiveTurn,
                                    streaming = true,
                                )
                            }
                            if (live.streamingText.isNotBlank()) {
                                AssistantEntry(
                                    text = live.streamingText,
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
                        LiveTurnEntry(
                            session = liveSession.value,
                            isFirst = blocks.isEmpty() && !streaming,
                            isLast = true,
                        )
                    }
                }
        }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topBand)
                .frostVeil(fromTop = true, hold = topHold),
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
                val preview by vm.previewSlot.collectAsStateWithLifecycle()
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
                                vm.togglePreview()
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
                if (session.status == SessionStatus.Running || session.stopping) {
                    Spacer(Modifier.width(6.dp))
                    PaperDisc {
                        InkIconButton(
                            icon = HugeIcons.Stop,
                            contentDescription = stringResource(R.string.session_stop),
                            onClick = { vm.stop() },
                            tint = MaterialTheme.sea.vermilion,
                            busy = session.stopping,
                            enabled = !session.stopping,
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomBand)
                .frostVeil(fromTop = false, hold = bottomHold),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomChromePx = it.height },
        ) {
            Column(Modifier.fillMaxWidth()) {
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
                key(activeKey ?: "idle") {
                    val boundId = activeKey
                    ClaudeCodeInputBar(
                        session = session,
                        onRunShell = { command ->
                            followTail = true
                            vm.runShell(command)
                        },
                        onSend = { text, images ->
                            followTail = true
                            vm.send(text, images)
                        },
                        onInterrupt = vm::interrupt,
                        onSetModel = vm::setModel,
                        onSetPermissionMode = vm::setPermissionMode,
                        onApplyEffort = { effort, ultracode -> vm.applyEffort(effort, ultracode) },
                        onSetPromptCacheTtl = vm::setPromptCacheTtl,
                        onRequestModels = vm::refreshModels,
                        dailyCostUsd = dailyCostUsd,
                        chineseDescriptions = chineseDescriptions,
                        onSetChineseDescriptions = vm::setChineseDescriptions,
                        onRefreshUsage = vm::refreshUsage,
                        onImportFile = vm::importFile,
                        onSetCwd = vm::setCwd,
                        onListCwd = vm::listCwdFolders,
                        onCreateCwd = vm::createCwdFolder,
                        onDeleteWorkspaceFile = vm::deleteWorkspaceFile,
                        onSearchFiles = vm::searchFiles,
                        onLoadImage = vm::loadImage,
                        onOpenLocalCommand = { configCommand = it },
                        onComposerFocusChange = onComposerFocusChange,
                        restoredDraft = composerDraft,
                        onDraftChange = { vm.saveComposerDraft(boundId, it) },
                        onDraftSnapshot = { vm.snapshotComposerOnStop(boundId, it) },
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
            vm = vm,
            onDismiss = { configCommand = null },
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
 */
@Composable
internal fun TranscriptItem(
    item: ChatItem,
    isFirst: Boolean,
    isLast: Boolean,
    labels: TranscriptLabels,
    onRevert: ((String) -> Unit)? = null,
) {
    when (item) {
        is ChatItem.UserText -> UserEntry(item.text, isFirst, isLast, item.queued)
        is ChatItem.AssistantText -> AssistantEntry(
            text = item.text,
            isFirst = isFirst,
            isLast = isLast,
            durationMs = item.durationMs,
            outputTokens = item.outputTokens,
        )
        is ChatItem.Thinking -> ThinkingEntry(item.text, item.id, isFirst, isLast)
        is ChatItem.Note -> NoteEntry(item.text, item.isError, isFirst, isLast)
        is ChatItem.ProcessOutput -> ProcessOutputEntry(item, isFirst, isLast)
        is ChatItem.ToolCall -> ToolEntry(
            item = item,
            isFirst = isFirst,
            isLast = isLast,
            labels = labels,
            // 只有编辑类工具才有快照。这里按工具名过滤而不是去问磁盘：
            // 展开一条就发一次 IO 查询，滑动列表时会打出一串无谓的读盘。
            onRevert = if (onRevert != null && item.name in CHECKPOINT_TOOLS) {
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
    session: ClaudeCodeManager.SessionState,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val starting = session.status == SessionStatus.Starting
    val tasks = session.runningTasks
    val retry = session.retryNotice
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
            session.applyingSettings -> stringResource(R.string.session_live_applying)
            session.applyingEffort -> stringResource(R.string.session_live_applying_relaunch)
            starting -> stringResource(R.string.session_live_starting)
            else -> statusPhaseLabel(session.statusPhase)
                ?: runningToolLabel
                ?: stringResource(R.string.session_live_generating)
        },
        session.statusDetail?.takeIf { it.isNotBlank() },
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
        tasks.forEach { task -> SubagentLine(task) }
    }
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
private fun TurnMeter(session: ClaudeCodeManager.SessionState) {
    val startedAt = session.turnStartedAt
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
        session.turnOutputTokens.takeIf { it > 0 }?.let { "↓${formatTokens(it)}" },
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
 * 时长。秒级以内给整秒，超过一分钟给 `1m32s` —— 手机上这一栏只有几十 dp 宽，
 * 写成 `92 秒` 反而要在脑子里再换算一次。
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60) return if (seconds == 0L) "${minutes}m" else "${minutes}m${seconds}s"
    val hours = minutes / 60
    return "${hours}h${minutes % 60}m"
}

/** token 数。上千收成 `1.2k`，四位数字在这一栏里只会把行挤爆 */
internal fun formatTokens(count: Int): String =
    if (count < 1000) count.toString()
    else String.format(java.util.Locale.US, "%.1fk", count / 1000.0)

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
private fun SubagentLine(task: ClaudeCodeManager.TaskInfo) {
    val toolUsesLabel = task.toolUses?.takeIf { it > 0 }?.let {
        stringResource(R.string.session_subagent_tool_uses, it)
    }
    val meta = listOfNotNull(
        task.lastToolName,
        toolUsesLabel,
        // token 数是机器产物。和回执行同一个口径（formatTokens 有一位小数），
        // 1500 tok 曾在这里被整数除法截成 "1k tok"
        task.totalTokens?.takeIf { it > 0 }?.let { formatTokens(it) },
    ).joinToString(" · ")
    val azure = MaterialTheme.sea.sea
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubagentMarker(azure)
        Text(
            text = task.subagentType ?: stringResource(R.string.session_subagent_default),
            style = MaterialTheme.typography.labelSmall,
            color = azure,
            maxLines = 1,
        )
        Text(
            text = task.summary?.takeIf { it.isNotBlank() } ?: task.description,
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

/**
 * 权限确认。用底部 sheet 而不是 AlertDialog：审批是**高频一键动作**，
 * 模态对话框把整屏盖掉、按钮还在屏幕中间，单手够不着；底部 sheet 拇指可达，
 * 上面正在读的内容也还看得见。
 *
 * 「始终允许 …」来自 `can_use_tool` 请求附带的 `permission_suggestions`：
 * 把用户选中的那条原样回传，CLI 就会写规则，之后同类调用不再询问。
 * 没有它的话，默认 Manual 模式下每一条 Bash、每一次 Edit 都要重新点一遍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionSheet(
    pending: ClaudeCodeEvent.PermissionRequest,
    onAnswer: (allow: Boolean, suggestion: PermissionSuggestion?) -> Unit,
) {
    // **不允许滑动关掉**。这个 sheet 是在会话流上方弹出来的，而用户很可能正在往回滑看历史 ——
    // 弹出的那一瞬间手指还在移动，一下就把它滑走了，而滑走等于 onDismissRequest，
    // 之前那会直接回一个 deny。ExitPlanMode 被误拒的后果尤其重：模型会以为你否了这份计划，
    // 转头去改方案。出口只留两个明确的按钮。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    InkSheet(
        onDismissRequest = { /* 只能按按钮，见上 */ },
        sheetState = sheetState,
        dismissible = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Seal()
                Text(
                    pending.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = JetbrainsMono,
                )
            }
            pending.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            pending.blockedPath?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.session_permission_blocked_path, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.vermilion,
                )
            }
            pending.decisionReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.session_permission_reason, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 要动的东西跑到 /workspace 之外了。挂了整机存储之后这是唯一能把
            // 「改容器里的 /etc」和「改你相册里的照片」分开说的地方
            val escaped = remember(pending.input) { escapedPaths(pending.input) }
            val onDevice = remember(pending.input) { touchesDeviceStorage(pending.input) }
            if (escaped.isNotEmpty()) {
                Text(
                    text = if (onDevice) {
                        stringResource(R.string.session_permission_device_path, escaped.first())
                    } else {
                        stringResource(R.string.session_permission_outside_workspace, escaped.first())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = if (onDevice) {
                        MaterialTheme.sea.vermilion
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 按工具类型渲染入参：编辑类给 diff，Bash 给高亮命令，其余给美化 JSON。
            // 原来这里是 `input.toString()` 截断成一行流水账 —— 用户点「允许」时
            // 其实看不到自己批准了什么，而那正是权限确认唯一的意义。
            ToolInputPreview(
                name = pending.toolName,
                input = pending.input,
                labels = rememberTranscriptLabels(),
            )

            InkDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 拒绝是判定（朱砂），允许是主动作（墨 / 金）
                InkButton(
                    onClick = { onAnswer(false, null) },
                    modifier = Modifier.weight(1f),
                    tone = InkButtonTone.Vermilion,
                ) { Text(stringResource(R.string.session_permission_deny)) }
                InkButton(
                    onClick = { onAnswer(true, null) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.session_permission_allow_once)) }
            }
            // 「始终允许」放在主按钮下方：它改的是持久规则，不该和一次性批准同等醒目。
            //
            // 碰到设备上的真实文件时**一条都不给**：那条规则会把往后所有同类写入都放过去，
            // 而这一档改坏了没有 undo（rootfs 里改砸了重装就回来，相册里的照片不会）。
            // 每一次都值得单独看一眼——这正是把整机交出去之后仅剩的那道闸。
            if (!onDevice) {
                pending.suggestions.forEach { suggestion ->
                    InkButton(
                        onClick = { onAnswer(true, suggestion) },
                        modifier = Modifier.fillMaxWidth(),
                        tone = InkButtonTone.Paper,
                        icon = HugeIcons.Tick01,
                    ) {
                        Text(suggestion.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StartPanel(
    session: ClaudeCodeManager.SessionState,
    cliVersion: String?,
    onStart: (ClaudeCodeManager.SessionOptions) -> Unit,
    lastOptions: ClaudeCodeManager.SessionOptions = session.options,
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
) {
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    var cwd by rememberSaveable { mutableStateOf(lastOptions.cwd.ifBlank { ClaudeCodeManager.DEFAULT_CWD }) }
    var pickingCwd by remember { mutableStateOf(false) }
    SeaSurface {
    HeroCanvas {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = BiasAlignment(0f, 1f),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle(stringResource(R.string.workspace_detail_workspace_info))
                // 产品名，不翻
                Text("Claude Code", style = MaterialTheme.typography.headlineSmall)
                CwdPathRow(
                    cwd = cwd,
                    trailing = cliVersion,
                    onClick = { pickingCwd = true },
                )
                Spacer(Modifier.height(10.dp))
                InkDivider()
                // 上次崩溃过就在这里说一声：没有联网上报，用户不主动去「关于」里看就永远不知道。
                // 只提示、不弹窗——启动面板本来就是"停下来看一眼"的地方
                val context = LocalContext.current
                // 读崩溃记录要碰磁盘：挪到 IO，别卡在启动面板的组合里
                var crashed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    crashed = withContext(Dispatchers.IO) {
                        dev.min.code.core.crash.CrashRecorder.read(context) != null
                    }
                }
                AnimatedVisibility(visible = crashed, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    Notice(
                        text = stringResource(R.string.session_start_crash_notice),
                        tone = NoticeTone.Warn,
                        action = {
                            InkTextButton(onClick = { dev.min.code.core.crash.CrashRecorder.clear(context); crashed = false }) {
                                Text(stringResource(R.string.common_clear), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
                session.errorMessage?.let {
                    Notice(text = it, tone = NoticeTone.Error, maxLines = 6)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { skipPermissions = !skipPermissions }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    // 整行可点，勾选框自己不接点击
                    InkCheckbox(checked = skipPermissions, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.session_start_skip_permissions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.session_start_skip_permissions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                InkButton(
                    onClick = {
                        onStart(
                            lastOptions.copy(
                                skipPermissions = skipPermissions,
                                resumeSessionId = null,
                                cwd = cwd,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = HugeIcons.Play,
                ) {
                    Text(stringResource(R.string.session_start_action))
                }
            }
        }
    }
    }
    if (pickingCwd) {
        CwdPickerSheet(
            current = cwd,
            onPick = { cwd = it; pickingCwd = false },
            onDismiss = { pickingCwd = false },
            onList = onListCwd,
            onCreate = onCreateCwd,
        )
    }
}
