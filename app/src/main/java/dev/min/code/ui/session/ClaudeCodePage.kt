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
import dev.min.code.ui.preview.LocalPreviewSheet
import dev.min.code.ui.setup.SetupPage
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalPreviewBus
import dev.min.code.util.LocalUrls
import dev.min.code.util.openExternalUrl
import dev.min.code.util.writeClipboardText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
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
    val session by vm.session.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val maintenance by vm.maintenance.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val localServiceList by vm.localServiceList.collectAsStateWithLifecycle()
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()
    val dailyCostUsd by vm.dailyCostUsd.collectAsStateWithLifecycle()
    val chineseDescriptions by vm.chineseDescriptions.collectAsStateWithLifecycle()
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
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }
    var showPlan by remember { mutableStateOf(false) }
    var showMaintenance by remember { mutableStateOf(false) }
    var showRuntime by remember { mutableStateOf(false) }
    var previewUrl by remember { mutableStateOf<String?>(null) }

    // 会话链 / 终端 / 总线发现的 loopback → 应用内预览
    LaunchedEffect(Unit) {
        merge(vm.localPreviewUrls, LocalPreviewBus.urls).collect { url ->
            previewUrl = LocalUrls.normalizeLoopback(url)
        }
    }

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
        if (session.status == ClaudeCodeManager.SessionStatus.Running && !session.busy) {
            vm.refreshUsage()
            vm.refreshPlan()
            vm.refreshSessions()
            delay(2_500)
            if (session.status == ClaudeCodeManager.SessionStatus.Running) {
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
        (session.status == ClaudeCodeManager.SessionStatus.Running ||
            session.status == ClaudeCodeManager.SessionStatus.Starting)

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

    val drawer: @Composable (paneWidth: Dp?) -> Unit = { paneWidth ->
        ClaudeCodeSessionDrawer(
            permanent = wide,
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
            onOpenFiles = {
                afterSidebarNav()
                navController.navigate(Screen.Files())
            },
            onOpenTerminal = {
                afterSidebarNav()
                navController.navigate(Screen.Terminal)
            },
            onOpenRuntime = {
                afterSidebarNav()
                vm.refreshNetworkSnapshot()
                showRuntime = true
            },
            onOpenSettings = {
                afterSidebarNav()
                navController.navigate(Screen.Settings)
            },
            onOpenMaintenance = {
                afterSidebarNav()
                vm.loadEnvironment()
                showMaintenance = true
            },
            // 没内容时不给这一行 —— 一个复制出来是空字符串的按钮只会让人以为坏了
            onCopyTranscript = if (session.items.isNotEmpty()) {
                {
                    afterSidebarNav()
                    context.writeClipboardText(session.items.toTranscriptText())
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
        val previewHandler = rememberLocalPreviewUriHandler { previewUrl = it }
        CompositionLocalProvider(LocalUriHandler provides previewHandler) {
        if (live) {
            SessionContent(
                session = session,
                vm = vm,
                dailyCostUsd = dailyCostUsd,
                chineseDescriptions = chineseDescriptions,
                // 宽屏也始终给汉堡：展开=收起，收起=展开（对齐 Kimi / DeepSeek）
                showMenu = setup.ready,
                onOpenDrawer = if (wide) toggleSessionList else openSessionList,
                onOpenPlan = { showPlan = true },
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
                                contentDescription = "刷新状态",
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
                            status = "正在铺纸研墨",
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
            onUpgradeApt = vm::upgradeApt,
            onReinstallNode = vm::reinstallNode,
            onOpenWorkspace = {
                showMaintenance = false
                navController.navigate(Screen.Files())
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
                previewUrl = url
            },
        )
    }

    previewUrl?.let { url ->
        LocalPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
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

private fun ClaudeCodeManager.SessionState.subtitle(): String = when {
    stopping -> "正在结束会话…"
    applyingSettings || applyingEffort -> "正在应用会话设置…"
    status == ClaudeCodeManager.SessionStatus.Running ->
        listOfNotNull(model, toolCount.takeIf { it > 0 }?.let { "$it 个工具" })
            .joinToString(" · ")
            .ifBlank { "运行中" }

    status == ClaudeCodeManager.SessionStatus.Starting -> "正在启动 Claude Code…"
    status == ClaudeCodeManager.SessionStatus.Closed -> "会话已结束"
    status == ClaudeCodeManager.SessionStatus.Failed -> "会话出错"
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
internal fun List<ClaudeCodeManager.ChatItem>.toTranscriptText(): String = buildString {
    this@toTranscriptText.forEach { item ->
        when (item) {
            is ClaudeCodeManager.ChatItem.UserText -> append("## 你\n\n${item.text}\n\n")
            is ClaudeCodeManager.ChatItem.AssistantText -> append("## Claude\n\n${item.text}\n\n")
            is ClaudeCodeManager.ChatItem.Thinking -> append("### 思考\n\n${item.text}\n\n")
            is ClaudeCodeManager.ChatItem.Note -> append("> ${item.text}\n\n")
            // 进程输出导成代码块：贴到别处去查的时候，等宽和原样换行都要保住
            is ClaudeCodeManager.ChatItem.ProcessOutput ->
                append("```\n${item.lines.joinToString("\n")}\n```\n\n")
            is ClaudeCodeManager.ChatItem.ToolCall -> {
                val status = when (item.status) {
                    ClaudeCodeManager.ChatItem.ToolCall.Status.Running -> "运行中"
                    ClaudeCodeManager.ChatItem.ToolCall.Status.Done -> "完成"
                    ClaudeCodeManager.ChatItem.ToolCall.Status.Error -> "出错"
                }
                append("- `${item.name}` ${toolSummary(item.name, item.input)} — $status\n\n")
            }
        }
    }
}.trim()

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
    session: ClaudeCodeManager.SessionState,
    vm: ClaudeCodeVM,
    dailyCostUsd: Double,
    chineseDescriptions: Boolean,
    onOpenPlan: () -> Unit,
    showMenu: Boolean = true,
    onOpenDrawer: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val streaming = session.streamingText.isNotBlank() || session.streamingThinking.isNotBlank()
    val composerDraft by vm.composerDraft.collectAsStateWithLifecycle()
    val activeKey by vm.activeSessionKey.collectAsStateWithLifecycle()
    val showLiveTurn = !streaming && (
        session.busy ||
            session.stopping ||
            session.applyingSettings ||
            session.applyingEffort ||
            session.status == ClaudeCodeManager.SessionStatus.Starting ||
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
    var configCommand by remember { mutableStateOf<LocalSlash?>(null) }

    // 终端浮层。和会话同一个 rootfs 里的真 bash，见 [ClaudeCodeTerminalSheet]
    var showTerminal by remember { mutableStateOf(false) }
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
    LaunchedEffect(blocks.size, session.streamingText.length / 64, showLiveTurn, followTail) {
        if (!followTail) return@LaunchedEffect
        val extra = (if (streaming) 1 else 0) + (if (showLiveTurn) 1 else 0)
        val total = blocks.size + extra
        if (total > 0) listState.animateScrollToItem(total - 1, scrollOffset = TAIL_SCROLL_OFFSET)
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

    CompositionLocalProvider(LocalFrost provides frost) {
    Box(Modifier.fillMaxSize().imePadding()) {
        // 会话流铺满，顶栏 / 输入框浮在上面。铬件高度量完再垫进列表，字不会被挡住。
        val blank = blocks.isEmpty() && !streaming
        Box(Modifier.fillMaxSize().frostSource(frost)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = blank,
            enter = fadeIn(InkMotion.effect()),
            exit = fadeOut(tween(600, easing = InkMotion.Ease)),
        ) {
            BlankPage(starting = session.status == ClaudeCodeManager.SessionStatus.Starting)
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
                    // 新条目淡入（落墨）：只做出现，不做位移，轨道上的标记才不会滑来滑去
                    Box(Modifier.animateItem(fadeInSpec = tween(280, easing = InkMotion.Ease), placementSpec = null, fadeOutSpec = null)) {
                        when (block) {
                            is TranscriptBlock.Single ->
                                TranscriptItem(block.item, isFirst, isLast, vm)

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
                                                vm = vm,
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
                        Column {
                            if (session.streamingThinking.isNotBlank()) {
                                ThinkingEntry(
                                    text = session.streamingThinking,
                                    id = "streaming-thinking",
                                    isFirst = blocks.isEmpty(),
                                    isLast = session.streamingText.isBlank() && !showLiveTurn,
                                    streaming = true,
                                )
                            }
                            if (session.streamingText.isNotBlank()) {
                                AssistantEntry(
                                    text = session.streamingText,
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
                            session = session,
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
                // 终端：和会话同一个 Linux 里的一个真 bash。抽屉里那个入口留着，
                // 但"边看 agent 跑边自己敲一条命令"是会话页上的动作，要够得着。
                // 搜索只在会话记录抽屉里——进行页不再放放大镜。
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
                if (session.status == ClaudeCodeManager.SessionStatus.Running || session.stopping) {
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

/** 一条会话记录按类型分派到各自的渲染器。折叠块展开后走的也是这里，两边一模一样 */
@Composable
private fun TranscriptItem(
    item: ClaudeCodeManager.ChatItem,
    isFirst: Boolean,
    isLast: Boolean,
    vm: ClaudeCodeVM,
) {
    when (item) {
        is ClaudeCodeManager.ChatItem.UserText -> UserEntry(item.text, isFirst, isLast, item.queued)
        is ClaudeCodeManager.ChatItem.AssistantText -> AssistantEntry(
            text = item.text,
            isFirst = isFirst,
            isLast = isLast,
            durationMs = item.durationMs,
            outputTokens = item.outputTokens,
        )
        is ClaudeCodeManager.ChatItem.Thinking -> ThinkingEntry(item.text, item.id, isFirst, isLast)
        is ClaudeCodeManager.ChatItem.Note -> NoteEntry(item.text, item.isError, isFirst, isLast)
        is ClaudeCodeManager.ChatItem.ProcessOutput -> ProcessOutputEntry(item, isFirst, isLast)
        is ClaudeCodeManager.ChatItem.ToolCall -> ToolEntry(
            item = item,
            isFirst = isFirst,
            isLast = isLast,
            // 只有编辑类工具才有快照。这里按工具名过滤而不是去问磁盘：
            // 展开一条就发一次 IO 查询，滑动列表时会打出一串无谓的读盘。
            onRevert = if (item.name in CHECKPOINT_TOOLS) {
                { vm.revertToolCall(item.toolUseId) }
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
    val starting = session.status == ClaudeCodeManager.SessionStatus.Starting
    val tasks = session.runningTasks
    val retry = session.retryNotice
    val runningTool = session.items
        .asReversed()
        .filterIsInstance<ClaudeCodeManager.ChatItem.ToolCall>()
        .firstOrNull { it.status == ClaudeCodeManager.ChatItem.ToolCall.Status.Running }
    val label = listOfNotNull(
        when {
            session.stopping -> "正在结束会话"
            session.applyingSettings -> "正在应用会话设置"
            session.applyingEffort -> "正在应用会话设置（重启 CLI 并续接）"
            starting -> "正在启动 claude"
            else -> statusPhaseLabel(session.statusPhase)
                ?: runningTool?.let { "正在执行 ${it.name}" }
                ?: "正在生成"
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
 * 上一轮的回执：`完成 · 用时 31s · 14:54 · ↓1.2k`。
 *
 * 一轮跑完之后状态行整条消失，"刚才那次跑了多久、吐了多少"就再也无处可查 ——
 * 而这正是放下手机再拿起来时第一个想确认的事（它是刚跑完，还是早就停在这儿了）。
 */
@Composable
private fun TurnReceipt(session: ClaudeCodeManager.SessionState) {
    val finishedAt = session.lastTurnFinishedAt ?: return
    val duration = session.lastTurnDurationMs
    val clock = remember(finishedAt) {
        java.time.Instant.ofEpochMilli(finishedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            HugeIcons.Tick01,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = listOfNotNull(
                duration?.let { "用时 ${formatDuration(it)}" } ?: "已完成",
                clock,
                session.turnOutputTokens.takeIf { it > 0 }?.let { "↓${formatTokens(it)}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

/** `status` 帧的运行阶段。idle 不显示——它等于"没在干活" */
private fun statusPhaseLabel(phase: String?): String? = when (phase) {
    "waiting" -> "等待中"
    "working" -> "工作中"
    "thinking" -> "思考中"
    "responding" -> "回复中"
    "requesting" -> "请求中"
    "compacting" -> "压缩上下文中"
    "busy" -> "处理中"
    "idle", null -> null
    else -> phase
}

/**
 * 一个在跑的子 agent。信息密度对齐终端：类型 · 在做什么 · 最近工具 · 用量。
 * 数据来自 `task_started` / `task_progress` / `task_updated`，按 task_id 原地更新。
 */
@Composable
private fun SubagentLine(task: ClaudeCodeManager.TaskInfo) {
    val meta = listOfNotNull(
        task.lastToolName,
        task.toolUses?.takeIf { it > 0 }?.let { "$it 次调用" },
        task.totalTokens?.takeIf { it >= 1000 }?.let { "${it / 1000}k tok" },
    ).joinToString(" · ")
    val azure = MaterialTheme.sea.sea
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubagentMarker(azure)
        Text(
            text = task.subagentType ?: "子任务",
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
                    "正在启动会话",
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
            "Claude 给出了一份计划",
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
                    "越权路径：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.vermilion,
                )
            }
            pending.decisionReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "触发原因：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 按工具类型渲染入参：编辑类给 diff，Bash 给高亮命令，其余给美化 JSON。
            // 原来这里是 `input.toString()` 截断成一行流水账 —— 用户点「允许」时
            // 其实看不到自己批准了什么，而那正是权限确认唯一的意义。
            ToolInputPreview(name = pending.toolName, input = pending.input)

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
                ) { Text("拒绝") }
                InkButton(
                    onClick = { onAnswer(true, null) },
                    modifier = Modifier.weight(1f),
                ) { Text("允许一次") }
            }
            // 「始终允许」放在主按钮下方：它改的是持久规则，不该和一次性批准同等醒目
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
                SectionTitle("工作区")
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
                var crashed by remember { mutableStateOf(dev.min.code.core.crash.CrashRecorder.read(context) != null) }
                AnimatedVisibility(visible = crashed, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    Notice(
                        text = "上次运行崩溃过，报告在 设置 → 关于 里，可以复制出来发给人看。",
                        tone = NoticeTone.Warn,
                        action = {
                            InkTextButton(onClick = { dev.min.code.core.crash.CrashRecorder.clear(context); crashed = false }) {
                                Text("清除", style = MaterialTheme.typography.labelSmall)
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
                    Text("跳过权限确认", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "允许直接执行工具操作",
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
                    Text("启动会话")
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
