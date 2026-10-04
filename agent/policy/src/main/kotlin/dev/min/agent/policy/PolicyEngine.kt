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
 * 1. **安全底线**（任何模式都问人，含 bypass）：写手机文件；写受保护路径；递归删关键路径、抹磁盘、强推这类无法挽回的命令；
 *    看不透的命令里出现危险词。
 * 2. **拒绝规则**：任一命中即拒（跨层级拒绝优先，与 CC 一致）。
 * 3. **询问规则**：任一命中即问。
 * 4. **允许规则**：这次调用碰到的每一样东西（每条简单命令、每个路径、每个域名）都被允许规则或只读命令覆盖，才放行。
 * 5. **模式**：bypass 放行；计划模式只读；dontAsk 只放行只读；acceptEdits 放行工作区内的编辑；默认只放行只读。
 *    Bash 由命令分析决定是否只读：`ls`、`git status` 这类在默认模式下免批（与 CC 一致）。
 */
object PolicyEngine {

    /** 只按模式判定（没有规则、按前缀分级）。内核接上规则与宿主分级之前沿用它 */
    fun evaluate(ask: PermissionAsk, readOnly: Boolean, mode: PermissionMode): Verdict =
        evaluate(ask, readOnly, PolicyContext(mode))

    fun evaluate(ask: PermissionAsk, readOnly: Boolean, context: PolicyContext): Verdict {
        val facts = Facts.of(ask, context)
        val matcher = RuleMatcher(context.cwd, context.home)

        if (floorReason(facts) != null) return Verdict.Ask

        val deny = context.rules.filter { it.kind == RuleKind.Deny }
        firstHit(facts, deny, matcher)?.let { rule ->
            return Verdict.Deny(
                DecidedBy.Rule(rule.raw, rule.source),
                "这个操作被权限规则「${rule.raw}」（${rule.source}）禁止。不要原样重试，换个做法或请用户调整规则。",
            )
        }
        if (firstHit(facts, context.rules.filter { it.kind == RuleKind.Ask }, matcher) != null) return Verdict.Ask

        covering(facts, context.rules.filter { it.kind == RuleKind.Allow }, matcher)?.let { rule ->
            return Verdict.Allow(DecidedBy.Rule(rule.raw, rule.source))
        }

        val effectivelyReadOnly = readOnly || facts.isReadOnlyCommand
        val byMode = DecidedBy.Mode(context.mode)
        return when (context.mode) {
            PermissionMode.BypassPermissions -> Verdict.Allow(byMode)
            PermissionMode.Plan ->
                if (effectivelyReadOnly) Verdict.Allow(byMode)
                else Verdict.Deny(byMode, "计划模式下只能读不能改。先把计划整理好，用 ExitPlanMode 交给用户确认。")
            PermissionMode.DontAsk ->
                if (effectivelyReadOnly) Verdict.Allow(byMode)
                else Verdict.Deny(byMode, "当前模式不询问用户，这个操作没有被预先允许。")
            PermissionMode.AcceptEdits ->
                if (effectivelyReadOnly || facts.isWorkspaceEditOnly) Verdict.Allow(byMode) else Verdict.Ask
            // auto 模式的审查模型在 M5 落地，在那之前按 default 处理
            PermissionMode.Default, PermissionMode.Auto ->
                if (effectivelyReadOnly) Verdict.Allow(byMode) else Verdict.Ask
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
        val parsed = PermissionRule.parse(RuleKind.Allow, rule, "hook-if") ?: return false
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

    private fun floorReason(facts: Facts): String? {
        facts.paths.firstOrNull { it.kind == AccessKind.Write && it.scope == PathScope.Device }
            ?.let { return "要写手机上的真实文件 ${it.path}" }
        facts.paths.firstOrNull { it.kind == AccessKind.Write && CommandSafety.isProtectedPath(it.path.value) }
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
                    facts.paths.any { p -> m.matchesPath(rule, p) && !exempted(p, rule, rules, m) } ||
                    facts.domains.any { m.matchesDomain(rule, it) } ||
                    facts.specifiers.any { m.matchesSpecifier(rule, facts.tool, it) }
                )
        }

    /** `!` 规则：同一来源里的否定规则把路径从更早的拒绝 / 询问里豁免出来（CC 的 `Read(!sample.env)`） */
    private fun exempted(path: PathAccess, rule: PermissionRule, rules: List<PermissionRule>, m: RuleMatcher): Boolean =
        rules.any { it.source == rule.source && m.isNegation(it) && m.matchesPath(it, path) }

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
        rules.firstOrNull { m.matchesTool(it, facts.tool) }?.let { return it }
        if (facts.untrusted || facts.unresolvedWrites.isNotEmpty()) return null
        if (facts.commands.isEmpty() && facts.paths.isEmpty() && facts.domains.isEmpty() && facts.specifiers.isEmpty()) return null
        var decisive: PermissionRule? = null
        for (cmd in facts.commands) {
            val rule = rules.firstOrNull { m.matchesCommand(it, cmd) }
            if (rule == null && !CommandSafety.isReadOnly(cmd)) return null
            decisive = decisive ?: rule
        }
        for (path in facts.paths) {
            val rule = rules.firstOrNull { !m.isNegation(it) && m.matchesPath(it, path) }
            // 命令的重定向写进工作区：规则已允许这条命令，就不再单独要求 Edit 规则
            val okByCommand = path in facts.redirectWrites && path.scope == PathScope.Workspace && decisive != null
            if (rule == null && !okByCommand) return null
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
        if (floorReason(ask, context) != null) return null
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
        /** 前面 cd 到了静态不知道的地方之后的相对重定向目标：写到哪里不知道 */
        val unresolvedWrites: List<String>,
        val paths: List<PathAccess>,
        val domains: List<String>,
        val specifiers: List<String>,
        val memoryDir: String?,
    ) {
        val untrusted: Boolean get() = untrustedRaw.isNotEmpty()

        /** Bash 调用且每条简单命令都只读、不往文件里写 */
        val isReadOnlyCommand: Boolean
            get() = rawCommands.isNotEmpty() && !untrusted && redirectWrites.isEmpty() && unresolvedWrites.isEmpty() &&
                commands.all { CommandSafety.isReadOnly(it) }

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
                val unresolved = mutableListOf<String>()
                // 按顺序跟踪 cd：`cd /sdcard && echo x > a` 写的是 /sdcard/a。管道里的 cd 其实不生效，这里一律当生效（宁可多问）
                var cwd: String? = context.cwd.value
                for (cmd in commands) {
                    for (r in cmd.redirects.filter { it.writesFile }) {
                        val path = resolve(r.target, cwd, context)
                        if (path == null) unresolved += r.target
                        else redirectWrites += PathAccess(path, AccessKind.Write, context.scopeOf(path))
                    }
                    if (cmd.program in setOf("cd", "pushd", "popd")) cwd = nextCwd(cmd, cwd, context)
                }
                return Facts(
                    tool = ask.tool,
                    commands = commands,
                    rawCommands = ask.commands.map { it.raw },
                    untrustedRaw = ask.commands.zip(analyses).filter { it.second is ShellAnalysis.Untrusted }.map { it.first.raw },
                    redirectWrites = redirectWrites,
                    unresolvedWrites = unresolved,
                    paths = ask.paths.map { it.copy(path = GuestPath(normalize(it.path.value))) } + redirectWrites,
                    domains = ask.domains,
                    specifiers = ask.specifiers,
                    memoryDir = context.autoMemoryDir?.let { normalize(it.value) },
                )
            }

            /** cd 之后的目录；静态不知道（`cd -`、`popd`、含变量）为 null */
            private fun nextCwd(cmd: SimpleCommand, cwd: String?, context: PolicyContext): String? {
                if (cmd.program == "popd" || cmd.hasExpansion) return null
                val args = cmd.argv.drop(1).filterNot { it == "-P" || it == "-L" || it == "-e" || it == "--" }
                val target = args.singleOrNull() ?: return if (args.isEmpty()) context.home.value else null
                if (target == "-") return null
                return resolve(target, cwd, context)?.value
            }

            /** 相对路径接到 [cwd] 上，`~` 是家目录，`..` 规范化掉；[cwd] 不知道时相对路径也不知道 */
            private fun resolve(target: String, cwd: String?, context: PolicyContext): GuestPath? {
                val absolute = when {
                    target.startsWith("/") -> target
                    target == "~" -> context.home.value
                    target.startsWith("~/") -> context.home.value + "/" + target.removePrefix("~/")
                    cwd == null -> return null
                    else -> "$cwd/$target"
                }
                return GuestPath(normalize(absolute))
            }

            /** 去掉 `.`、`..` 与重复的斜杠；`..` 到根为止（与内核一致） */
            fun normalize(path: String): String {
                val parts = ArrayDeque<String>()
                for (part in path.split('/')) {
                    when (part) {
                        "", "." -> Unit
                        ".." -> parts.removeLastOrNull()
                        else -> parts.addLast(part)
                    }
                }
                return parts.joinToString("/", prefix = "/")
            }
        }
    }
}
