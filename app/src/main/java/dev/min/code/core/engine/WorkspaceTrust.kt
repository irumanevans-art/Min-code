package dev.min.code.core.engine

/**
 * 工作区信任（[EngineCapabilities.workspaceTrust]）：项目里带的 hooks 与 `.mcp.json` 会在手机上执行命令、连服务器，
 * 克隆来的仓库不该自己批准自己，所以引擎在第一次进一个带项目层配置的工作目录前先问人。
 * 账本在 `SettingsStore`，引擎只管「看这个目录里有什么」与读它。
 */
interface WorkspaceTrustControl {
    /**
     * 这个目录该不该弹信任卡：没问过、且里面有项目层 hooks 或 `.mcp.json` 才返回内容，已经决定过或什么都没带返回 null。
     * 读的是磁盘上的文件，所以是挂起函数
     */
    suspend fun probe(cwd: String): TrustProbe?

    /** 当前的决定：true = 信任（含从父目录继承）、false = 明确不信任、null = 没问过 */
    fun decisionOf(cwd: String): Boolean?

    /** 继承信任来自哪个祖先目录（会话设置里撤销按钮旁写一句）；自己被信任或没信任都是 null */
    fun inheritedFrom(cwd: String): String?

    /** 记下决定；[trusted] 为 null = 忘掉（下次会再问）。已经在线的会话立即重读 MCP 配置，hooks 要等下一个新会话（会话创建时冻结） */
    suspend fun decide(cwd: String, trusted: Boolean?)
}

/**
 * 信任卡要说的内容：这个目录里的项目层配置会做什么。
 * @param hookEvents 项目 settings 里配了 hook 的事件名（`PreToolUse` ……），空 = 没有 hooks
 * @param mcpServers 项目 `.mcp.json` 里的服务器名，空 = 没有
 */
data class TrustProbe(val cwd: String, val hookEvents: List<String>, val mcpServers: List<String>)
