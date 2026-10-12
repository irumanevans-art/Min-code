package dev.min.code.ui.session

import androidx.annotation.StringRes
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.ConfigFiles
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.engine.claude.ClaudeStateMapper
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog

/**
 * 会话页里「哪个引擎长什么样」的**唯一分派处**（设计 §3.8；`AgentSession` 头注释的第 3 条规矩）。
 *
 * 页面、输入栏、抽屉、设置面板里不写 `if (engine == Min)`：要么读 [EngineCapabilities]（能力位），
 * 要么问这里。这里的函数都是纯函数，没有 Compose 依赖，`EngineUiTest` 钉住每个引擎的结果——
 * 以后加第四个引擎（或改某个引擎的能力）时，测试表会逐格提醒哪些控件跟着变。
 *
 * 判断分两类，别混：
 * - **能力位能说清的**（`!` 命令、改工作目录、配置面板入口）：只看 [EngineCapabilities]，引擎无关；
 * - **能力位说不清的**（同一件事两家画法不同：模型选择、用量段、抽屉里的引擎标签、今日花费）：按 [EngineKind] 在这里选。
 */
internal object EngineUi {
    /** 输入框里打 `!` 当终端命令跑（不发给模型）。只有 CC 有这条命令 */
    fun userShell(capabilities: EngineCapabilities): Boolean = capabilities.userShell

    /**
     * `/mcp /agents /memory /config /hooks` 这几个本地接管命令打开 CC 的配置面板。
     * Min 的配置能力是「部分」（[ConfigFiles.Partial]）：面板整套是按 CC 的文件布局写的，MCP 一项不适用，
     * Min 的 MCP 在会话设置的「MCP 服务器」段里（[SettingsLayout.mcp]），不走这套文件面板，所以这里仍只在「完整」时开放入口。
     */
    fun configCommands(capabilities: EngineCapabilities): Boolean = capabilities.claudeConfigFiles == ConfigFiles.Full

    /**
     * 「今日合计」花费只属于 CC：它是 CC 账本把各会话 CLI 报的美元数加起来的（`ClaudeCodeCostLedger`），
     * 别的引擎的会话里不该冒出一个和它无关的数。Min 的花费是会话自己的 [dev.min.code.core.engine.UsageView.costLabel]
     */
    fun dailyCost(kind: EngineKind): Boolean = kind == EngineKind.ClaudeCode

    /**
     * 底栏潮环的分母（上下文窗口）取谁。Min 的窗口来自目录与供应商档，是准的，照用——以前和 CC 共用「按 CC 目录取较大值」，
     * 128k 窗口的模型被画成 200k，环永远到不了朱；CC 的 CLI 对不认识的 id 报 200k（Fable 5.1 是原生 1M），要按目录纠正
     */
    fun contextLimit(kind: EngineKind, reported: Int?, model: String?): Int = when (kind) {
        EngineKind.MinAgent -> reported?.takeIf { it > 0 } ?: ClaudeCodeModelCatalog.effectiveContextLimit(null, model)
        EngineKind.ClaudeCode, EngineKind.Codex -> ClaudeCodeModelCatalog.effectiveContextLimit(reported, model)
    }

    /** 抽屉里会话行上的引擎标签。CC 是默认引擎，不标 */
    @StringRes
    fun tag(kind: EngineKind): Int? = when (kind) {
        EngineKind.MinAgent -> R.string.engine_min
        EngineKind.Codex -> R.string.engine_codex
        EngineKind.ClaudeCode -> null
    }

    /**
     * 抽屉里这一行是不是「当前打开的那个」。CC 一行的 `isActive` 来自旧注册表（它不知道页面现在显示的是谁），
     * 所以页面显示别家会话时它不算；Min / Codex 的行没有这个字段，直接比 [shown]。
     */
    fun isEntryActive(ref: SessionRef, legacyActive: Boolean, shown: SessionRef): Boolean = when (ref.engine) {
        EngineKind.MinAgent, EngineKind.Codex -> ref == shown
        EngineKind.ClaudeCode -> legacyActive && shown.engine == EngineKind.ClaudeCode
    }

    /**
     * 排队中的消息能不能点一下取回。能力位 [EngineCapabilities.withdrawQueued] 说引擎「能撤回」，但 CC 的适配器还没接上
     * 按 id 撤回（manager 只有 ■ 时整体退回 `interruptReturningQueued`；已交棒的帧在 CLI 的 stdin 里撤不回，
     * `ClaudeCodeSession.withdrawQueued` 返回 Unsupported），给一个点了没反应的气泡比没有更糟，所以 CC 先不给。接上之后只需改这里
     */
    fun tapToWithdraw(capabilities: EngineCapabilities, kind: EngineKind): Boolean =
        capabilities.withdrawQueued && kind != EngineKind.ClaudeCode

    /**
     * 权限档那一行的标题。Codex 的三档（`CodexPermissionPreset`）适配器里只给了协议 id，要本地化；
     * 别的引擎的档位自带标签（CC 的 Manual / Accept edits 是官方术语不翻、Min 的由适配器给），返回 null 就用 [dev.min.code.core.engine.Choice.label]
     */
    @StringRes
    fun modeLabelRes(kind: EngineKind, modeId: String): Int? = when (kind) {
        EngineKind.Codex -> CodexPermissionPreset.entries.firstOrNull { it.id == modeId }?.let(::codexPresetLabel)
        EngineKind.ClaudeCode, EngineKind.MinAgent -> null
    }

    /** 权限档那一行的说明；null = 用 CC 的说明表（[ClaudeCodePermissionMode.descRes]）或不写 */
    @StringRes
    fun modeDescRes(kind: EngineKind, modeId: String): Int? = when (kind) {
        EngineKind.Codex -> CodexPermissionPreset.entries.firstOrNull { it.id == modeId }?.let(::codexPresetDesc)
        EngineKind.ClaudeCode, EngineKind.MinAgent -> null
    }

    /** 会话设置面板用哪一张 */
    fun settingsSheet(kind: EngineKind): SettingsSheetKind = when (kind) {
        EngineKind.Codex -> SettingsSheetKind.Codex
        EngineKind.ClaudeCode, EngineKind.MinAgent -> SettingsSheetKind.Generic
    }

    /** 设置面板里有哪些分区、各用哪种画法（通用那张面板用） */
    fun settingsLayout(kind: EngineKind, capabilities: EngineCapabilities, session: AgentSessionState): SettingsLayout {
        val settings = session.settings
        return SettingsLayout(
            modelPicker = when (kind) {
                EngineKind.MinAgent -> ModelPickerStyle.Providers
                EngineKind.ClaudeCode, EngineKind.Codex -> ModelPickerStyle.Cli
            },
            // CC 的强度分区永远在（它还要在里面说「这个模型不支持」「换了要重启」）；其余引擎没有档位就整段隐藏
            effort = session.settings.efforts.isNotEmpty() || !capabilities.effortHotSwap,
            effortRestartNote = settings.effortNeedsRestart,
            // 「默认」是谁决定的：CC 是它的 CLI，其余引擎没有 CLI——Min 不传强度参数，由模型自己的默认来
            effortDefaultLabel = when (kind) {
                EngineKind.ClaudeCode -> R.string.session_effort_default
                EngineKind.MinAgent, EngineKind.Codex -> R.string.session_effort_default_model
            },
            ultracode = settings.extra(ClaudeStateMapper.OPTION_ULTRACODE) != null,
            cache = settings.extra(ClaudeStateMapper.OPTION_CACHE_TTL) != null,
            cwd = capabilities.cwdChange,
            commands = session.slashCommands.isNotEmpty(),
            usage = when (kind) {
                EngineKind.MinAgent -> true
                EngineKind.ClaudeCode, EngineKind.Codex -> false
            },
            mcp = capabilities.mcpServers,
            trust = capabilities.workspaceTrust,
            checkpoints = capabilities.rewind,
            autoReview = capabilities.autoReview && settings.autoReview != null,
            advisor = capabilities.advisor && settings.advisor != null,
            schedule = capabilities.schedule && session.schedule != null,
        )
    }
}

/** 模型分区的两种画法：CLI 目录 + 中转站 + 别名（CC），或按供应商分的目录 + 手填（Min） */
internal enum class ModelPickerStyle { Cli, Providers }

/**
 * 会话设置面板用哪一张。Codex 的设置项（逐轮覆盖的模型 / 权限档 / 思考强度 / 思考摘要 / 工作目录）与 CC、Min 的
 * 形状差得远：没有模型目录、没有重启、没有 MCP / 信任 / 用量，多一个思考摘要和连接方式入口，
 * 单独一张 [CodexSettingsSheet] 比往通用那张里塞一堆 `if` 清楚；两张都只读 [dev.min.code.core.engine.AgentSessionState]、
 * 只发 [dev.min.code.core.engine.SessionCommand]，不碰引擎类型。
 */
internal enum class SettingsSheetKind { Generic, Codex }

/** [EngineUi.settingsLayout] 的结果：设置面板各分区出不出现 */
internal data class SettingsLayout(
    val modelPicker: ModelPickerStyle,
    val effort: Boolean,
    /** 强度分区顶上写「换强度要重启」 */
    val effortRestartNote: Boolean,
    /** 「跟随默认」那一行的标题资源 */
    @StringRes val effortDefaultLabel: Int,
    /** 强度分区底下的 Ultracode 开关 */
    val ultracode: Boolean,
    val cache: Boolean,
    val cwd: Boolean,
    val commands: Boolean,
    /** 「用量」分区：上下文占比 / 缓存命中率 / 花费 */
    val usage: Boolean,
    /** 「MCP 服务器」分区（[EngineCapabilities.mcpServers]） */
    val mcp: Boolean,
    /** 「工作区信任」分区（[EngineCapabilities.workspaceTrust]） */
    val trust: Boolean,
    /** 「检查点」分区：快照仓库占用与清理（[EngineCapabilities.rewind]） */
    val checkpoints: Boolean,
    /** 「自动模式的审查模型」分区（[EngineCapabilities.autoReview]） */
    val autoReview: Boolean,
    /** 「定时任务」分区：清单与删除（[EngineCapabilities.schedule]，会话里有过定时任务才出现） */
    val schedule: Boolean,
    val advisor: Boolean = false,
)

@StringRes
private fun codexPresetLabel(preset: CodexPermissionPreset): Int = when (preset) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access
}

@StringRes
private fun codexPresetDesc(preset: CodexPermissionPreset): Int = when (preset) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only_desc
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto_desc
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access_desc
}

/**
 * 权限档在界面上的名字：引擎给的档位自带标签的就用它，Codex 的三档只给了协议 id，要本地化（[EngineUi.modeLabelRes]）。
 * 输入坞底栏的摘要与设置面板的权限分区共用。
 */
@androidx.compose.runtime.Composable
internal fun modeTitle(kind: EngineKind, mode: dev.min.code.core.engine.Choice): String =
    EngineUi.modeLabelRes(kind, mode.id)?.let { androidx.compose.ui.res.stringResource(it) } ?: mode.label
