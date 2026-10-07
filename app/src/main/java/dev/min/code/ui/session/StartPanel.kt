package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.StartChoices
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.theme.sea
import dev.min.code.ui.components.HeroCanvas
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SeaSurface
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play

/**
 * 开始面板顶部那条引擎分段要的东西（设计 §9.2 B7）。分开打包而不是散着传：它们是一个整体——
 * Min 关着（[minVisible] 为假）时分段里没有 Min，也不会选中它。
 *
 * Codex 的会话页在 B9 之前仍是单独一页，所以点「Codex」不是选中它，而是 [onOpenCodex] 直接过去。
 */
internal data class StartEngines(
    val minVisible: Boolean,
    val availability: Map<EngineKind, EngineAvailability>,
    val minChoices: StartChoices?,
    /** 默认选中的引擎 = 上次用的 */
    val initial: EngineKind,
    val onChoose: (EngineKind) -> Unit,
    val onOpenCodex: () -> Unit,
) {
    companion object {
        /** 只有 Claude Code（Min 关着、测试、预览） */
        val ClaudeOnly = StartEngines(false, emptyMap(), null, EngineKind.ClaudeCode, {}, {})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartPanel(
    session: AgentSessionState,
    cliVersion: String?,
    /** 点「启动」时的选择。Claude Code 只带「跳过权限确认」，Min 带供应商档 / 模型 / 权限模式；其余沿用上次的，由会话层取 */
    onStart: (StartRequest) -> Unit,
    /** 面板上默认选中的工作目录（上次用过的） */
    defaultCwd: String,
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
    /** false = 还没有可用的供应商，见 [SessionVM.connected] */
    connected: Boolean = true,
    onOpenProviders: () -> Unit = {},
    engines: StartEngines = StartEngines.ClaudeOnly,
) {
    // Min 关掉时即使存着「上次选的是 Min」也回到 Claude Code
    var engine by rememberSaveable(engines.initial, engines.minVisible) {
        mutableStateOf(if (engines.initial == EngineKind.MinAgent && !engines.minVisible) EngineKind.ClaudeCode else engines.initial)
    }
    val minAvailability = engines.availability[EngineKind.MinAgent] ?: EngineAvailability.Ready
    var minProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var minModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var minModelTyped by rememberSaveable { mutableStateOf("") }
    var minModeId by rememberSaveable { mutableStateOf<String?>(null) }
    val minProfile = engines.minChoices?.let { choices ->
        choices.profiles.firstOrNull { it.id == minProfileId }
            ?: choices.profiles.firstOrNull { it.id == choices.defaultProfileId }
            ?: choices.profiles.firstOrNull()
    }
    // 手填的优先；其次点选的（要仍属于当前档）；最后是这个档上次用的 / 第一个
    val minModel = minModelTyped.trim().ifBlank {
        minModelId?.takeIf { id -> minProfile?.models?.any { it.id == id } == true } ?: minProfile?.defaultModelId.orEmpty()
    }
    val minMode = minModeId ?: engines.minChoices?.defaultModeId
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    var cwd by rememberSaveable { mutableStateOf(defaultCwd) }
    var pickingCwd by remember { mutableStateOf(false) }
    // 挂在 ConnectPrompt 外面：登录成功那一刻 ConnectPrompt 就收起了，见它的注释
    var subscriptionLogin by remember { mutableStateOf(false) }
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
                if (engines.minVisible) {
                    // 引擎分段：Claude Code · Codex · Min。不可用的字变淡但仍可点，点了在下面写原因和去处
                    val segments = listOf(EngineKind.ClaudeCode, EngineKind.Codex, EngineKind.MinAgent)
                    InkSegmented(
                        options = segments.map { engineName(it) },
                        selected = segments.indexOf(engine),
                        dimmed = segments.withIndex().filter { (_, kind) ->
                            kind != EngineKind.ClaudeCode && engines.availability[kind]?.isReady == false
                        }.map { it.index }.toSet(),
                        onSelect = { index ->
                            val picked = segments[index]
                            if (picked == EngineKind.Codex) {
                                engines.onOpenCodex()
                            } else {
                                engine = picked
                                engines.onChoose(picked)
                            }
                        },
                    )
                }
                // 产品名，不翻
                Text(engineName(engine), style = MaterialTheme.typography.headlineSmall)
                CwdPathRow(
                    cwd = cwd,
                    trailing = if (engine == EngineKind.ClaudeCode) cliVersion else null,
                    onClick = { pickingCwd = true },
                )
                Spacer(Modifier.height(10.dp))
                InkDivider()
                if (engine == EngineKind.MinAgent) {
                    MinStartSection(
                        availability = minAvailability,
                        choices = engines.minChoices,
                        profileId = minProfile?.id,
                        onProfile = { minProfileId = it; minModelId = null; minModelTyped = "" },
                        modelId = minModel,
                        onModel = { minModelId = it; minModelTyped = "" },
                        typedModel = minModelTyped,
                        onTypedModel = { minModelTyped = it },
                        modeId = minMode,
                        onMode = { minModeId = it },
                        onOpenProviders = onOpenProviders,
                    )
                    session.errorMessage?.let { Notice(text = it, tone = NoticeTone.Error, maxLines = 6) }
                    InkButton(
                        onClick = {
                            onStart(
                                StartRequest(
                                    engine = EngineKind.MinAgent, cwd = cwd,
                                    profileId = minProfile?.id, modelId = minModel.takeIf { it.isNotBlank() }, modeId = minMode,
                                ),
                            )
                        },
                        enabled = minAvailability.isReady && minProfile != null && minModel.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        icon = HugeIcons.Play,
                    ) {
                        Text(stringResource(R.string.session_start_action))
                    }
                    return@Column
                }
                AnimatedVisibility(visible = !connected, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    ConnectPrompt(
                        onOpenProviders = onOpenProviders,
                        onSubscriptionLogin = { subscriptionLogin = true },
                    )
                }
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
                    onClick = { onStart(StartRequest(EngineKind.ClaudeCode, cwd, skipPermissions = skipPermissions)) },
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
    if (subscriptionLogin) {
        ClaudeSubscriptionSheet(onDismiss = { subscriptionLogin = false })
    }
}

/** 产品名，不翻译 */
private fun engineName(kind: EngineKind): String = when (kind) {
    EngineKind.ClaudeCode -> "Claude Code"
    EngineKind.Codex -> "Codex"
    EngineKind.MinAgent -> "Min"
}

/**
 * Min 选中时开始面板上的那一段：供应商（默认最近一次用的档）、模型、权限模式。
 * 引擎不可用时只剩一句原因和去处——点不了「启动」（调用方已按 [availability] 禁用）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinStartSection(
    availability: EngineAvailability,
    choices: StartChoices?,
    profileId: String?,
    onProfile: (String) -> Unit,
    modelId: String,
    onModel: (String) -> Unit,
    typedModel: String,
    onTypedModel: (String) -> Unit,
    modeId: String?,
    onMode: (String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    if (!availability.isReady) {
        val reason = when (availability) {
            EngineAvailability.NeedsInstall -> stringResource(R.string.engine_needs_install)
            EngineAvailability.NeedsProvider -> stringResource(R.string.engine_needs_provider)
            is EngineAvailability.Disabled -> availability.reason
            EngineAvailability.Ready -> ""
        }
        Notice(
            text = reason,
            tone = NoticeTone.Warn,
            action = if (availability == EngineAvailability.NeedsProvider) {
                {
                    InkTextButton(onClick = onOpenProviders) {
                        Text(stringResource(R.string.start_min_open_providers), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else null,
        )
        return
    }
    val profiles = choices?.profiles.orEmpty()
    val current = profiles.firstOrNull { it.id == profileId }
    SectionTitle(stringResource(R.string.start_min_provider))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            InkChip(label = profile.label, selected = profile.id == profileId, onClick = { onProfile(profile.id) })
        }
    }
    SectionTitle(stringResource(R.string.start_min_model))
    val models = current?.models.orEmpty()
    if (models.isEmpty()) {
        Text(
            stringResource(R.string.start_min_no_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { model ->
                InkChip(label = model.label, selected = typedModel.isBlank() && model.id == modelId, onClick = { onModel(model.id) }, monospace = true)
            }
        }
    }
    InkTextField(
        value = typedModel,
        onValueChange = onTypedModel,
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.start_min_model_other),
        singleLine = true,
        monospace = true,
    )
    val modes = choices?.modes.orEmpty()
    SectionTitle(stringResource(R.string.start_min_mode))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            InkChip(label = mode.label, selected = mode.id == modeId, onClick = { onMode(mode.id) })
        }
    }
    // 绕过权限那一档要把话说在前面（朱字：它是判定）
    modes.firstOrNull { it.id == modeId }?.warning?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sea.vermilion)
    }
}
