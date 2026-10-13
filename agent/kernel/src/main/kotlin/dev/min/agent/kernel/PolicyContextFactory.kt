package dev.min.agent.kernel

import dev.min.agent.policy.PermissionRule
import dev.min.agent.policy.PolicyContext
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.RuleEntry

/**
 * 某个会话此刻的权限判定上下文：日志里的规则快照 + 本会话「总是允许」的规则、当前模式与 cwd、宿主按**解析后**路径给的分级。
 *
 * 从 [ToolExecutor] 里拆出来，因为只要判定上下文的地方（自动审查、LSP 的读权限、skill 的路径检查、委派）
 * 不必为此建一个整张工具表的 ToolExecutor。每次 [context] 都读会话的当前状态；规则列表的解析结果按实例缓存，
 * 所以反复用的地方（ToolExecutor、LSP 的回调）应当持有同一个实例，一次性用的现建现用即可
 */
internal class PolicyContextFactory(private val rt: SessionRuntime) {
    private val deps = rt.deps
    private var parsedFromRules: List<RuleEntry>? = null
    private var parsedFromSession: List<RuleEntry>? = null
    private var parsedRules: List<PermissionRule> = emptyList()

    /**
     * 规则列表只在快照或会话规则变了时重新解析：折叠只在变化时换掉这两个列表，所以比引用就够；
     * 解析不了的规则跳过（读取时已记过说明）
     */
    fun context(): PolicyContext {
        val s = rt.state.value
        if (s.rules !== parsedFromRules || s.sessionRules !== parsedFromSession) {
            parsedRules = (s.rules + s.sessionRules).mapNotNull(PermissionRule::from)
            parsedFromRules = s.rules
            parsedFromSession = s.sessionRules
        }
        return PolicyContext(
            mode = s.settings.mode,
            classifyAllShell = s.autoReviewSettings.config.classifyAllShell,
            // 本轮 skill 的 allowed-tools 放行（照文档：到用户下一条消息为止）；拒绝与询问规则仍在它之上（PolicyEngine 先看拒绝、再看询问）
            rules = parsedRules + s.skillGrants.mapNotNull { PermissionRule.parse(dev.min.agent.policy.RuleKind.Allow, it, "skill") },
            cwd = s.settings.cwd,
            // 命令里的相对路径按 shell 当前目录解析（规则与工作区范围仍按 cwd）
            shellCwd = s.shellCwd,
            home = deps.memoryHome ?: DEFAULT_HOME,
            scopeOf = { path ->
                val actual = deps.canonicalPath(path)
                val hostScope = deps.host.fs.scopeOf(actual)
                when {
                    hostScope == dev.min.agent.spi.PathScope.Device -> hostScope
                    Worktrees.under(actual, deps.canonicalPath(s.settings.cwd)) -> dev.min.agent.spi.PathScope.Workspace
                    hostScope == dev.min.agent.spi.PathScope.Workspace -> dev.min.agent.spi.PathScope.Rootfs
                    else -> hostScope
                }
            },
            // 自动记忆目录：acceptEdits 下对它的写入与工作区同等放行（设计 §2.4）
            autoMemoryDir = s.autoMemory?.directory,
            additionalDirectories = s.additionalDirectories,
            credentialPaths = deps.credentialPaths,
            canonicalPath = deps.canonicalPath,
            worktreeRoot = s.worktree?.path,
            autoHardDeny = s.autoReviewSettings.config.hardDeny,
            // --restricted：内置文件工具限制在工作目录内（PolicyEngine 里排在允许规则之前）
            confineToWorkingDirectories = deps.settingsInputs.restricted,
            guestIsolation = deps.host.exec.guestIsolation,
            // 会话的 CLAUDE_ENV_FILE 目录：里面的脚本会在后续每条 Bash 命令前执行，模型不能自己写它（安全底线，见 SessionEnvFile.kt）
            shellEnvDir = rt.envFiles.dir(),
        )
    }

    private companion object {
        /** 宿主没给主目录时（内核测试）规则里 `~/` 相对的目录 */
        val DEFAULT_HOME = GuestPath("/root")
    }
}
