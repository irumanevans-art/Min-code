package dev.min.agent.kernel

import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.config.ConfigSource
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
 * Skill 工具背后的端口：按会话**冻结的清单**找 skill（磁盘上中途新增的 skill 这个会话里调不到，与清单里看不到一致），
 * 正文调用时才读、此刻渲染——读到什么都留在那次工具结果里，请求照样能从日志重建。
 */
internal class KernelSkillPort(
    private val rt: SessionRuntime,
    private val fs: WorkspaceFs,
    private val turnId: TurnId,
    private val callId: CallId,
) : SkillPort {
    override suspend fun invoke(name: String, args: String?): SkillInvocation {
        val state = rt.state.value
        val entries = state.skills?.entries.orEmpty() + state.nestedSkills
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
        rt.grantSkillTools(owner.allowedTools, owner.baseDir)
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
        val context = ToolExecutor(rt, HookDriver(rt)).policyContext()
        when (val verdict = PolicyEngine.evaluate(ask, readOnly = false, context)) {
            is Verdict.Allow -> Unit
            is Verdict.Deny -> return RunResult.Refused(refusal(command, verdict.messageToModel))
            Verdict.Ask -> {
                // 文档：注入的命令不弹审批；非自动模式下 ask 与 deny 一样中止调用。自动模式走分类器审查（与普通 Bash 一致），
                // 分身里不许（文档：forked skill 的 ask 直接中止）
                if (rt.state.value.settings.mode != PermissionMode.Auto || owner.fork) {
                    return RunResult.Refused(refusal(command, "这条命令需要用户批准。用 allowed-tools 预先放行它，或让用户批准后再调用。"))
                }
                val input = buildJsonObject { put("command", JsonPrimitive(command)) }
                val review = rt.reviewAutoAction(turnId, "Bash", input, ask.summary)
                if (review is Verdict.Deny) return RunResult.Refused(refusal(command, review.messageToModel))
            }
        }
        val result = rt.runNetworkShell(callId, ShellCommand(command, rt.state.value.settings.cwd), ExecOptions(timeoutMs = SHELL_TIMEOUT_MS, foregroundBudgetMs = null), emptyList())
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
        val request = DelegateRequest(description = "skill ${owner.name}", prompt = prompt, agentType = owner.agent, background = owner.background)
        return when (val r = hub.spawn(rt, turnId, callId, request)) {
            is DelegateResult.Started -> RunResult.Ok("skill「${owner.name}」已在后台的子 agent（${r.child.value}）里运行，结果会作为消息回到会话。")
            is DelegateResult.Finished -> if (r.isError) RunResult.Refused(r.report) else RunResult.Ok(r.report)
            is DelegateResult.Rejected -> RunResult.Refused(r.messageForModel)
        }
    }

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
internal suspend fun SessionRuntime.discoverNestedSkills(touched: List<GuestPath>) {
    if (deps.customizations != Customizations.Full) return
    val s = state.value
    val taken = (s.skills?.entries.orEmpty() + s.nestedSkills).map { it.name }.toSet()
    val found = SkillDiscovery.nestedSkills(deps.host.fs, s.settings.cwd, touched, s.checkedSkillDirs, taken)
    if (found.checked.isEmpty() && found.notes.isEmpty()) return
    commit(listOf(SkillsDiscovered(found.skills.map { it.toEntry() }, found.checked, found.notes)), Durability.Batched)
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
