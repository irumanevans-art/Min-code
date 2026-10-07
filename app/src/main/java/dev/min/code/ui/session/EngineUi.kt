package dev.min.code.ui.session

import androidx.annotation.StringRes
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.ConfigFiles
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.claude.ClaudeStateMapper

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

    /** 抽屉里会话行上的引擎标签。CC 是默认引擎，不标；Codex 在自己的页里，不在这个列表里 */
    @StringRes
    fun tag(kind: EngineKind): Int? = when (kind) {
        EngineKind.MinAgent -> R.string.engine_min
        EngineKind.ClaudeCode, EngineKind.Codex -> null
    }

    /**
     * 抽屉里这一行是不是「当前打开的那个」。CC 一行的 `isActive` 来自旧注册表（它不知道页面现在显示的是谁），
     * 所以页面显示别家会话时它不算；Min 一行没有这个字段，直接比 [shown]。
     */
    fun isEntryActive(ref: SessionRef, legacyActive: Boolean, shown: SessionRef): Boolean = when (ref.engine) {
        EngineKind.MinAgent -> ref == shown
        EngineKind.ClaudeCode -> legacyActive && shown.engine == EngineKind.ClaudeCode
        EngineKind.Codex -> false
    }

    /** 设置面板里有哪些分区、各用哪种画法 */
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
        )
    }
}

/** 模型分区的两种画法：CLI 目录 + 中转站 + 别名（CC），或按供应商分的目录 + 手填（Min） */
internal enum class ModelPickerStyle { Cli, Providers }

/** [EngineUi.settingsLayout] 的结果：设置面板各分区出不出现 */
internal data class SettingsLayout(
    val modelPicker: ModelPickerStyle,
    val effort: Boolean,
    /** 强度分区顶上写「换强度要重启」 */
    val effortRestartNote: Boolean,
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
)
