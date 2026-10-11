package dev.min.code.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * 一个会话引擎（Claude Code / Codex / Min Agent）。注册表只认这个接口，三个适配器各实现一份：
 * `core/engine/claude/ClaudeCodeEngine`、`codex/CodexEngine`、`min/MinAgentEngine`。
 */
interface AgentEngine {
    val kind: EngineKind
    val capabilities: EngineCapabilities

    /** 能不能新建会话：环境没装好 / 没连供应商时界面把这个引擎置灰，下面一行写原因和去处 */
    val availability: StateFlow<EngineAvailability>
    val catalog: SessionCatalog

    /** 开始面板上这个引擎专有的选项；没有的引擎是 null（默认）。见 [StartChoices] */
    val startChoices: StateFlow<StartChoices?> get() = NoStartChoices

    /** 工作区信任（[EngineCapabilities.workspaceTrust] 为真才有）；其余引擎 null */
    val workspaceTrust: WorkspaceTrustControl? get() = null

    /**
     * 引擎自己认定「在线」的会话，含没在界面上打开的（Min：后台子 agent 跑完唤醒的父会话）。
     * [LiveSessionSummary.isActive] / [LiveSessionSummary.attached] 引擎不用填，注册表合并时填。
     */
    val live: StateFlow<List<LiveSessionSummary>>

    /** 失败（环境没就绪、起不来）用 [Result.failure] 返回，不抛；协程取消照常抛 CancellationException */
    suspend fun create(options: StartOptions): Result<AgentSession>

    /**
     * 打开一个已有会话。会话已经在线就返回在线的那个，不要重开。
     * 不自动续跑：Min 的 `hub.open` 不开回合，CC 的 `--resume` 不带续跑开关（§6.4）。
     */
    suspend fun open(id: String): Result<AgentSession>

    suspend fun closeAll(reason: CloseReason)

    /**
     * 给一个会话改名，在线与否都行（列表里长按改名的会话常常没在线）。Min 写进内核日志（`session_renamed`），
     * 与 CLI 的 `/rename`、按名续接同一个名字；别的引擎有自己的改名路径，默认不支持。名字规整后为空返回 false
     */
    suspend fun renameSession(id: String, title: String): Boolean = false
}

sealed interface EngineAvailability {
    data object Ready : EngineAvailability

    /** 要先装运行环境（CC 的 CLI / Node，Codex 的安装） */
    data object NeedsInstall : EngineAvailability

    /** 要先连一个供应商（Min 的 API key 档；CC 的订阅登录或 API 档） */
    data object NeedsProvider : EngineAvailability

    /** 实验开关关着、系统版本不够等。[reason] 是给人看的原因 */
    data class Disabled(val reason: String) : EngineAvailability

    val isReady: Boolean get() = this is Ready
}

/**
 * 新建会话的参数。[profileId] / [modelId] / [modeId] / [effortId] 为 null 表示用引擎或最近一次用的默认
 * （Min：最近一次用的 API key 档，会话绑定自己的档，之后切全局供应商不影响它，§10.2 拍板 3）。
 * [engineOptions] 是引擎专有的开局设置，键同 [EngineOptionView.key]。
 */
data class StartOptions(
    val cwd: String,
    val profileId: String? = null,
    val modelId: String? = null,
    val modeId: String? = null,
    val effortId: String? = null,
    val engineOptions: Map<String, String> = emptyMap(),
    val loadingModes: dev.min.agent.spi.SessionLoadingModes? = null,
)
