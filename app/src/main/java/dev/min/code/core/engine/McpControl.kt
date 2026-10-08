package dev.min.code.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * 会话里的 MCP 服务器面板与批准卡（[EngineCapabilities.mcpServers]）。只有 Min 有：CC 的 MCP 由它自己的 CLI 管，
 * 走配置面板（`/mcp`）；Min 的连接活在 App 进程里，状态、重连、登录都得由 App 自己给界面。
 *
 * 没有流：面板与批准卡都按「现在」轮询 [servers]（内核的 `statusOf` 是实时读表，会话日志里的快照只在回合开头更新，
 * 拿它来画面板，服务器掉线了面板还写「已连接」）。
 */
interface McpControl {
    /** 此刻这个会话看到的每台服务器（配置里的全部，含没放行的）。轮询用，便宜 */
    suspend fun servers(): List<McpServerView>

    /**
     * 项目 `.mcp.json` 里一台服务器「怎么跑」：批准卡要让人看清自己在批准什么（命令行或 URL，原样不展开变量）。
     * 读不到（文件没了、不是项目服务器）返回 null
     */
    suspend fun describe(server: String): McpServerCommandLine?

    /** 批准 / 拒绝一台项目服务器：写进 `.claude/settings.local.json`（与 CC 互通），并让本会话重读配置——下一回合生效 */
    suspend fun approve(server: String, allow: Boolean): Result<Unit>

    /** 丢掉旧连接从头再连一次（失败、掉线、卡住都可以点） */
    suspend fun reconnect(server: String): Result<Unit>

    /** 只对本会话开 / 关一台服务器（不改配置文件）。关掉的服务器工具下一回合起不在工具表里 */
    suspend fun setEnabled(server: String, enabled: Boolean): Result<Unit>

    /**
     * 登录一台远程服务器（OAuth）。会先经 [loginPrompt] 让人看到授权域名与 scope、点「继续」才打开浏览器；
     * 一直挂起到登录结束（用户在浏览器里走完流程，最长五分钟）。用户在确认卡上点了取消 = [Result.failure]（[McpLoginCancelled]）
     */
    suspend fun login(server: String): Result<Unit>

    /** 退出登录：只清本机令牌、关掉相关连接（不向服务器撤销令牌） */
    suspend fun logout(server: String): Result<Unit>

    /** 正在等人确认的登录；null = 没有。界面显示成一张卡，点哪个按钮就调 [answerLoginPrompt] */
    val loginPrompt: StateFlow<McpLoginPrompt?>

    fun answerLoginPrompt(proceed: Boolean)
}

/** 一台服务器此刻的样子 */
data class McpServerView(
    val name: String,
    /** managed / local / project / user */
    val scope: String,
    /** stdio / http / sse / ws */
    val transport: String,
    val state: McpServerState,
    /** 不是已连接时给人看的一句话（内核的说明，可能含中文） */
    val reason: String?,
    val toolCount: Int,
    /** stdio 服务器 stderr 的尾巴（最多几十行）；没有 / 远程服务器为空 */
    val stderrTail: List<String>,
    /** 远程服务器：本机有没有它的访问令牌。null = 不适用（stdio）或读不出来 */
    val signedIn: Boolean?,
    /** 本会话在面板里把它关了（[McpControl.setEnabled]）；此时 [state] 是 [McpServerState.Disabled] */
    val sessionOff: Boolean = false,
) {
    /** 项目服务器等批准：批准卡的触发条件 */
    val needsApproval: Boolean get() = state == McpServerState.NotApproved

    /**
     * 面板上出不出「本会话」开关：只有配置里放行了的服务器有意义（待批准、未信任、被策略拒绝、配置里关着的，开了也连不上）；
     * 本会话关掉的那台当然要给，不然关了就再也开不回来
     */
    val sessionToggle: Boolean
        get() = sessionOff || state in setOf(McpServerState.Pending, McpServerState.Connected, McpServerState.Failed, McpServerState.NeedsAuth)
}

enum class McpServerState {
    Pending, Connected, Failed, NeedsAuth,

    /** 配置里关了、`disabledMcpjsonServers` 里拒绝了，或者本会话在面板里关了 */
    Disabled,

    /** 项目服务器，已信任但还没批准 */
    NotApproved,

    /** 项目服务器，工作区还没被信任（信任之前一个都不连） */
    Untrusted,

    /** 被管控策略（`deniedMcpServers` 等）拒绝 */
    Denied,
    ;

    companion object {
        /** 内核的状态串（`McpServerInfo.state`）；不认识的当失败，面板上至少看得到「出了问题」 */
        fun of(wire: String): McpServerState = when (wire) {
            "pending" -> Pending
            "connected" -> Connected
            "failed" -> Failed
            "needs-auth" -> NeedsAuth
            "disabled" -> Disabled
            "not-approved" -> NotApproved
            "untrusted" -> Untrusted
            "denied" -> Denied
            else -> Failed
        }
    }
}

/** 批准卡上的「怎么跑」：[command] 是整行命令（含参数），[url] 是远程地址；二选一 */
data class McpServerCommandLine(val command: String?, val url: String?)

/** 登录确认卡：浏览器打开前让人看到「去哪个域名授权、要什么权限」 */
data class McpLoginPrompt(val server: String, val issuer: String, val domain: String, val scopes: String?)

/** 用户在确认卡上点了取消 */
class McpLoginCancelled : Exception("已取消登录。")
