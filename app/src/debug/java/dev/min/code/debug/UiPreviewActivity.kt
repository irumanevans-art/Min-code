package dev.min.code.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.BrandMark
import dev.min.code.ui.components.InkBottomTabs
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.FrostFade
import dev.min.code.ui.components.LocalFrost
import dev.min.code.ui.components.PaperDisc
import dev.min.code.ui.components.frostSource
import dev.min.code.ui.components.frostVeil
import dev.min.code.ui.components.rememberFrostState
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTab
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkToastHost
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.LoadingScreen
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.components.rememberInkToaster
import dev.min.code.ui.session.AssistantEntry
import dev.min.code.ui.session.BlankPage
import dev.min.code.ui.session.ClaudeCodeInputBar
import dev.min.code.ui.session.ClaudeCodeSettingsSheet
import dev.min.code.ui.session.StartPanel
import dev.min.code.ui.session.ToolEntry
import dev.min.code.ui.session.rememberTranscriptLabels
import dev.min.code.ui.session.UserEntry
import dev.min.code.ui.session.ThinkingEntry
import dev.min.code.ui.session.CollapsedWorkEntry
import dev.min.code.ui.session.CollapseWorkFooter
import dev.min.code.ui.setup.ConnectionStep
import dev.min.code.ui.setup.SetupVM
import dev.min.code.ui.theme.FormSwitchController
import dev.min.code.ui.theme.FormSwitchHost
import dev.min.code.ui.theme.LocalFormSwitch
import dev.min.code.ui.theme.LocalTilt
import dev.min.code.ui.theme.MinTheme
import dev.min.code.ui.theme.rememberTilt
import dev.min.code.ui.theme.sea
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Stop
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/** Debug source set only. Fixture callbacks never resolve a ViewModel or start a CLI process. */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requested = intent.getStringExtra("min.preview.scene") ?: "start"
        val initialScene = PreviewScene.entries.firstOrNull { it.key == requested } ?: PreviewScene.Start
        val dark = intent.getStringExtra("min.preview.theme") == "dark"
        val chrome = intent.getBooleanExtra("min.preview.chrome", true)
        val runId = intent.getStringExtra("min.preview.run").orEmpty()
        val variant = intent.getStringExtra("min.preview.variant").orEmpty()
        // 认不出的名字退回海，而不是崩 —— 这是给截图脚本用的入口，写错一个参数
        // 不该让整个验收流程停在一个 IllegalArgumentException 上
        val skin = intent.getStringExtra("min.preview.skin")
            ?.let { name -> SkinStyle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: SkinStyle.SEA
        setContent { Preview(initialScene, dark, skin, chrome, runId, variant) }
    }
}

private enum class PreviewScene(val key: String, val label: String) {
    Start("start", "启动"), Blank("blank", "空白"), Loading("loading", "加载"),
    Setup("setup", "向导"), Controls("controls", "控件"),
    Conversation("conversation", "会话"), Settings("settings", "设置"),
    Sessions("sessions", "会话列表"),
}

@Composable
private fun Preview(
    initialScene: PreviewScene,
    initialDark: Boolean,
    initialSkin: SkinStyle,
    chrome: Boolean,
    runId: String,
    variant: String,
) {
    var scene by rememberSaveable { mutableStateOf(initialScene) }
    var dark by rememberSaveable { mutableStateOf(initialDark) }
    var skin by rememberSaveable { mutableStateOf(initialSkin) }
    var settingsOpen by rememberSaveable { mutableStateOf(initialScene == PreviewScene.Settings) }
    var session by remember { mutableStateOf(fixtureSession()) }
    var chineseDescriptions by remember { mutableStateOf(true) }
    val switch = remember { FormSwitchController() }
    val tilt = rememberTilt()
    val (toaster, toastState) = rememberInkToaster()
    // LocalSeaField 由 MinTheme 按风格提供，这里不再自己造一份
    MinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, skin) {
        CompositionLocalProvider(
            LocalFormSwitch provides switch,
            LocalTilt provides tilt,
            LocalToaster provides toaster,
        ) {
            FormSwitchHost(switch) {
                val overlaySession = !chrome &&
                    (scene == PreviewScene.Conversation || scene == PreviewScene.Settings)
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (!overlaySession) InkTopBar(
                            title = "Min",
                            brand = true,
                            subtitle = if (scene == PreviewScene.Conversation || scene == PreviewScene.Settings) "已连接 · claude" else null,
                            navigationIcon = {
                                InkIconButton(HugeIcons.Menu03, "场景", onClick = {
                                    scene = PreviewScene.entries[(scene.ordinal + 1) % PreviewScene.entries.size]
                                    settingsOpen = scene == PreviewScene.Settings
                                })
                            },
                            actions = {
                                if (chrome) {
                                    // 昼夜和风格两个分段。横向挤，所以各自收窄；
                                    // 截图脚本走 extra 传参，这两个只给人眼来回比对用
                                    InkSegmented(
                                        options = listOf("浅", "深"),
                                        selected = if (dark) 1 else 0,
                                        onSelect = { index ->
                                            switch.switch(null, index == 1, skin) { dark = index == 1 }
                                        },
                                        modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                    )
                                    val skins = SkinStyle.entries
                                    InkSegmented(
                                        options = listOf("海", "云", "陶"),
                                        selected = skins.indexOf(skin),
                                        onSelect = { index ->
                                            switch.switch(null, dark, skins[index]) { skin = skins[index] }
                                        },
                                        modifier = Modifier.width(132.dp).padding(end = 8.dp),
                                    )
                                } else {
                                    InkIconButton(HugeIcons.Refresh01, "刷新状态", onClick = {})
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (chrome) InkBottomTabs(
                            tabs = PreviewScene.entries.map { InkTab(sceneIcon(it), it.label) },
                            selected = scene.ordinal,
                            onSelect = {
                                scene = PreviewScene.entries[it]
                                settingsOpen = scene == PreviewScene.Settings
                            },
                        )
                    },
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (overlaySession) Modifier else Modifier.padding(padding))
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        when (scene) {
                            PreviewScene.Start -> StartPanel(
                                session = ClaudeCodeManager.SessionState(),
                                cliVersion = "2.1.261",
                                onStart = { scene = PreviewScene.Blank },
                            )
                            PreviewScene.Blank -> BlankPage(starting = false)
                            PreviewScene.Loading -> LoadingScreen(detail = "正在读取工作区", progress = 0.64f)
                            PreviewScene.Setup -> ConnectionStep(SetupVM.State(loading = false), onSave = { _, _ -> scene = PreviewScene.Start })
                            PreviewScene.Controls -> ControlsPreview()
                            PreviewScene.Sessions -> SessionsPreview()
                            PreviewScene.Conversation, PreviewScene.Settings -> ConversationPreview(
                                session = session,
                                onSession = { session = it },
                                onOpenSettings = { settingsOpen = true },
                                variant = variant,
                                overlayChrome = overlaySession,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    InkToastHost(toastState, Modifier.align(Alignment.BottomCenter))
                }
            }
            if (settingsOpen) ClaudeCodeSettingsSheet(
                session = session,
                initialSection = null,
                chineseDescriptions = chineseDescriptions,
                onSetChineseDescriptions = { chineseDescriptions = it },
                onDismiss = { settingsOpen = false },
                onSetModel = { model, _ -> session = session.copy(model = model, options = session.options.copy(model = model)) },
                onSetPermissionMode = { session = session.copy(permissionMode = it) },
                onApplyEffort = { effort, ultra -> session = session.copy(options = session.options.copy(effort = effort, ultracode = ultra)) },
                onSetPromptCacheTtl = { session = session.copy(options = session.options.copy(promptCacheTtl = it)) },
                onSetCwd = { session = session.copy(cwd = it) },
                onPickCommand = { settingsOpen = false },
            )
            LaunchedEffect(scene, dark, skin, runId) {
                // The capture script waits for this unique run marker before its animation settling delay.
                // 风格也进 key 与 marker：换风格要换一张纹理，不重新等一次的话，
                // 截图脚本会在旧底还没被新底顶掉的那一帧按下快门
                withFrameNanos { }
                withFrameNanos { }
                Log.i(
                    "UiPreview",
                    "READY $runId ${scene.key} ${if (dark) "dark" else "light"} ${skin.name.lowercase()}",
                )
            }
        }
    }
}

private fun sceneIcon(scene: PreviewScene) = when (scene) {
    PreviewScene.Start -> HugeIcons.Play
    PreviewScene.Blank -> HugeIcons.Folder01
    PreviewScene.Loading -> HugeIcons.Refresh01
    PreviewScene.Setup -> HugeIcons.Package
    PreviewScene.Controls -> HugeIcons.Menu03
    PreviewScene.Conversation -> HugeIcons.ComputerTerminal01
    PreviewScene.Settings -> HugeIcons.Settings02
    PreviewScene.Sessions -> HugeIcons.Folder01
}

/** 会话抽屉（右栏面板）：当前会话是蓝底 + 金框 + 「使用中」，活着的带湛点。数据全在内存。 */
@Composable
private fun SessionsPreview() {
    val now = System.currentTimeMillis()
    val entries = listOf(
        dev.min.code.ui.session.ClaudeCodeVM.SessionEntry("a", "检查工作区，整理这次修改", now - 120_000, 12, isLive = true, isActive = true, pinned = true, titled = true),
        dev.min.code.ui.session.ClaudeCodeVM.SessionEntry("b", "把 proot 补丁拆成独立模块", now - 3_600_000, 41, isLive = true, isActive = false, category = "工作", titled = true),
        dev.min.code.ui.session.ClaudeCodeVM.SessionEntry("c", "文件页导入导出联调", now - 86_400_000, 7, isLive = false, isActive = false, category = "工作"),
        dev.min.code.ui.session.ClaudeCodeVM.SessionEntry("d", "签名动画：龙凤与轨迹的双层提取方案讨论，以及输入坞底板的裁切位置", now - 172_800_000, 3, isLive = false, isActive = false),
    )
    dev.min.code.ui.session.ClaudeCodeSessionDrawer(
        sessions = entries,
        permanent = true,
        onNewSession = {},
        onOpenSession = {},
        onDeleteSession = {},
        onCopyTranscript = {},
    )
}

@Composable
private fun ControlsPreview() {
    var token by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionTitle("连接")
            InkTextField(token, { token = it; submitted = false }, label = "API 密钥", placeholder = "输入密钥", singleLine = true, modifier = Modifier.fillMaxWidth())
            InkSegmented(listOf("默认", "计划", "自动编辑"), mode, { mode = it })
            SettingRow("中文说明", onClick = { enabled = !enabled }, trailing = { InkSwitch(enabled, { enabled = it }) })
            SettingRow("记住本次选择", onClick = { checked = !checked }, trailing = { InkCheckbox(checked, { checked = it }) })
            InkDivider(brush = true)
            SectionTitle("环境")
            InkLineProgress(progress = 0.64f)
            Notice(if (submitted) "设置已应用" else "工作区已就绪", tone = NoticeTone.Info)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton(onClick = { submitted = true }, modifier = Modifier.weight(1f), icon = HugeIcons.Play) { Text("应用") }
                InkButton(onClick = { token = ""; submitted = false }, modifier = Modifier.weight(1f), tone = InkButtonTone.Paper) { Text("重置") }
            }
            InkButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) { Text("等待连接") }
        }
    }
}

@Composable
private fun ConversationPreview(
    session: ClaudeCodeManager.SessionState,
    onSession: (ClaudeCodeManager.SessionState) -> Unit,
    onOpenSettings: () -> Unit,
    variant: String,
    overlayChrome: Boolean,
) {
    var messages by remember { mutableStateOf(emptyList<String>()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val frost = rememberFrostState()
    val longText = remember {
        buildString {
            repeat(10) { section ->
                append("## ${section + 1}. 工作区检查\n\n")
                append("依赖安装完成，继续验证命令输出与运行结果。每项结果均保留完整记录，长段正文在滚动、展开和流式增长时保持相同的布局。\n\n")
                append("| 组件 | 状态 |\n| --- | --- |\n| Python | 运行正常 |\n| R | 依赖齐全 |\n\n")
            }
            append("验证完成，所有结果已记录。")
        }
    }
    var generated by remember { mutableStateOf("") }
    LaunchedEffect(variant) {
        if (variant == "streaming") {
            for (end in 40..longText.length step 40) {
                generated = longText.take(end)
                delay(160)
            }
        }
    }
    val tool = remember {
        ChatItem.ToolCall(
            id = "preview-read", toolUseId = "preview-read", name = "Bash",
            input = JsonObject(mapOf("command" to JsonPrimitive("git diff --stat"))),
            status = if (variant == "streaming") ChatItem.ToolCall.Status.Running else ChatItem.ToolCall.Status.Done,
            result = "3 files changed, 42 insertions(+), 18 deletions(-)",
        )
    }
    // 工具卡的字头包（行数、全部替换这些）是按界面语言走的：这三张卡专门用来
    // 在浅色/深色、中英两个语言下验收收口后的文案
    val tools = remember {
        listOf(
            tool,
            ChatItem.ToolCall(
                id = "preview-edit", toolUseId = "preview-edit", name = "Edit",
                input = JsonObject(
                    mapOf(
                        "file_path" to JsonPrimitive("/workspace/Sea.kt"),
                        "old_string" to JsonPrimitive("val a = 1"),
                        "new_string" to JsonPrimitive("val a = 2"),
                        "replace_all" to JsonPrimitive(true),
                    ),
                ),
                status = ChatItem.ToolCall.Status.Done,
            ),
            ChatItem.ToolCall(
                id = "preview-grep", toolUseId = "preview-grep", name = "Read",
                input = JsonObject(
                    mapOf(
                        "file_path" to JsonPrimitive("/workspace/README.md"),
                        "offset" to JsonPrimitive(100),
                        "limit" to JsonPrimitive(100),
                    ),
                ),
                status = ChatItem.ToolCall.Status.Done,
                result = (1..40).joinToString("\n") { "line $it" },
            ),
        )
    }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(overlayChrome) {
        if (overlayChrome) list.scrollToItem(0, scrollOffset = 120)
    }
    CompositionLocalProvider(LocalFrost provides frost) {
        Box(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().frostSource(frost),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (overlayChrome) 28.dp else 8.dp,
                    bottom = 96.dp,
                ),
            ) {
                item(key = "lead") {
                    AssistantEntry(
                        "设了 acceptEdits + 长 allowlist，诊断命令仍被拒。用户要的是少打断；实际是「编辑文件自动过、看一眼磁盘不行」。研究场景里读机器状态和写代码一样常见。\n\n" +
                            "系统里有 memory 机制。我上一轮把沙箱限制写成了 env-constraints：「不要重试 apt」「不要 rm」——这是把产品/镜像 bug 内化成 agent 行为。你指出这点是对的。\n\n" +
                            "建议：记忆系统应区分用户偏好、项目事实、环境缺陷。不要让 agent 把 deny 当成永久规范。",
                        isFirst = true,
                        isLast = false,
                    )
                }
                item(key = "user") {
                    UserEntry("检查工作区，整理这次修改。", isFirst = false, isLast = false)
                }
                item(key = "thinking") { ThinkingEntry("检查依赖和运行状态，然后核对输出。", "preview-thinking", false, false, streaming = variant == "streaming") }
                item(key = "tools") {
                    if (expanded) Column {
                        tools.forEach {
                            ToolEntry(it, isFirst = false, isLast = false, labels = rememberTranscriptLabels())
                        }
                        CollapseWorkFooter(false) { expanded = false }
                    } else CollapsedWorkEntry(tools, false, false) { expanded = true }
                }
                item(key = "assistant") {
                    AssistantEntry(
                        when (variant) {
                            "long" -> longText
                            "streaming" -> generated
                            else -> longText
                        },
                        isFirst = false, isLast = messages.isEmpty(),
                        streaming = variant == "streaming",
                    )
                }
                itemsIndexed(messages) { index, message -> UserEntry(message, isFirst = false, isLast = index == messages.lastIndex) }
            }
            if (overlayChrome) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(88.dp + FrostFade)
                        .frostVeil(fromTop = true, hold = 88f / (88f + 8f)),
                )
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 0.dp)
                            .graphicsLayer { clip = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperDisc {
                            InkIconButton(HugeIcons.Menu03, "会话列表", onClick = {})
                        }
                        Spacer(Modifier.width(10.dp))
                        BrandMark()
                        Spacer(Modifier.weight(1f))
                        PaperDisc {
                            InkIconButton(
                                HugeIcons.Stop,
                                "停止会话",
                                onClick = {},
                                tint = MaterialTheme.sea.vermilion,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(96.dp + FrostFade)
                    .frostVeil(fromTop = false, hold = 96f / (96f + 8f)),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ClaudeCodeInputBar(
                        session = session.copy(busy = variant == "streaming"),
                        onSend = { text, _ ->
                            messages = messages + text
                            scope.launch { delay(50); list.animateScrollToItem(5 + messages.lastIndex) }
                        },
                        onInterrupt = {},
                        onRunShell = { command -> messages = messages + "!$command" },
                        onSetModel = { model, _ -> onSession(session.copy(model = model, options = session.options.copy(model = model))) },
                        onSetPermissionMode = { onSession(session.copy(permissionMode = it)) },
                        onApplyEffort = { effort, ultra -> onSession(session.copy(options = session.options.copy(effort = effort, ultracode = ultra))) },
                        onSetPromptCacheTtl = { onSession(session.copy(options = session.options.copy(promptCacheTtl = it))) },
                        onRequestModels = {},
                        onSetCwd = { onSession(session.copy(cwd = it)) },
                        onOpenLocalCommand = { onOpenSettings() },
                        // 比 costText 里那笔大，底栏才会走到「今日 … · 本次 …」那一支
                        dailyCostUsd = 1.34,
                    )
                }
            }
        }
    }
}

/**
 * 底栏那一行故意取**最挤**的一组值：长模型名 + 今日和本次两个金额都在 + 六位数上下文。
 *
 * 1.0.11 之前停止键会在这组值下被整个挤没（它和用量挤在同一个 Row 里争宽度，
 * 用量先量、吃满，停止键被量成 0 宽）。预览页要是只给一组宽松的值，
 * 这种"控件凭空消失"的回归在真机上跑到才发现。
 */
private fun fixtureSession() = ClaudeCodeManager.SessionState(
    status = SessionStatus.Running,
    sessionId = "ui-preview",
    model = "claude-sonnet-5[1m]",
    options = ClaudeCodeManager.SessionOptions(model = "claude-sonnet-5[1m]", effort = "high"),
    // 逼近上限：潮环画得出一段真的弧，同时能看到朱砂的告警态（CONTEXT_WARN_RATIO = 0.85）
    contextTokens = 880_000,
    contextLimit = 1_000_000,
    costText = "Total cost: \$0.6500",
    relayModelsChecked = true,
)
