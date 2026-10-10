package dev.min.agent.kernel

import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.skills.BundledSkills
import dev.min.agent.ext.skills.SkillFiles
import dev.min.agent.ext.skills.SkillPrompt
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SkillEntry
import dev.min.agent.spi.SkillInvocation
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SkillPort
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.WorkspaceFs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 会话创建时读一次 skills / commands 的清单快照（连同 settings 里的清单预算键）。
 * 只留「列出来与找到它」需要的字段，不留正文：见 [dev.min.agent.ext.skills.SkillFiles]。
 */
internal object SkillSnapshots {
    /** 什么都没读到、也没有要告诉人的说明时返回 null（不写事件） */
    suspend fun load(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath, plugins: dev.min.agent.ext.plugins.PluginBundle? = null): SkillsLoaded? {
        val config = ClaudeConfigLoader.load(fs, cwd, home)
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
                context = it.context, hooks = it.hooks,
            )
        } + (config.commands + plugins?.commands.orEmpty()).distinctBy { it.name }.filter { it.name !in bundledNames }.map {
            SkillEntry(
                name = it.name, description = it.description, path = it.path, kind = SkillKind.Command, whenToUse = it.whenToUse,
                source = it.sourceLabel, argumentHint = it.argumentHint, arguments = it.arguments,
                disableModelInvocation = it.disableModelInvocation, userInvocable = it.userInvocable,
                context = it.context, hooks = it.hooks,
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
 * Skill 工具背后的端口：按会话**冻结的清单**找 skill（磁盘上中途新增的 skill 这个会话里调不到，与清单里看不到一致），
 * 正文调用时才读、此刻渲染——读到什么都留在那次工具结果里，请求照样能从日志重建。
 */
internal class KernelSkillPort(
    private val rt: SessionRuntime,
    private val fs: WorkspaceFs,
) : SkillPort {
    override suspend fun invoke(name: String, args: String?): SkillInvocation {
        val state = rt.state.value
        val entries = state.skills?.entries.orEmpty()
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
        val rendered = SkillPrompt.render(body, args, entry.arguments, baseDir, vars)
        // 内置 /loop：间隔取整、提示词选择由内核算好，附在正文后面（照 LoopCommand.planBlock 的说明）
        if (BundledSkills.isBundled(entry.path.value) && name == "loop") {
            val (project, user) = BundledSkills.loopMdTexts(fs, state.settings.cwd, rt.deps.memoryHome)
            return SkillInvocation.Loaded(name, rendered + "\n\n" + LoopCommand.planBlock(args.orEmpty(), project, user))
        }
        return SkillInvocation.Loaded(name, rendered)
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
