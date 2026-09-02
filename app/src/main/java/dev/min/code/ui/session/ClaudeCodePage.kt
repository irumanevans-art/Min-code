package dev.min.code.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.ui.setup.SetupPage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import dev.min.code.ui.nav.Screen
import dev.min.code.core.claudecode.CHECKPOINT_TOOLS
import dev.min.code.core.claudecode.ASK_USER_QUESTION_TOOL
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.PermissionSuggestion
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.theme.CustomColors
import dev.min.code.util.writeClipboardText
import org.koin.androidx.compose.koinViewModel

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
 */
@Composable
fun ClaudeCodePage(vm: ClaudeCodeVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val setup by vm.setup.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val maintenance by vm.maintenance.collectAsStateWithLifecycle()
    val liveSessions by vm.liveSessions.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showPlan by remember { mutableStateOf(false) }
    var showMaintenance by remember { mutableStateOf(false) }

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

    // 会话跑起来后周期性拉一次用量/花费，状态条才有东西显示
    LaunchedEffect(session.status, session.busy) {
        if (session.status == ClaudeCodeManager.SessionStatus.Running && !session.busy) {
            vm.refreshUsage()
            vm.refreshPlan()
            vm.refreshSessions()
        }
    }

    // 平板 / 横屏：会话列表常驻左栏，不再是抽屉。840dp 是 Material 的 expanded 断点
    val wide = LocalConfiguration.current.screenWidthDp >= 840 && setup.ready
    val drawer: @Composable () -> Unit = {
            ClaudeCodeSessionDrawer(
                permanent = wide,
                sessions = sessions,
                onNewSession = {
                    scope.launch { drawerState.close() }
                    vm.newSession()
                },
                onOpenSession = { id ->
                    scope.launch { drawerState.close() }
                    vm.openSession(id)
                },
                onDeleteSession = vm::deleteSession,
                onOpenFiles = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Screen.Files())
                },
                onOpenTerminal = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Screen.Terminal)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Screen.Settings)
                },
                onOpenMaintenance = {
                    scope.launch { drawerState.close() }
                    vm.loadEnvironment()
                    showMaintenance = true
                },
                // 没内容时不给这一行 —— 一个复制出来是空字符串的按钮只会让人以为坏了
                onCopyTranscript = if (session.items.isNotEmpty()) {
                    {
                        scope.launch { drawerState.close() }
                        context.writeClipboardText(session.items.toTranscriptText())
                    }
                } else null,
            )
    }
    AdaptiveDrawer(wide = wide, drawerState = drawerState, gesturesEnabled = setup.ready, drawer = drawer) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // 必须限死单行 + 省略号：TopAppBar 把剩余宽度分给 title，
                        // actions 一多标题就会被压到几像素宽然后逐字竖排（真机上出过）
                        Column {
                            Text("Min", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = session.subtitle(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        // 这一页是首页，没有"返回"。装好之前抽屉里没东西可看，左上角直接给设置
                        if (wide) {
                            Unit
                        } else if (setup.ready) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(HugeIcons.Menu03, "会话列表")
                            }
                        } else {
                            IconButton(onClick = { navController.navigate(Screen.Settings) }) {
                                Icon(HugeIcons.Settings02, "设置")
                            }
                        }
                    },
                    actions = {
                        if (session.status == ClaudeCodeManager.SessionStatus.Running) {
                            IconButton(onClick = { vm.stop() }) { Icon(HugeIcons.Stop, "停止会话") }
                        }
                        IconButton(onClick = { vm.refresh() }) { Icon(HugeIcons.Refresh01, "刷新状态") }
                    },
                    colors = CustomColors.topBarColors,
                )
            },
            containerColor = CustomColors.topBarColors.containerColor,
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when {
                    setup.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    // 三步向导嵌在这一页里：装好之前顶栏照常在，装好那一刻 refresh() 就切到启动面板
                    !setup.ready -> SetupPage(onReady = vm::refresh)

                    session.status == ClaudeCodeManager.SessionStatus.Idle ||
                        session.status == ClaudeCodeManager.SessionStatus.Closed ||
                        session.status == ClaudeCodeManager.SessionStatus.Failed ->
                        StartPanel(session = session, cliVersion = setup.cliVersion, onStart = vm::start)

                    else -> SessionContent(
                        session = session,
                        vm = vm,
                        onOpenPlan = { showPlan = true },
                    )
                }
            }
        }
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
}

/**
 * 自动跟随时把最后一项的底部顶到视口下沿。animateScrollToItem 的语义是把目标项对齐到
 * 视口**顶部**，直接用会导致"滚到最后一条却停在屏幕上方"，看着像弹回去了。
 */
private const val TAIL_SCROLL_OFFSET = 100_000

/** 宽屏常驻左栏、窄屏模态抽屉，内容一份 */
@Composable
private fun AdaptiveDrawer(
    wide: Boolean,
    drawerState: androidx.compose.material3.DrawerState,
    gesturesEnabled: Boolean,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (wide) {
        androidx.compose.material3.PermanentNavigationDrawer(drawerContent = drawer, content = content)
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = drawer,
            content = content,
        )
    }
}

private fun ClaudeCodeManager.SessionState.subtitle(): String = when {
    applyingEffort -> "正在应用 effort…"
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

// ---------------------------------------------------------------------------
// 会话内容
// ---------------------------------------------------------------------------

@Composable
private fun SessionContent(
    session: ClaudeCodeManager.SessionState,
    vm: ClaudeCodeVM,
    onOpenPlan: () -> Unit,
) {
    val listState = rememberLazyListState()
    val streaming = session.streamingText.isNotBlank() || session.streamingThinking.isNotBlank()
    val lastIndex = session.items.lastIndex

    // 交互式斜杠命令（/mcp、/agents、/memory…）打开的配置面板。它们改的是 Rootfs 里的
    // 配置文件，和「会话设置」那张 sheet 是两回事，所以状态提在这里而不是输入栏内部。
    var configCommand by remember { mutableStateOf<LocalSlash?>(null) }

    // 只有当用户本来就贴在底部时才自动跟随。之前无条件滚动，一往回翻就被拽走。
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            // 手停下来那一刻看还能不能往下滚：不能 = 在底部 = 继续跟随
            if (!scrolling) followTail = !listState.canScrollForward
        }
    }
    LaunchedEffect(session.items.size, session.streamingText.length / 64, followTail) {
        if (!followTail) return@LaunchedEffect
        val total = session.items.size + if (streaming) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1, scrollOffset = TAIL_SCROLL_OFFSET)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // **不能加 verticalArrangement 间距**：条目之间一留白，左轨道就断成一节一节。
            // 留白放在 TranscriptEntry 内部。
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            itemsIndexed(session.items, key = { _, item -> item.id }) { index, item ->
                val isFirst = index == 0
                // 有流式内容时最后一条不是尾巴，轨道要继续往下延伸
                val isLast = index == lastIndex && !streaming
                when (item) {
                    is ClaudeCodeManager.ChatItem.UserText ->
                        UserEntry(item.text, isFirst, isLast)

                    is ClaudeCodeManager.ChatItem.AssistantText ->
                        AssistantEntry(item.text, isFirst, isLast)

                    is ClaudeCodeManager.ChatItem.Thinking ->
                        ThinkingEntry(item.text, item.id, isFirst, isLast)

                    is ClaudeCodeManager.ChatItem.ToolCall ->
                        ToolEntry(
                            item = item,
                            isFirst = isFirst,
                            isLast = isLast,
                            // 只有编辑类工具才有快照。这里按工具名过滤而不是去问磁盘：
                            // 展开一条就发一次 IO 查询，滑动列表时会打出一串无谓的读盘。
                            onRevert = if (item.name in CHECKPOINT_TOOLS) {
                                { vm.revertToolCall(item.toolUseId) }
                            } else null,
                        )

                    is ClaudeCodeManager.ChatItem.Note ->
                        NoteEntry(item.text, item.isError, isFirst, isLast)
                }
            }
            if (streaming) {
                item(key = "streaming") {
                    Column {
                        if (session.streamingThinking.isNotBlank()) {
                            ThinkingEntry(
                                text = session.streamingThinking,
                                id = "streaming-thinking",
                                isFirst = session.items.isEmpty(),
                                isLast = session.streamingText.isBlank(),
                                streaming = true,
                            )
                        }
                        if (session.streamingText.isNotBlank()) {
                            AssistantEntry(
                                text = session.streamingText,
                                isFirst = false,
                                isLast = true,
                                streaming = true,
                            )
                        }
                    }
                }
            }
        }

        session.plan?.takeIf { it.isNotBlank() }?.let { PlanBanner(onOpenPlan) }

        RunningStatusBar(session = session, onInterrupt = vm::interrupt)

        session.errorMessage?.let {
            // 限行：这段排在输入栏之前且没有 weight，500 字符的 stderr 会把高度吃掉，
            // 横屏时输入栏会被挤出屏幕
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        ClaudeCodeInputBar(
            session = session,
            onSend = vm::send,
            onInterrupt = vm::interrupt,
            onSetModel = vm::setModel,
            onSetPermissionMode = vm::setPermissionMode,
            onApplyEffort = { effort, ultracode -> vm.applyEffort(effort, ultracode) },
            onRequestModels = vm::refreshModels,
            onRefreshUsage = vm::refreshUsage,
            onImportFile = vm::importFile,
            onSetCwd = vm::setCwd,
            onDeleteWorkspaceFile = vm::deleteWorkspaceFile,
            onSearchFiles = vm::searchFiles,
            onLoadImage = vm::loadImage,
            onOpenLocalCommand = { configCommand = it },
        )
    }

    configCommand?.let { command ->
        ClaudeCodeConfigSheet(
            command = command,
            vm = vm,
            onDismiss = { configCommand = null },
        )
    }
}

/**
 * "它现在在干什么"。之前这件事没有落点 —— busy 只体现为占位符文案变了、
 * 顶栏多一个停止按钮。手机放下再拿起来时，这一行是最先要看的东西。
 */
@Composable
private fun RunningStatusBar(
    session: ClaudeCodeManager.SessionState,
    onInterrupt: () -> Unit,
) {
    val starting = session.status == ClaudeCodeManager.SessionStatus.Starting
    val tasks = session.runningTasks
    val retry = session.retryNotice
    // 后台子任务和重试可能在 busy 之外发生，所以不能只看 busy
    if (!session.busy && !starting && retry == null && tasks.isEmpty()) return
    // 正在跑的那个工具就是当前动作；没有工具在跑说明模型在生成文本
    val runningTool = session.items
        .asReversed()
        .filterIsInstance<ClaudeCodeManager.ChatItem.ToolCall>()
        .firstOrNull { it.status == ClaudeCodeManager.ChatItem.ToolCall.Status.Running }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(
                // 优先用 CLI 自己报的阶段和实时短语（system/status + system/task_summary）——
                // 那是电脑端显示的同一份数据。拿不到时才退回"看哪个工具在跑"的推测。
                text = listOfNotNull(
                    when {
                        session.applyingEffort -> "正在应用 effort（续接当前会话）"
                        starting -> "正在启动 claude"
                        else -> statusPhaseLabel(session.statusPhase)
                            ?: runningTool?.let { "正在执行 ${it.name}" }
                            ?: "正在生成"
                    },
                    session.statusDetail?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.busy) {
                TextButton(onClick = onInterrupt, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("停止", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 退避重试：不显示的话用户只看到界面卡住不动，不知道 CLI 正在重试
        retry?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 子 agent：每个一行，带类型 / 最近工具 / 用量
        tasks.forEach { task -> SubagentLine(task) }
    }
}

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            HugeIcons.AiBrain01,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = task.subagentType ?: "子任务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
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

/** 计划只在真有计划时出现，不像以前那样在底栏常驻一个多数时候点不动的 chip */
@Composable
private fun PlanBanner(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HugeIcons.LeftToRightListBullet, null, Modifier.size(14.dp))
            Text("Claude 给出了一份计划", style = MaterialTheme.typography.labelMedium)
        }
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
    ModalBottomSheet(
        onDismissRequest = { /* 只能按按钮，见上 */ },
        sheetState = sheetState,
        properties = ModalBottomSheetDefaults.properties(shouldDismissOnBackPress = false),
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
                Icon(toolIcon(pending.toolName), null, Modifier.size(18.dp))
                Text(
                    pending.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            pending.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            pending.blockedPath?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "越权路径：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
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

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onAnswer(false, null) },
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
                Button(
                    onClick = { onAnswer(true, null) },
                    modifier = Modifier.weight(1f),
                ) { Text("允许一次") }
            }
            // 「始终允许」放在主按钮下方：它改的是持久规则，不该和一次性批准同等醒目
            pending.suggestions.forEach { suggestion ->
                OutlinedButton(
                    onClick = { onAnswer(true, suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Tick01, null, Modifier.size(15.dp))
                    Text(
                        suggestion.label,
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPanel(
    session: ClaudeCodeManager.SessionState,
    cliVersion: String?,
    onStart: (ClaudeCodeManager.SessionOptions) -> Unit,
) {
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("启动 Claude Code", style = MaterialTheme.typography.titleMedium)
        Text(
            "工作目录 /workspace。启动后模型、权限模式、思考强度都能在底部随时调整，" +
                "左上角切换历史会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        cliVersion?.let {
            Text(
                "CLI $it",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.errorMessage?.let {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    it,
                    modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = skipPermissions, onCheckedChange = { skipPermissions = it })
            Text(
                "以 Bypass permissions 启动（跳过所有权限确认，风险自负）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { onStart(session.options.copy(skipPermissions = skipPermissions, resumeSessionId = null)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("启动会话", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
