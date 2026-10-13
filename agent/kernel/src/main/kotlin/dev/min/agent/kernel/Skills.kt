package dev.min.agent.kernel

import kotlinx.serialization.json.jsonObject
import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.skillWatchRoots
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.config.SkillDiscovery
import dev.min.agent.ext.config.addDirSkills
import dev.min.agent.ext.skills.BundledSkills
import dev.min.agent.ext.skills.RunResult
import dev.min.agent.ext.skills.SkillRun
import dev.min.agent.ext.skills.SkillRunOwner
import dev.min.agent.ext.skills.SkillRunPort
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.policy.PolicyEngine
import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.Durability
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FORK_AGENT_TYPE
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.SkillEntry
import dev.min.agent.spi.SkillInvocation
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SkillPort
import dev.min.agent.spi.SkillsDiscovered
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 会话创建时读一次 skills / commands 的清单快照（连同 settings 里的清单预算键）。
 * 只留「列出来与找到它」需要的字段，不留正文：见 [dev.min.agent.ext.skills.SkillFiles]。
 */
internal object SkillSnapshots {
    /** 什么都没读到、也没有要告诉人的说明时返回 null（不写事件） */
    suspend fun load(
        fs: WorkspaceFs,
        cwd: GuestPath,
        home: GuestPath,
        plugins: dev.min.agent.ext.plugins.PluginBundle? = null,
        inputs: dev.min.agent.ext.config.SettingsInputs = dev.min.agent.ext.config.SettingsInputs(),
        /** `--bare`：不发现任何 skill，只加载 `--add-dir` 目录里的（CLI reference 原文） */
        addDirsOnly: Boolean = false,
    ): SkillsLoaded? {
        if (addDirsOnly) {
            val added = addDirSkills(fs, inputs.addDirs)
            val entries = added.skills.map { it.toEntry() }
            if (entries.isEmpty() && added.notes.isEmpty()) return null
            return SkillsLoaded(entries, notes = added.notes)
        }
        val config = ClaudeConfigLoader.load(fs, cwd, home, inputs = inputs)
        val notes = config.notes.drop(config.settings.notes.size).toMutableList()
        val merged = config.settings.merged
        // 内置 skill（CC 的 bundled skills）排在磁盘与插件之后：同名时让位，文档写的是「你的 skill 替换内置的」。
        // `disableBundledSkills: true` 整体关掉；--bare / --safe-mode 不走到这里（见 SessionRuntime）
        val disk = (config.skills + plugins?.skills.orEmpty()).distinctBy { it.name }
        val bundled = if (merged.raw("disableBundledSkills").isTrueFlag()) emptyList() else BundledSkills.defs().filter { b -> disk.none { it.name == b.name } }
        // 内置 skill 替换同名的内置命令（文档：skill 替换 command）；磁盘与插件的命令照旧
        val bundledNames = bundled.map { it.name }.toSet()
        val entries = (disk + bundled).map {
            SkillEntry(
                name = it.name, description = it.description, path = it.path, kind = SkillKind.Skill, whenToUse = it.whenToUse,
                source = it.sourceLabel, argumentHint = it.argumentHint, arguments = it.arguments,
                disableModelInvocation = it.disableModelInvocation, userInvocable = it.userInvocable,
                context = it.context, hooks = it.hooks, agent = it.agent, background = it.background, allowedTools = it.allowedTools,
            )
        } + (config.commands + plugins?.commands.orEmpty()).distinctBy { it.name }.filter { it.name !in bundledNames }.map {
            SkillEntry(
                name = it.name, description = it.description, path = it.path, kind = SkillKind.Command, whenToUse = it.whenToUse,
                source = it.sourceLabel, argumentHint = it.argumentHint, arguments = it.arguments,
                disableModelInvocation = it.disableModelInvocation, userInvocable = it.userInvocable,
                context = it.context, hooks = it.hooks, agent = it.agent, background = it.background, allowedTools = it.allowedTools,
            )
        }
        val fraction = number(merged.raw("skillListingBudgetFraction"))?.takeIf { it > 0 && it <= 1 }
        if (merged.raw("skillListingBudgetFraction") != null && fraction == null) {
            notes += "忽略 skillListingBudgetFraction：必须是大于 0 且不超过 1 的数字"
        }
        val maxChars = (merged.raw("skillListingMaxDescChars") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
        if (merged.raw("skillListingMaxDescChars") != null && maxChars == null) {
            notes += "忽略 skillListingMaxDescChars：必须是正整数"
        }
        if (entries.isEmpty() && notes.isEmpty() && fraction == null && maxChars == null) return null
        return SkillsLoaded(entries, fraction, maxChars, notes)
    }

    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** 布尔开关：只认真正的 true / false，字符串 "true" 不算 */
    private fun JsonElement?.isTrueFlag(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
}

/**
 * 斜杠 `/loop` 附的「内核解析结果」（CLI 的交互输入用；模型调 Skill 工具的路在 [KernelSkillPort] 里附同一段）。
 * 为什么公开：斜杠展开在 CLI 模块里，那边看不到内部的 [LoopCommand]；两条路必须算出同一份结果。
 */
suspend fun SessionRuntime.loopPlanBlock(args: String): String {
    val (project, user) = BundledSkills.loopMdTexts(deps.host.fs, state.value.settings.cwd, deps.memoryHome)
    return LoopCommand.planBlock(args, project, user)
}

/**
 * Skill 工具背后的端口：按会话**冻结的清单**找 skill，再叠上日志里记着的中途变化（嵌套目录懒加载、`--add-dir` 重扫，见 [skillEntries]）；
 * 用户 / 项目层磁盘上中途新增的 skill 这个会话里调不到。正文调用时才读、此刻渲染——读到什么都留在那次工具结果里，请求照样能从日志重建。
 */
internal class KernelSkillPort(
    private val rt: SessionRuntime,
    private val fs: WorkspaceFs,
    private val turnId: TurnId,
    private val callId: CallId,
) : SkillPort {
    override suspend fun invoke(name: String, args: String?): SkillInvocation {
        val state = rt.state.value
        val entries = state.skillEntries()
        val entry = entries.firstOrNull { it.name == name }
            ?: return SkillInvocation.Rejected(unknown(name, entries))
        if (entry.disableModelInvocation) {
            return SkillInvocation.Rejected(
                "skill「$name」设置了 disable-model-invocation，只能由用户用 /$name 手动触发，你不能调用它。" +
                    "不要用别的办法复现它的步骤；需要的话告诉用户这个 skill 要由他们自己运行。",
            )
        }
        val body = rt.readSkillBody(entry.path)
            ?: return SkillInvocation.Rejected("skill「$name」的文件现在读不了（${entry.path.value}，可能在会话开始后被删除或改动）。告诉用户，或不用这个 skill 继续。")
        // skill 被调用（模型走的这条路）：frontmatter hooks 登记进本会话，余下回合生效。
        // 每次调用都重新登记全量（CC 的 invoke 时 register），整份替换旧的——once 用掉的也回来
        entry.hooks?.let { rt.registerComponentHooks(entry.kind.name.lowercase(), entry.name, entry.source ?: "user", it) }
        // 插件声明 `on-skill-invoke:<这个 skill>` 的 monitor：第一次调用时启动
        rt.startPluginMonitors(entry.name)
        val baseDir = if (entry.kind == SkillKind.Skill) entry.path.value.substringBeforeLast('/') else null
        val vars = SkillVars(
            sessionId = rt.sessionId.value,
            projectDir = state.settings.cwd.value,
            effort = state.settings.effort?.name?.lowercase(),
        )
        val owner = SkillRunOwner(
            name = entry.name, source = entry.source, allowedTools = entry.allowedTools,
            fork = entry.context == "fork", agent = entry.agent, background = entry.background, baseDir = baseDir,
        )
        // 预执行命令、分身都在这里跑（权限与信任见 KernelSkillRunPort）；失败整次中止，模型看不到半成品正文
        val text = when (val run = SkillRun.run(owner, body, args, entry.arguments, vars, KernelSkillRunPort(rt, turnId, callId))) {
            is RunResult.Refused -> return SkillInvocation.Rejected(run.message)
            is RunResult.Ok -> run.text
        }
        // 内置 /loop：间隔取整、提示词选择由内核算好，附在正文后面（照 LoopCommand.planBlock 的说明）
        if (BundledSkills.isBundled(entry.path.value) && name == "loop") {
            val (project, user) = BundledSkills.loopMdTexts(fs, state.settings.cwd, rt.deps.memoryHome)
            return SkillInvocation.Loaded(name, text + "\n\n" + LoopCommand.planBlock(args.orEmpty(), project, user))
        }
        return SkillInvocation.Loaded(name, text)
    }

    private fun unknown(name: String, entries: List<SkillEntry>): String {
        val callable = entries.filter { !it.disableModelInvocation }.map { it.name }
        val shown = callable.take(MAX_NAMES_IN_ERROR).joinToString("、")
        val more = if (callable.size > MAX_NAMES_IN_ERROR) " 等 ${callable.size} 个" else ""
        return "没有名为「$name」的 skill。" + if (callable.isEmpty()) "这个会话里没有可用的 skill。" else "可用的：$shown$more。只能用清单里的准确名字。"
    }

    private companion object {
        const val MAX_NAMES_IN_ERROR = 30
    }
}

/**
 * 预执行命令与分身运行的内核端口：一次 skill 调用（或一次用户敲 `/名字`）一个实例，回合号与调用号由创建者给。
 * 命令的判定与 Bash 完全同一套（[PolicyEngine] + 本会话规则，含 allowed-tools 放行），信任与 disableSkillShellExecution 在它前面。
 */
internal class KernelSkillRunPort(
    private val rt: SessionRuntime,
    private val turnId: TurnId,
    private val callId: CallId,
) : SkillRunPort {
    /** 同一次调用里只读一次设置（每条命令都读没有意义） */
    private var shellDisabledByPolicy: Boolean? = null

    override suspend fun begin(owner: SkillRunOwner) {
        rt.grantSkillRules(rt.skillGrantRules(owner), owner.name)
    }

    override suspend fun shell(owner: SkillRunOwner, command: String): RunResult {
        val label = "skill「${owner.name}」"
        // disableSkillShellExecution（文档）：用户、项目、插件、附加目录来源的 skill 与命令一律不执行，替换成说明；managed 与内置不受影响
        if (owner.isShellAffectedByPolicy() && shellDisabled()) return RunResult.Ok(SHELL_DISABLED_TEXT)
        // 项目里的 skill 与项目 hooks 走同一条信任口径：没有信任的工作区，项目里的东西不替人跑命令
        if (owner.source == ConfigSource.Project.label && !rt.deps.trustProjectHooks(rt.state.value.settings.cwd)) {
            return RunResult.Refused("$label 来自项目，这个工作区还没有被信任，它的 shell 命令没有执行。先信任工作区再调用它。")
        }
        val ask = PermissionAsk(
            tool = "Bash",
            summary = "$label 的预执行命令：" + command.take(SUMMARY_CHARS),
            commands = listOf(CommandAccess(raw = command)),
        )
        // 起点先定：目录失效时这里重置，下面的判定读到的 shell 目录与真正起进程的目录才是同一个（[shellStart]）
        val start = rt.shellStart().dir
        val context = PolicyContextFactory(rt).context()
        when (val verdict = PolicyEngine.evaluate(ask, readOnly = false, context)) {
            is Verdict.Allow -> Unit
            is Verdict.Deny -> return RunResult.Refused(refusal(command, verdict.messageToModel))
            Verdict.Ask -> {
                // 文档：注入的命令不弹审批；非自动模式下 ask 与 deny 一样中止调用。
                // 自动模式下不中止：skill 带着「先自己跑这条命令」的指示加载，模型自己的 Bash 调用再走自动模式的常规判定（含分类器）——
                // 内核不替它先跑、也不在这里单独送审。例外仍中止：指定了 agent 的分身 skill（命令要在子 agent 里跑，没有这条交接），
                // 以及这个会话里模型没有 Bash 工具（没法照着做）
                val abortReason = when {
                    rt.state.value.settings.mode != PermissionMode.Auto -> "这条命令需要用户批准。用 allowed-tools 预先放行它，或让用户批准后再调用。"
                    owner.fork && owner.agent != null -> "这条命令需要批准，而分身 skill 指定了 agent，自动模式下也不能交给模型代跑。用 allowed-tools 预先放行它。"
                    availableTools(rt.state.value, rt.deps, runtime = rt).none { it.name == "Bash" } -> "这条命令需要批准，而当前会话里没有 Bash 工具可以让模型自己去跑。"
                    else -> null
                }
                if (abortReason != null) return RunResult.Refused(refusal(command, abortReason))
                return RunResult.Ok(deferredCommand(command))
            }
        }
        val result = rt.runNetworkShell(callId, ShellCommand(command, start), ExecOptions(timeoutMs = SHELL_TIMEOUT_MS, foregroundBudgetMs = null), emptyList())
        return when (result) {
            is ExecResult.Exited ->
                if (result.exitCode == 0) RunResult.Ok(result.output)
                else RunResult.Refused("Shell command failed for pattern \"$command\"（退出码 ${result.exitCode}）：\n${result.output}")
            is ExecResult.TimedOut -> RunResult.Refused("Shell command failed for pattern \"$command\"：超过 $SHELL_TIMEOUT_MS 毫秒仍未结束，已终止。")
            // 没有前台预算就不会转后台；万一转了，按失败处理，不把半成品交给模型
            is ExecResult.Backgrounded -> RunResult.Refused("Shell command failed for pattern \"$command\"：命令转入了后台，预执行只接受前台结果。")
        }
    }

    override suspend fun fork(owner: SkillRunOwner, prompt: String): RunResult {
        val hub = rt.hub ?: return RunResult.Refused("skill「${owner.name}」要在分身里运行，这个环境没有子 agent 支持。")
        // 分身类型只能由内核派生；skill 写成 fork 就是想绕过「分身不能再委派」之类的约束
        if (owner.agent == FORK_AGENT_TYPE) return RunResult.Refused("skill「${owner.name}」的 agent 写成了 fork，分身只能由内核派生，skill 不能指定它。")
        val request = DelegateRequest(
            description = "skill ${owner.name}", prompt = prompt, agentType = owner.agent, background = owner.background,
            skillGrants = rt.skillGrantRules(owner),
        )
        return when (val r = hub.spawn(rt, turnId, callId, request)) {
            is DelegateResult.Started -> RunResult.Ok("skill「${owner.name}」已在后台的子 agent（${r.child.value}）里运行，结果会作为消息回到会话。")
            is DelegateResult.Finished -> if (r.isError) RunResult.Refused(r.report) else RunResult.Ok(r.report)
            is DelegateResult.Rejected -> RunResult.Refused(r.messageForModel)
        }
    }

    /** 自动模式下留给模型的交接：这条命令没有被预执行，请它自己用 Bash 先跑（原样给出命令），再按 skill 余下的内容继续 */
    private fun deferredCommand(command: String) =
        "[Not pre-run: this command needs approval. Run it yourself with the Bash tool first, then continue with the rest of this skill: $command]"

    private fun refusal(command: String, reason: String) = "Shell command permission check failed for pattern \"$command\"：$reason"

    private suspend fun shellDisabled(): Boolean = shellDisabledByPolicy ?: run {
        val home = rt.deps.memoryHome
        val flag = home != null && SettingsLoader.load(rt.deps.host.fs, rt.state.value.settings.cwd, home, inputs = rt.deps.settingsInputs)
            .merged.raw("disableSkillShellExecution").isTrueFlagOrNull()
        flag.also { shellDisabledByPolicy = it }
    }

    private fun SkillRunOwner.isShellAffectedByPolicy(): Boolean = source != ConfigSource.Managed.label && source != ConfigSource.Bundled.label

    private fun JsonElement?.isTrueFlagOrNull(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    private companion object {
        /** 文档：替换成的文字（不是中止） */
        const val SHELL_DISABLED_TEXT = "[shell command execution disabled by policy]"
        /** 文档：「each command runs under the Bash tool's default 2-minute timeout」 */
        const val SHELL_TIMEOUT_MS = 120_000L
        const val SUMMARY_CHARS = 120
    }
}

/** 用户在输入框敲 `/名字` 时用的运行端口：没有模型回合，内核给它新的回合号与调用号（子 agent 的父调用记在这上面） */
fun SessionRuntime.userSkillRunPort(): SkillRunPort =
    KernelSkillRunPort(this, TurnId(deps.ids.next("skill-turn")), CallId(deps.ids.next("skill-call")))

/** 清单里的一项：定义 → 快照字段（启动时与嵌套懒加载共用） */
internal fun SkillDef.toEntry(): SkillEntry = SkillEntry(
    name = name, description = description, path = path, kind = SkillKind.Skill, whenToUse = whenToUse,
    source = sourceLabel, argumentHint = argumentHint, arguments = arguments,
    disableModelInvocation = disableModelInvocation, userInvocable = userInvocable,
    context = context, hooks = hooks, agent = agent, background = background, allowedTools = allowedTools,
)

/**
 * 模型读写了某个子目录的文件：把那一层的 `.claude/skills` 懒加载进会话（文档：「They load the first time Claude reads or edits
 * a file in that subdirectory and stay available for the rest of the session」）。只在完整档里做；`--bare` / `--safe-mode`
 * 不自动发现 skills，这里也不做。结果落日志（SkillsDiscovered），重开会话不重新查。
 */
internal suspend fun SessionRuntime.discoverNestedSkills(touched: List<GuestPath>, anchor: dev.min.agent.protocol.MessageId? = null) {
    if (deps.customizations != Customizations.Full) return
    val s = state.value
    val taken = s.skillEntries().map { it.name }.toSet()
    val found = SkillDiscovery.nestedSkills(deps.host.fs, s.settings.cwd, touched, s.checkedSkillDirs, taken)
    if (found.checked.isEmpty() && found.notes.isEmpty()) return
    // 找到了新 skill 就要让模型知道（追加的 system-reminder，挂在 [anchor] 上），进模型视图的东西要 Sync；只有「查过」的记录 Batched 就够
    val durability = if (found.skills.isNotEmpty() && anchor != null) Durability.Sync else Durability.Batched
    commit(listOf(SkillsDiscovered(found.skills.map { it.toEntry() }, found.checked, found.notes, anchor)), durability)
}

/**
 * 嵌套目录懒加载来的 skills，转成斜杠展开用的定义（正文现读，与启动时的定义同一种形状）。CLI 与手机界面的斜杠入口共用。
 * 模型调 Skill 工具时直接走 [KernelSkillPort]，用不着这个。
 */
suspend fun SessionRuntime.nestedSkillDefs(): List<SkillDef> = state.value.nestedSkills.mapNotNull { entry ->
    val body = readSkillBody(entry.path) ?: return@mapNotNull null
    SkillDef(
        name = entry.name, description = entry.description, path = entry.path, body = body,
        source = dev.min.agent.ext.config.ConfigSource.entries.firstOrNull { it.label == entry.source } ?: dev.min.agent.ext.config.ConfigSource.Project,
        whenToUse = entry.whenToUse, argumentHint = entry.argumentHint, arguments = entry.arguments,
        disableModelInvocation = entry.disableModelInvocation, userInvocable = entry.userInvocable,
        allowedTools = entry.allowedTools, context = entry.context, agent = entry.agent, background = entry.background, hooks = entry.hooks,
    )
}



/**
 * 托管层设了 `allowManagedPermissionRulesOnly` 时仍然保留 `allowed-tools` 的来源（settings 页原文：「Skills from managed settings and
 * bundled skills keep their allowed-tools」）。原文列为**忽略**的是仓库 `.claude/`、用户 `~/.claude/skills|commands`、`--add-dir`、
 * 放在这两处 skills 目录里的 `.claude-plugin` 清单插件——它们在 Min 里的来源标签都是 project / user / add-dir；
 * 已安装的插件不在原文的名单里，保留。标签认不出来的一律当作受影响（失败关闭）。
 */
private fun keepsAllowedToolsUnderManagedOnly(source: String?): Boolean =
    source == ConfigSource.Managed.label || source == ConfigSource.Bundled.label || source?.startsWith("plugin:") == true

/**
 * 这次调用真正该放行的 `allowed-tools` 规则（已代入 `${CLAUDE_SKILL_DIR}`）。托管层设了 `allowManagedPermissionRulesOnly`、
 * 而这个 skill 来自受影响的来源时返回空：那几条工具回到托管规则与常规的权限提示（原文：「go through your organization's managed rules
 * and the normal permission prompt instead」），并留一条给人看的说明、记进 [ignoredSkillTools]（原文用 `/status` 列出；Min 没有 `/status`）。
 * 每次调用都重读设置：托管文件会话中途变了，下一次调用就按新的算。放行的写入口（[SessionRuntime.grantSkillRules]）与分身交接
 * （[KernelSkillRunPort.fork]）都经这里，没有第二条路能把被忽略的规则放进去。
 */
internal suspend fun SessionRuntime.skillGrantRules(owner: SkillRunOwner): List<String> {
    if (owner.allowedTools.isEmpty()) return emptyList()
    if (!keepsAllowedToolsUnderManagedOnly(owner.source) && managedPermissionRulesOnly()) {
        if (ignoredSkillToolNames.add(owner.name)) {
            commit(
                listOf(dev.min.agent.spi.Note(
                    dev.min.agent.spi.NoteLevel.Info,
                    "托管设置里 allowManagedPermissionRulesOnly 已开：skill「${owner.name}」（来自 ${owner.source ?: "未知来源"}）的 allowed-tools 被忽略，" +
                        "它列的工具按托管规则与常规权限提示处理。",
                )),
                Durability.Sync,
            )
        }
        return emptyList()
    }
    return resolveSkillGrantRules(owner.allowedTools, owner.baseDir)
}

/** 本会话里 `allowed-tools` 被 `allowManagedPermissionRulesOnly` 忽略过的 skill 名（按出现顺序，重开会话后重新累积） */
fun SessionRuntime.ignoredSkillTools(): List<String> = synchronized(ignoredSkillToolNames) { ignoredSkillToolNames.toList() }

private suspend fun SessionRuntime.managedPermissionRulesOnly(): Boolean {
    val home = deps.memoryHome ?: return false
    return SettingsLoader.load(deps.host.fs, state.value.settings.cwd, home, inputs = deps.settingsInputs).merged.allowManagedPermissionRulesOnly
}

/** skill 的 `allowed-tools` 规则文字：`${CLAUDE_SKILL_DIR}`（文档：允许在 allowed-tools 的 Bash 规则里用它）代入 skill 所在目录 */
internal fun resolveSkillGrantRules(allowed: List<String>, baseDir: String?): List<String> =
    allowed.map { if (baseDir != null) it.replace("\${CLAUDE_SKILL_DIR}", baseDir) else it }

/** 命令定义 → 清单项（与启动时 [SkillSnapshots.load] 里对命令的映射一致，重扫比对要逐字段相等） */
internal fun dev.min.agent.ext.config.CommandDef.toEntry(): SkillEntry = SkillEntry(
    name = name, description = description, path = path, kind = SkillKind.Command, whenToUse = whenToUse,
    source = sourceLabel, argumentHint = argumentHint, arguments = arguments,
    disableModelInvocation = disableModelInvocation, userInvocable = userInvocable,
    context = context, hooks = hooks, agent = agent, background = background, allowedTools = allowedTools,
)

/**
 * 一次重扫的结果。[available] = false 表示这个会话不读 skills（`--safe-mode`），没有扫；[added] 是新出现的名字、[changed] 是说明 / 参数等
 * 元数据变了的、[removed] 是没了的（只改正文的不算：正文每次调用时现读）；[total] 是重扫之后会话里可按名字调用的 skill 与命令的个数。
 */
class SkillReload(
    val available: Boolean,
    val total: Int,
    val added: List<String>,
    val removed: List<String>,
    val changed: List<String>,
    val notes: List<String> = emptyList(),
) {
    /** 给人看的一行（`/reload-skills` 的回执）：原文「Reports how many skills are available and how many were added or removed」 */
    fun summary(): String {
        if (!available) return "这个会话不加载 skills（--safe-mode），没有可重扫的。"
        val parts = buildList {
            if (added.isNotEmpty()) add("新增 ${added.size}（${added.joinToString("、")}）")
            if (removed.isNotEmpty()) add("移除 ${removed.size}（${removed.joinToString("、")}）")
            if (changed.isNotEmpty()) add("更新 ${changed.size}（${changed.joinToString("、")}）")
        }
        return "已重新扫描：现有 $total 个 skill / 命令可用" + if (parts.isEmpty()) "，没有变化。" else "；" + parts.joinToString("，") + "。"
    }
}

/**
 * 手动重扫（`/reload-skills`，commands 文档：「Re-scan skill and command directories so skills added or changed on disk during the session
 * become available without restarting. Reports how many skills are available and how many were added or removed」）。
 * 范围：用户 `~/.claude`、项目（含仓库祖先）与 `--add-dir` 的 skills **和 commands**；已安装插件另有 `/reload-plugins`，托管目录与内置不读。
 * `--bare` 只重扫 `--add-dir` 的 skills（bare 启动时就只加载这一块）；`--safe-mode` 什么都不扫。差异的呈现与自动重扫同一条路（[SkillsRescanned]）。
 */
suspend fun SessionRuntime.reloadSkills(): SkillReload = rescanSkills(manual = true)

/**
 * 用户 / 项目 / `--add-dir` 层 skills 的热更新（skills 文档「Edit a skill during a session」：「Claude Code watches skill directories for file
 * changes, except in bare mode. When you add, edit, or remove a skill under `~/.claude/skills/`, the project `.claude/skills/`, or a
 * `.claude/skills/` inside an `--add-dir` directory, Claude Code picks up the change within the current session, without a restart」；
 * 「Live change detection covers SKILL.md text only」，`--add-dir` 的 commands / agents 不监视）。
 * Min 没有常驻文件监视，改在用户每次开新回合之前重扫一遍（[SessionRuntime.send]），与原文的差别：新建的顶层 skills 目录原文要 `/reload-skills`
 * 才认，这里下一回合就认（范围比原文大）；换来的是不必常驻监视进程。只读那几处目录，与会话当前已知的比对，
 * 有新增 / 改动 / 删除才写一条 [dev.min.agent.spi.SkillsRescanned]——系统提示里冻结的清单不动（前缀与缓存不断），
 * 差异以 system-reminder 附在这条用户消息后告诉模型，Skill 工具按新的找。`manual` = `/reload-skills`：多读 commands 目录、bare 也扫 add-dir。
 *
 * 什么算「受监视的」按文件路径判：落在这几个根目录之下的清单项才参与比对（嵌套目录懒加载来的、托管、内置、插件的不碰，
 * 免得被当成「没了」）。同名的优先级沿用启动时的（[ClaudeConfigLoader] 的顺序）；某个受监视的 skill 删掉后，被它遮住的内置 / 插件同名项还给会话。
 */
private suspend fun SessionRuntime.rescanSkills(manual: Boolean): SkillReload = skillRescanLock.withLock {
    val mode = deps.customizations
    val home = deps.memoryHome
    if (mode == Customizations.Safe || home == null || (mode == Customizations.Bare && !manual)) {
        return@withLock SkillReload(available = false, total = state.value.skillEntries().size, added = emptyList(), removed = emptyList(), changed = emptyList())
    }
    val s = state.value
    val fs = deps.host.fs
    // 会话中途 /add-dir 加的目录与 --add-dir 同待遇：它们的 skills（与手动重扫时的 commands）也要被扫到
    val inputs = deps.settingsInputs.let { it.copy(addDirs = (it.addDirs + s.addedDirectories).distinct()) }
    val bare = mode == Customizations.Bare
    val fresh: List<SkillEntry>
    val notes: List<String>
    if (bare) {
        val added = addDirSkills(fs, inputs.addDirs)
        fresh = added.skills.map { it.toEntry() }
        notes = added.notes
    } else {
        val cwd = s.created.cwd
        val config = ClaudeConfigLoader.load(fs, cwd, home, inputs = inputs)
        notes = config.notes.drop(config.settings.notes.size)
        fresh = config.skills.map { it.toEntry() } + if (manual) config.commands.map { it.toEntry() } else emptyList()
    }
    val roots = skillWatchRoots(fs, s.created.cwd, home, inputs.addDirs, inputs.restricted, manual, bare)
    fun watched(e: SkillEntry): Boolean = roots.covers(e.path.value, command = e.kind == SkillKind.Command)
    val current = s.skillEntries()
    val byName = current.associateBy { it.name }
    // 内置 skill 替换同名的命令（与启动时一致）：别让重扫把被内置顶掉的命令又加回来
    val freshWatched = fresh.filter { e ->
        watched(e) && !(e.kind == SkillKind.Command && byName[e.name]?.let { it.kind == SkillKind.Skill && BundledSkills.isBundled(it.path.value) } == true)
    }
    val freshNames = freshWatched.map { it.name }.toSet()
    val changedEntries = freshWatched.filter { byName[it.name] != it }
    val gone = current.filter { watched(it) && it.name !in freshNames }.map { it.name }
    // 被删掉的 skill 若遮住了冻结清单里的内置 / 插件同名项，把那一项还回去而不是标成「没了」
    val frozen = s.skills?.entries.orEmpty()
    val restored = gone.mapNotNull { n -> frozen.firstOrNull { it.name == n && !watched(it) } }
    val removed = gone.filter { n -> restored.none { it.name == n } }
    if (changedEntries.isNotEmpty() || gone.isNotEmpty()) {
        // 自动重扫发现的是磁盘上 skill 文件的变化：先问 ConfigChange hooks（source = skills，原文：`.claude/skills/` 里的 skill 文件变了）；
        // 被拦就不并进会话（Skill 工具与斜杠展开继续用旧清单），同一份差异不再反复问（digest）。手动 /reload-skills 是用户的命令，不是文件变化，不触发
        if (!manual) {
            val digest: Any = changedEntries to gone.sorted()
            if (digest == skillsBlockedDigest) return@withLock SkillReload(available = true, total = state.value.skillEntries().size, added = emptyList(), removed = emptyList(), changed = emptyList())
            val file = changedEntries.firstOrNull()?.path ?: current.firstOrNull { it.name in gone }?.path
            val specific = dev.min.agent.ext.hooks.HookJson.encodeToJsonElement(
                dev.min.agent.ext.hooks.ConfigChangeInput.serializer(),
                dev.min.agent.ext.hooks.ConfigChangeInput("skills", file?.let { deps.hookShellPath(it) }),
            ).jsonObject
            if (HookDriver(this).run(dev.min.agent.ext.hooks.HookEvent.ConfigChange, "skills", specific)?.decision == HookOutcome.Decision.Block) {
                skillsBlockedDigest = digest
                return@withLock SkillReload(available = true, total = state.value.skillEntries().size, added = emptyList(), removed = emptyList(), changed = emptyList())
            }
            skillsBlockedDigest = null
        }
        commit(listOf(dev.min.agent.spi.SkillsRescanned(changedEntries + restored, removed.sorted(), notes)), Durability.Sync)
    }
    SkillReload(
        available = true,
        total = state.value.skillEntries().size,
        added = changedEntries.filter { it.name !in byName }.map { it.name },
        removed = removed.sorted(),
        changed = changedEntries.filter { it.name in byName }.map { it.name } + restored.map { it.name },
        notes = notes,
    )
}

/** 每次开新回合前的自动重扫（[SessionRuntime.send]，以及斜杠入口展开之前——[slashDefs]）：回合在跑时不做，safe / bare 不做 */
internal suspend fun SessionRuntime.rescanSkillsBeforeTurn() {
    if (state.value.turn != null) return
    rescanSkills(manual = false)
}

/**
 * 会话里此刻可按名字调用的 skill 与命令（冻结的清单 + 中途重扫 / 嵌套发现的变化）。系统提示里的清单不走这里，那是冻结的。
 * 斜杠菜单与斜杠展开用它，保证与模型调 Skill 工具时看到的是同一份。
 */
fun SessionState.currentSkillEntries(): List<SkillEntry> = skillEntries()

/**
 * 斜杠入口展开用的清单（CLI 与手机界面共用）：[line] 开头连着的 `/名字`（最多 7 个：第一个加叠放的 5 个，多留一个不影响）对应的当前清单项，
 * 正文**此刻**才读（与模型路径同一条，所以改过正文的 skill 用新的）；读不了的名字进 [SlashDefs.unreadable]，调用处提示。
 * 不是斜杠开头的输入返回空清单。**不**用启动时的清单：热发现的 skill 要能展开（认名字之前先重扫一次），删掉的不能展开。
 */
suspend fun SessionRuntime.slashDefs(line: String): SlashDefs {
    // 磁盘上的 skill 中途变了：先重扫再认名字（与 [SessionRuntime.send] 回合前的重扫是同一套，没变化就不写事件）。
    // 展开发生在 send 之前，所以这一步不能等 send 里的那次
    if (line.trimStart().startsWith("/")) rescanSkillsBeforeTurn()
    val names = line.trimStart().split(Regex("\\s+")).take(7).takeWhile { it.startsWith("/") && it.length > 1 }.map { it.removePrefix("/") }.toSet()
    if (names.isEmpty()) return SlashDefs(emptyList(), emptyList(), emptySet())
    val skills = mutableListOf<SkillDef>()
    val commands = mutableListOf<dev.min.agent.ext.config.CommandDef>()
    val unreadable = mutableSetOf<String>()
    for (entry in state.value.skillEntries().filter { it.name in names }) {
        val body = readSkillBody(entry.path)
        if (body == null) {
            unreadable += entry.name
            continue
        }
        val source = ConfigSource.entries.firstOrNull { it.label == entry.source }
            ?: if (entry.source?.startsWith("plugin:") == true) ConfigSource.Plugin else ConfigSource.User
        if (entry.kind == SkillKind.Command) commands += dev.min.agent.ext.config.CommandDef(
            entry.name, entry.description, entry.path, body, source,
            whenToUse = entry.whenToUse, argumentHint = entry.argumentHint, arguments = entry.arguments,
            disableModelInvocation = entry.disableModelInvocation, userInvocable = entry.userInvocable, allowedTools = entry.allowedTools,
            context = entry.context, agent = entry.agent, background = entry.background, hooks = entry.hooks,
            sourceLabel = entry.source ?: source.label,
        ) else skills += SkillDef(
            entry.name, entry.description, entry.path, body, source,
            whenToUse = entry.whenToUse, argumentHint = entry.argumentHint, arguments = entry.arguments,
            disableModelInvocation = entry.disableModelInvocation, userInvocable = entry.userInvocable, allowedTools = entry.allowedTools,
            context = entry.context, agent = entry.agent, background = entry.background, hooks = entry.hooks,
            sourceLabel = entry.source ?: source.label,
        )
    }
    return SlashDefs(skills, commands, unreadable)
}

/** [slashDefs] 的结果 */
class SlashDefs(val skills: List<SkillDef>, val commands: List<dev.min.agent.ext.config.CommandDef>, val unreadable: Set<String>)
