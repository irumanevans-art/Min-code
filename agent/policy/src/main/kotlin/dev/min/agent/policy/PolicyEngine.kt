package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk

/** 一次工具调用的权限判定 */
sealed interface Verdict {
    data class Allow(val by: DecidedBy) : Verdict

    /** [messageToModel] 作为工具结果交给模型，让它换条路而不是原样重试 */
    data class Deny(val by: DecidedBy, val messageToModel: String) : Verdict

    /** 交给人（卡片 / 通知） */
    data object Ask : Verdict
}

/**
 * 判定所需的上下文。[scopeOf] 由宿主给（宿主侧文件系统按**解析后**的路径分级，工作区里指向 /sdcard 的链接也算设备）；
 * [rules] 是各层设置与本会话「总是允许」合起来的规则，顺序无关（求值按拒绝 → 询问 → 允许）；
 * [autoMemoryDir] 是本项目的自动记忆目录（设计 §2.4），acceptEdits 下对它的写入与工作区同等放行。
 */
data class PolicyContext(
    val mode: PermissionMode,
    val rules: List<PermissionRule> = emptyList(),
    val cwd: GuestPath = GuestPath("/workspace"),
    val home: GuestPath = GuestPath("/root"),
    val scopeOf: (GuestPath) -> PathScope = ::defaultScope,
    val autoMemoryDir: GuestPath? = null,
    val additionalDirectories: List<GuestPath> = emptyList(),
    val credentialPaths: Set<GuestPath> = emptySet(),
    val canonicalPath: (GuestPath) -> GuestPath = { it },
    val worktreeRoot: GuestPath? = null,
    val autoHardDeny: List<String> = emptyList(),
    val classifyAllShell: Boolean = false,
    /**
     * CC `--restricted`（cli-reference :123）：内置文件工具只能碰工作目录（cwd 与 additionalDirectories，即 [PathScope.Workspace]）。
     * 为真时，这次调用碰到任何工作目录外的路径都直接拒绝：它排在允许规则与模式之前，所以允许规则、acceptEdits、
     * bypass 都越不过去（bypass 在受限会话里本来也不许开，见 KernelDeps.settingsInputs）
     */
    val confineToWorkingDirectories: Boolean = false,
    val guestIsolation: dev.min.agent.spi.GuestIsolation? = null,
    /**
     * Bash 的 `cd` 带过来的 shell 当前目录（会话目录之内）。只有分析 Bash 命令里的相对路径时用它做起点——
     * 命令真正是从这里开始跑的；规则里的相对写法与工作区范围仍以 [cwd] 为准（`cd` 进子目录不改变权限范围）。
     * null = shell 就在 [cwd]
     */
    val shellCwd: GuestPath? = null,
)

/** 宿主没给分级函数时的兜底，与 app 的 core/session/PermissionScope.kt 同一套前缀 */
fun defaultScope(path: GuestPath): PathScope {
    val p = path.value.trimEnd('/')
    return when {
        p == "/workspace" || p.startsWith("/workspace/") -> PathScope.Workspace
        p == "/sdcard" || p.startsWith("/sdcard/") || p == "/storage" || p.startsWith("/storage/") -> PathScope.Device
        else -> PathScope.Rootfs
    }
}

/**
 * 权限判定。纯函数：同样的请求 + 上下文 → 同样的结论。hooks 与审查模型由内核在它之外编排（docs/agent/04-子系统设计.md §4.4）。
 *
 * 顺序：
 * 1. **拒绝规则**：任一命中即拒（跨层级拒绝优先，与 CC 一致）。排在底线前面：底线的结论是「问人」，
 *    人点一下就能过；明确写下的 deny（可能来自托管设置）不能因为同时踩了底线就变成可批准。
 * 2. **安全底线**（任何模式都问人，含 bypass）：写手机文件；写受保护路径；递归删关键路径、抹磁盘、强推这类无法挽回的命令；
 *    看不透的命令里出现危险词。
 * 3. **询问规则**：任一命中即问。
 * 4. **允许规则**：这次调用碰到的每一样东西（每条简单命令、每个路径、每个域名）都被允许规则或只读命令覆盖，才放行。
 * 5. **模式**：bypass 放行；计划模式非只读询问；dontAsk 拒绝需批的调用；acceptEdits 放行工作区内的编辑和文件命令；默认只放行只读。
 *    Bash 由命令分析决定是否只读：`ls`、`git status` 这类在默认模式下免批（与 CC 一致）。
 */
object PolicyEngine {

    /** 只按模式判定（没有规则、按前缀分级）。内核接上规则与宿主分级之前沿用它 */
    fun evaluate(ask: PermissionAsk, readOnly: Boolean, mode: PermissionMode): Verdict =
        evaluate(ask, readOnly, PolicyContext(mode))

    fun evaluate(ask: PermissionAsk, readOnly: Boolean, context: PolicyContext): Verdict {
        val facts = Facts.of(ask, context)
        val matcher = RuleMatcher(context.cwd, context.home)

        if (context.mode == PermissionMode.Auto) {
            val rules = context.autoHardDeny.mapNotNull { PermissionRule.parse(RuleKind.Deny, it, "autoMode.hard_deny") }
            firstHit(facts, rules, matcher)?.let { rule ->
                return Verdict.Deny(DecidedBy.Rule(rule.raw, rule.source), "autoMode.hard_deny 禁止这个操作：${rule.raw}")
            }
        }

        // 宿主凭证不能经工具进入模型或事件；这个拒绝不能降级为可由用户或 hook 批准的询问。
        if (facts.credentialTargets.any { credentialPath(it, context, includeAncestors = runsShellCommand(facts.tool)) }) {
            return Verdict.Deny(DecidedBy.Mode(context.mode), "这是 Min 保存的凭证文件，工具不能读取、搜索或修改它。")
        }

        // 宿主工具与 proot 共用同一份可写根；允许规则和 bypass 不能授予隔离范围外的写入。
        context.guestIsolation?.let { policy ->
            facts.paths.firstOrNull { it.kind == AccessKind.Write && !GuestIsolationPolicy.canWrite(policy, context.canonicalPath(it.path)) }?.let { outside ->
                return Verdict.Deny(DecidedBy.Mode(context.mode), "写入范围仅含工作区、临时与缓存目录：${outside.path.value} 在范围之外。")
            }
        }

        // 受限会话的工作目录线（CC :123）：在规则之前拒绝，不能被允许规则或模式放行，也不降级为询问——人点一下就能过的，不算限制
        if (context.confineToWorkingDirectories) {
            facts.paths.firstOrNull { it.scope != PathScope.Workspace }?.let { outside ->
                return Verdict.Deny(
                    DecidedBy.Mode(context.mode),
                    "受限模式（--restricted）：只能读写工作目录（cwd 与 additionalDirectories）里的文件，${outside.path.value} 不在里面。" +
                        "不要原样重试，换工作目录内的做法。",
                )
            }
        }

        val deny = context.rules.filter { it.kind == RuleKind.Deny }
        firstHit(facts, deny, matcher)?.let { rule ->
            return Verdict.Deny(
                DecidedBy.Rule(rule.raw, rule.source),
                "这个操作被权限规则「${rule.raw}」（${rule.source}）禁止。不要原样重试，换个做法或请用户调整规则。",
            )
        }

        if (floorReason(facts) != null) return Verdict.Ask

        if (firstHit(facts, context.rules.filter { it.kind == RuleKind.Ask }, matcher) != null) return Verdict.Ask

        covering(facts, context.rules.filter { it.kind == RuleKind.Allow &&
            (context.mode != PermissionMode.Auto || !suspendedInAuto(it, context.classifyAllShell)) }, matcher)?.let { rule ->
            return Verdict.Allow(DecidedBy.Rule(rule.raw, rule.source))
        }

        // classifyAllShell 连只读 shell 也送审；其他工具的只读快路仍按原契约。
        if (context.mode == PermissionMode.Auto && context.classifyAllShell && facts.tool in setOf("Bash", "PowerShell", "Monitor")) return Verdict.Ask
        val effectivelyReadOnly = (readOnly || facts.isReadOnlyCommand) && !facts.requiresReadApproval
        val byMode = DecidedBy.Mode(context.mode)
        return when (context.mode) {
            PermissionMode.BypassPermissions -> Verdict.Allow(byMode)
            PermissionMode.Plan ->
                if (effectivelyReadOnly) Verdict.Allow(byMode)
                else Verdict.Ask
            PermissionMode.DontAsk ->
                if (effectivelyReadOnly) Verdict.Allow(byMode)
                else Verdict.Deny(byMode, "当前模式不询问用户，这个操作没有被预先允许。")
            PermissionMode.AcceptEdits ->
                if (effectivelyReadOnly || facts.isWorkspaceEditOnly || facts.isWorkspaceFileCommand) Verdict.Allow(byMode) else Verdict.Ask
            // 需要审批的 auto 调用由内核先审查；纯权限引擎不发网络请求。
            PermissionMode.Auto ->
                if (effectivelyReadOnly || facts.isWorkspaceEditOnly) Verdict.Allow(byMode) else Verdict.Ask
            PermissionMode.Default ->
                if (effectivelyReadOnly) Verdict.Allow(byMode) else Verdict.Ask
        }
    }

    /**
     * 这个工具的入参是一条 shell 命令，判定按 Bash 的规矩来（Monitor 的命令源：CC 说它「用与 Bash 同一套权限规则」）。
     * 规则匹配本来就只看命令、不看工具名；凭证目录的祖先判定、Read 拒绝挡写这类按工具名分支的地方用它，别各写一份 `== "Bash"`
     */
    fun runsShellCommand(tool: String): Boolean = tool == "Bash" || tool == "Monitor"

    /** 任意代码执行授权在 Auto 里不能越过分类器；窄命令规则仍保留。 */
    fun suspendedInAuto(rule: PermissionRule, classifyAllShell: Boolean = false): Boolean {
        if (rule.tool in setOf("Agent", "Task", "Monitor")) return true
        if (rule.tool !in setOf("Bash", "PowerShell")) return false
        if (classifyAllShell) return true
        val spec = rule.specifier ?: return true
        if (spec.trim() in setOf("*", ":*")) return true
        val executable = ShellAnalyzer.autoRuleExecutable(spec.trim().let { if (it.endsWith(":*")) it.removeSuffix(":*") + " *" else it }) ?: return true
        val interpreters = setOf(
            "python", "python2", "python3", "pypy", "pypy3", "node", "nodejs", "ruby", "perl", "php", "lua",
            "sh", "bash", "zsh", "dash", "fish", "ksh", "pwsh", "powershell", "cmd", "deno", "bun", "eval", "exec", "env", "xargs",
            "npx", "pnpx", "bunx", "uvx", "sudo", "su", "ssh",
            "osascript", "rscript", "tclsh", "awk", "gawk",
        )
        // 版本号和可执行文件通配仍能放行解释器代码，不能因拼写不是裸名就绕过分类器。
        val literal = executable.takeWhile { it != '*' && it != '?' }.removeSuffix(".exe")
        return ('*' in spec || '?' in spec) && interpreters.any { name ->
            name.startsWith(literal) || literal == name || literal.startsWith("$name.") ||
                literal.startsWith(name) && literal.removePrefix(name).firstOrNull()?.isDigit() == true
        }
    }

    /**
     * 没带括号的 deny 规则整个禁掉了 [tool]（`mcp__github`、`mcp__github__*`、`mcp__*`……）：这样的工具不进工具表，
     * 模型根本看不到它（与 CC 一致：「deny 的裸名 / 通配会把工具从模型上下文移除」）。现在只用于 MCP 工具
     */
    fun deniesWholeTool(tool: String, context: PolicyContext): Boolean {
        val matcher = RuleMatcher(context.cwd, context.home)
        return context.rules.any { it.kind == RuleKind.Deny && it.specifier == null && matcher.matchesTool(it, tool) }
    }

    /**
     * hooks 的 `if` 字段：一条权限规则写法的条件，对这次调用成不成立（docs/agent/detail/hooks设计.md §4）。
     * 与 Claude Code 一致：Bash 逐个子命令判断，任何一个子命令命中就算；**看不透的命令一律算成立**——
     * 判断不了就跑 hook，hook 是门禁时宁可多跑一次。写法认不出时不成立（调用方记一条说明）。
     */
    fun matchesCondition(rule: String, ask: PermissionAsk, context: PolicyContext): Boolean {
        // hook 条件不授予权限，不能套用 allow 对 MCP 服务器通配的限制。
        val parsed = PermissionRule.parse(RuleKind.Ask, rule, "hook-if") ?: return false
        val facts = Facts.of(ask, context)
        if (parsed.tool == "Bash" && parsed.tool == facts.tool && facts.untrusted) return true
        return firstHit(facts, listOf(parsed), RuleMatcher(context.cwd, context.home)) != null
    }

    /**
     * 这次调用命中了哪条 `ask` 规则，没命中为 null。内核合并 hook 的结论时用它：CC 文档
     * 「Deny and ask rules are still evaluated regardless of what the hook returns」，所以 hook 的 allow 越不过 ask 规则。
     * 只回答「ask 规则命中没有」，不管底线与 deny 规则（那两样由 [evaluate] 先行处理）。
     */
    fun askRuleHit(ask: PermissionAsk, context: PolicyContext): PermissionRule? =
        firstHit(Facts.of(ask, context), context.rules.filter { it.kind == RuleKind.Ask }, RuleMatcher(context.cwd, context.home))

    /**
     * 踩了安全底线的原因（界面据此解释「为什么连 bypass 也要问你」），没踩为 null。
     */
    fun floorReason(ask: PermissionAsk, context: PolicyContext): String? = floorReason(Facts.of(ask, context))

    private fun protectedPath(path: String, worktreeRoot: String?): Boolean {
        // 已验证的隔离根只是容器路径；仍检查容器内部的 .git、.claude 与受保护文件名。
        val relative = if (worktreeRoot != null && path.startsWith("$worktreeRoot/")) path.removePrefix(worktreeRoot) else path
        return CommandSafety.isProtectedPath(relative)
    }

    private fun floorReason(facts: Facts): String? {
        facts.paths.firstOrNull { it.kind == AccessKind.Write && it.scope == PathScope.Device }
            ?.let { return "要写手机上的真实文件 ${it.path}" }
        facts.paths.firstOrNull { it.kind == AccessKind.Write && protectedPath(it.path.value, facts.worktreeRoot) }
            ?.let { return "要改受保护的路径 ${it.path}（版本库元数据、agent 配置或 shell 启动脚本）" }
        facts.unresolvedWrites.firstOrNull { CommandSafety.isProtectedPath("/$it") }
            ?.let { return "要改受保护的文件 $it（版本库元数据、agent 配置或 shell 启动脚本）" }
        facts.commands.firstNotNullOfOrNull { CommandSafety.dangerReason(it, facts.commands) }?.let { return it }
        facts.untrustedRaw.firstNotNullOfOrNull { CommandSafety.untrustedDanger(it) }?.let { return it }
        return null
    }

    /** 拒绝 / 询问规则：这次调用碰到的任何一样东西命中即算 */
    private fun firstHit(facts: Facts, rules: List<PermissionRule>, m: RuleMatcher): PermissionRule? =
        rules.firstOrNull { rule ->
            !m.isNegation(rule) && (
                m.matchesTool(rule, facts.tool) ||
                    facts.commands.any { m.matchesCommand(rule, it) } ||
                    // 看不透的命令也拿原文对一遍拒绝规则：宁可多拦
                    (rule.tool == "Bash" && facts.untrustedRaw.any { raw -> rawMatches(rule, raw) }) ||
                    facts.paths.any { p -> m.matchesPath(rule, p, facts.tool) && !exempted(p, rule, rules, m, facts.tool) } ||
                    facts.domains.any { m.matchesDomain(rule, it) } ||
                    facts.specifiers.any { m.matchesSpecifier(rule, facts.tool, it) }
                )
        }

    /** `!` 规则：同一来源里的否定规则把路径从更早的拒绝 / 询问里豁免出来（CC 的 `Read(!sample.env)`） */
    private fun exempted(path: PathAccess, rule: PermissionRule, rules: List<PermissionRule>, m: RuleMatcher, tool: String): Boolean =
        rules.any { it.source == rule.source && m.isNegation(it) && m.matchesPath(it, path, tool) }

    /** 看不透的命令：规则里通配符之前的那段字面量，在原文里作为独立的词出现就算命中 */
    private fun rawMatches(rule: PermissionRule, raw: String): Boolean {
        val literal = rule.specifier?.removeSuffix(":*")?.substringBefore('*')?.trim() ?: return false
        if (literal.isEmpty()) return false
        val words = literal.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("""(?<![\w./-])$words(?![\w-])""").containsMatchIn(raw)
    }

    /**
     * 允许规则要**覆盖全部**：每条简单命令（规则命中或本身只读）、每个路径、每个域名。只要有一样没覆盖就不算。
     * 整工具规则（如 `WebFetch`、`mcp__github`）直接覆盖。返回起决定作用的那条规则。
     */
    private fun covering(facts: Facts, rules: List<PermissionRule>, m: RuleMatcher): PermissionRule? {
        if (facts.untrusted || facts.unresolvedWrites.isNotEmpty()) return null
        val whole = rules.firstOrNull { m.matchesTool(it, facts.tool) }
        if (whole != null && !facts.hasOutsideReadRedirect) return whole
        if (facts.commands.isEmpty() && facts.paths.isEmpty() && facts.domains.isEmpty() && facts.specifiers.isEmpty()) return null
        var decisive: PermissionRule? = whole
        val allowedCommands = mutableSetOf<SimpleCommand>()
        for (cmd in facts.commands) {
            val rule = rules.firstOrNull { m.matchesCommand(it, cmd) }
            if (rule == null && whole == null && !facts.commandIsReadOnly(cmd)) return null
            decisive = decisive ?: rule
            if (rule != null || whole != null) allowedCommands += cmd
        }
        for (path in facts.paths) {
            val rule = rules.firstOrNull { !m.isNegation(it) && m.matchesPath(it, path, facts.tool) }
            // 命令的重定向写进工作区：规则已允许这条命令，就不再单独要求 Edit 规则
            val owners = facts.filePathOwners.filter { it.first == path }.map { it.second }
            val approvedOwners = owners.isNotEmpty() && owners.all { it in allowedCommands }
            val okByCommand = path in facts.redirectWrites && path.scope == PathScope.Workspace && approvedOwners
            val okRead = path.kind == AccessKind.Read && path.scope != PathScope.Device &&
                (path !in facts.redirectReads || path.scope == PathScope.Workspace)
            // 命令批准只覆盖自己的操作数，不能让前面的 npm allow 替后面的 cat 手机文件作批准。
            val okFileCommand = path !in facts.redirectReads && path !in facts.redirectWrites && approvedOwners
            if (rule == null && !okByCommand && !okRead && !okFileCommand) return null
            decisive = decisive ?: rule
        }
        for (domain in facts.domains) {
            val rule = rules.firstOrNull { m.matchesDomain(it, domain) } ?: return null
            decisive = decisive ?: rule
        }
        for (specifier in facts.specifiers) {
            val rule = rules.firstOrNull { m.matchesSpecifier(it, facts.tool, specifier) } ?: return null
            decisive = decisive ?: rule
        }
        return decisive
    }

    /**
     * 给「总是允许」的建议规则：尽量窄。命令取「程序 + 子命令」再加 ` *`（`git checkout *`），解释器只给精确命令
     * （`python *` 等于放行任意代码）；写文件取所在目录；网页取域名。复合命令、看不透的命令不给建议（每段都放宽太危险）。
     * 踩了底线的请求也不给：底线任何规则都绕不过，给了「总是允许」也没用，只会让人以为下次不用问。
     */
    fun suggestRule(ask: PermissionAsk, context: PolicyContext): String? {
        if (Facts.of(ask, context).credentialTargets.any { credentialPath(it, context, includeAncestors = runsShellCommand(ask.tool)) }) return null
        if (floorReason(ask, context) != null) return null
        // Monitor 的 WebSocket 源：CC 的弹窗不提供「以后不再问这个主机」，整个 Monitor 放行又太宽
        if (ask.tool == "Monitor" && ask.commands.isEmpty()) return null
        if (ask.commands.isNotEmpty()) {
            if (ask.commands.size != 1) return null
            val parsed = ShellAnalyzer.analyze(ask.commands.single().raw) as? ShellAnalysis.Parsed ?: return null
            val cmd = parsed.commands.singleOrNull() ?: return null
            if (cmd.program in EXACT_ONLY || cmd.hasExpansion) return "Bash(${cmd.text})"
            val second = cmd.argv.getOrNull(1)
            val withSub = second != null && !second.startsWith("-") && '/' !in second && '.' !in second && cmd.program in SUBCOMMAND_TOOLS
            return if (withSub) "Bash(${cmd.argv[0]} $second *)" else "Bash(${cmd.argv[0]} *)"
        }
        ask.paths.firstOrNull { it.kind == AccessKind.Write }?.let { write ->
            val dir = write.path.value.substringBeforeLast('/')
            // 根目录下的文件只给它自己，不给 `//**`（等于放行全部）
            return if (dir.isEmpty()) "Edit(/${write.path.value})" else "Edit(/$dir/**)"
        }
        ask.domains.firstOrNull()?.let { return "WebFetch(domain:$it)" }
        return ask.tool
    }

    /** 前缀放宽就等于放行任意代码的程序：只给精确命令 */
    private val EXACT_ONLY = setOf(
        "python", "python3", "node", "bash", "sh", "zsh", "dash", "ruby", "perl", "php", "deno", "bun",
        "npx", "pnpx", "bunx", "uvx", "sudo", "su", "xargs", "find", "ssh",
    )
    private val SUBCOMMAND_TOOLS = setOf(
        "git", "npm", "pnpm", "yarn", "cargo", "go", "gradle", "gradlew", "docker", "kubectl",
        "pip", "pip3", "uv", "apt", "apt-get", "make", "mvn", "dotnet", "flutter",
    )

    /**
     * 这次调用实际碰到的东西：工具、拆开后的简单命令、全部路径（含命令重定向写的文件）、域名。
     * [memoryDir] 是归一化后的自动记忆目录（没有为 null）。
     */
    private class Facts(
        val tool: String,
        val commands: List<SimpleCommand>,
        val rawCommands: List<String>,
        /** 分析器看不透的那几条命令的原文 */
        val untrustedRaw: List<String>,
        val redirectWrites: List<PathAccess>,
        val redirectReads: List<PathAccess>,
        val filePaths: List<PathAccess>,
        val filePathOwners: List<Pair<PathAccess, SimpleCommand>>,
        val fileCommandsCovered: Boolean,
        val gitAfterCd: Set<SimpleCommand>,
        /** 前面 cd 到了静态不知道的地方之后的相对重定向目标：写到哪里不知道 */
        val unresolvedWrites: List<String>,
        val paths: List<PathAccess>,
        val credentialTargets: List<GuestPath>,
        val domains: List<String>,
        val specifiers: List<String>,
        val memoryDir: String?,
        val worktreeRoot: String?,
    ) {
        val untrusted: Boolean get() = untrustedRaw.isNotEmpty()

        val hasOutsideReadRedirect: Boolean get() = redirectReads.any { it.scope != PathScope.Workspace }
        val requiresReadApproval: Boolean get() = hasOutsideReadRedirect || unresolvedWrites.isNotEmpty() ||
            paths.any { it.kind == AccessKind.Read && it.scope == PathScope.Device }

        fun commandIsReadOnly(cmd: SimpleCommand): Boolean = cmd !in gitAfterCd && CommandSafety.isReadOnly(cmd)

        /** Bash 调用且每条简单命令都只读、不往文件里写 */
        val isReadOnlyCommand: Boolean
            get() = rawCommands.isNotEmpty() && !untrusted && redirectWrites.isEmpty() && unresolvedWrites.isEmpty() &&
                commands.all(::commandIsReadOnly)

        val isWorkspaceFileCommand: Boolean
            get() = !untrusted && !requiresReadApproval && unresolvedWrites.isEmpty() && fileCommandsCovered &&
                filePaths.isNotEmpty() && domains.isEmpty() && paths.filter { it.kind == AccessKind.Write }.all { it.scope == PathScope.Workspace }

        /** 只改工作区里的文件（自动记忆目录算工作区：那是 agent 自己的笔记，设计 §2.4）、不跑命令、不联网：acceptEdits 下直接放行的那一类 */
        val isWorkspaceEditOnly: Boolean
            get() = rawCommands.isEmpty() && domains.isEmpty() && paths.isNotEmpty() && paths.all {
                it.scope == PathScope.Workspace || it in memoryDirWrites
            }

        /** 这次调用里写到自动记忆目录的路径（目录本身与它之下） */
        private val memoryDirWrites: List<PathAccess>
            get() {
                val dir = memoryDir ?: return emptyList()
                return paths.filter { p ->
                    p.kind == AccessKind.Write && (p.path.value == dir || p.path.value.startsWith("$dir/"))
                }
            }

        companion object {
            fun of(ask: PermissionAsk, context: PolicyContext): Facts {
                val analyses = ask.commands.map { ShellAnalyzer.analyze(it.raw) }
                val commands = analyses.filterIsInstance<ShellAnalysis.Parsed>().flatMap { it.commands }
                val redirectWrites = mutableListOf<PathAccess>()
                val redirectReads = mutableListOf<PathAccess>()
                val filePaths = mutableListOf<PathAccess>()
                val filePathOwners = mutableListOf<Pair<PathAccess, SimpleCommand>>()
                val gitAfterCd = mutableSetOf<SimpleCommand>()
                var changedDirectory = false
                var fileCommandsCovered = true
                val unresolved = mutableListOf<String>()
                val credentialTargets = ask.paths.map { it.path }.toMutableList()
                // 按顺序跟踪 cd：`cd /sdcard && echo x > a` 写的是 /sdcard/a。管道里的 cd 其实不生效，这里一律当生效（宁可多问）
                // Bash 的命令是从 shell 当前目录（cd 带过来的）开始跑的：相对路径、凭证目标都按它解析，不能按会话目录。
                // 只对 Bash：Monitor 等其他带命令的工具仍从会话目录起跑，按 shell 目录分析反而会错位
                val start = (if (ask.tool == "Bash") context.shellCwd else null) ?: context.cwd
                var cwd: String? = start.value
                var credentialCwd: String? = start.value
                for (cmd in commands) {
                    if (changedDirectory && cmd.program == "git") gitAfterCd += cmd
                    for (r in cmd.redirects.filter { it.writesFile || it.readsFile }) {
                        resolve(r.target, credentialCwd, context, normalize=false)?.let { credentialTargets += it }
                        val path = resolve(r.target, cwd, context)
                        if (path == null) unresolved += r.target
                        else {
                            if (r.writesFile) {
                                val access = PathAccess(path, AccessKind.Write, scope(path, context))
                                redirectWrites += access
                                filePathOwners += access to cmd
                            }
                            if (r.readsFile) redirectReads += PathAccess(path, AccessKind.Read, scope(path, context))
                        }
                    }
                    val operands = fileCommandPaths(cmd)
                    val beforeOperands = filePaths.size
                    if (operands == null && !CommandSafety.isReadOnly(cmd)) fileCommandsCovered = false
                    for (operand in operands.orEmpty()) {
                        resolve(operand.target, credentialCwd, context, normalize=false)?.let { credentialTargets += it }
                        val path = resolve(operand.target, cwd, context)
                        if (path == null) unresolved += operand.target
                        else filePaths += PathAccess(path, operand.kind, scope(path, context))
                    }
                    val reads = readCommandPaths(cmd)
                    if (reads == null) unresolved += "读文件命令含未展开的目标"
                    for (operand in reads.orEmpty()) {
                        resolve(operand.target, credentialCwd, context, normalize=false)?.let { credentialTargets += it }
                        val path = resolve(operand.target, cwd, context)
                        if (path == null) unresolved += operand.target
                        else filePaths += PathAccess(path, AccessKind.Read, scope(path, context, operand.glob))
                    }
                    filePaths.drop(beforeOperands).forEach { filePathOwners += it to cmd }
                    if (cmd.program in setOf("cd", "pushd", "popd")) {
                        changedDirectory = true
                        cwd = nextCwd(cmd, cwd, context)
                        credentialCwd = nextCwd(cmd, credentialCwd, context, normalize=false)
                    }
                }
                return Facts(
                    tool = ask.tool,
                    commands = commands,
                    rawCommands = ask.commands.map { it.raw },
                    untrustedRaw = ask.commands.zip(analyses).filter { it.second is ShellAnalysis.Untrusted }.map { it.first.raw },
                    redirectWrites = redirectWrites,
                    redirectReads = redirectReads,
                    filePaths = filePaths,
                    filePathOwners = filePathOwners,
                    fileCommandsCovered = fileCommandsCovered && gitAfterCd.isEmpty(),
                    gitAfterCd = gitAfterCd,
                    unresolvedWrites = unresolved,
                    paths = ask.paths.map {
                        val path = GuestPath(normalize(it.path.value))
                        it.copy(path = path, scope = if (it.scope == PathScope.Device) it.scope else scope(path, context))
                    } + redirectWrites + redirectReads + filePaths,
                    domains = ask.domains,
                    credentialTargets = credentialTargets,
                    specifiers = ask.specifiers,
                    memoryDir = context.autoMemoryDir?.let { normalize(it.value) },
                    worktreeRoot = context.worktreeRoot?.let { normalize(it.value) },
                )
            }

            private fun scope(path: GuestPath, context: PolicyContext, glob: Boolean = false): PathScope {
                val first = path.value.removePrefix("/").substringBefore('/')
                if (glob && first.any { it in "*?[" } && listOf("sdcard", "storage").any { it.startsWith(first.takeWhile { c -> c !in "*?[" }) }) {
                    return PathScope.Device
                }
                // 宿主 scopeOf 接受实际路径，不接受 shell 模式（Windows Path 连 * 都不能解析）。
                val scoped = if (path.value.any { it in "*?[" }) {
                    val prefix = path.value.takeWhile { it !in "*?[" }.substringBeforeLast('/', "/").ifEmpty { "/" }
                    GuestPath(prefix)
                } else path
                val original = context.scopeOf(scoped)
                // additionalDirectories 不得覆盖手机文件底线；宿主解析后给的 Device 分级优先。
                if (original == PathScope.Device) return original
                return if (context.additionalDirectories.any {
                    val dir = normalize(it.value).trimEnd('/')
                    scoped.value == dir || scoped.value.startsWith("$dir/")
                }) PathScope.Workspace else original
            }

            /** cd 之后的目录；静态不知道（`cd -`、`popd`、含变量）为 null */
            private fun nextCwd(cmd: SimpleCommand, cwd: String?, context: PolicyContext, normalize: Boolean = true): String? {
                if (cmd.program == "popd" || cmd.hasExpansion) return null
                val args = cmd.argv.drop(1).filterNot { it == "-P" || it == "-L" || it == "-e" || it == "--" }
                val target = args.singleOrNull() ?: return if (args.isEmpty()) context.home.value else null
                if (target == "-") return null
                return resolve(target, cwd, context, normalize)?.value
            }

            /** 相对路径接到 [cwd] 上，`~` 是家目录，`..` 规范化掉；[cwd] 不知道时相对路径也不知道 */
            private fun resolve(target: String, cwd: String?, context: PolicyContext, normalize: Boolean = true): GuestPath? {
                val absolute = when {
                    !normalize && target.matches(Regex("[A-Za-z]:[\\\\/].*")) -> "/${target.first().lowercaseChar()}/${target.substring(3).replace('\\','/')}"
                    target.startsWith("/") -> target
                    target == "~" -> context.home.value
                    target.startsWith("~/") -> context.home.value + "/" + target.removePrefix("~/")
                    cwd == null -> return null
                    else -> "$cwd/$target"
                }
                // 凭证目标先交给宿主解析链接，不能先折叠 link/.. 而改掉实际目标。
                return GuestPath(if(normalize) normalize(absolute) else absolute)
            }

            /** 去掉 `.`、`..` 与重复的斜杠；`..` 到根为止（与内核一致） */
            fun normalize(path: String): String {
                return normalizePolicyPath(path)
            }
        }
    }
}
