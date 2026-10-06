package dev.min.agent.kernel

import dev.min.agent.ext.config.ClaudeConfigLoader
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 会话创建时读一次 skills / commands 的清单快照（连同 settings 里的清单预算键）。
 * 只留「列出来与找到它」需要的字段，不留正文：见 [dev.min.agent.ext.skills.SkillFiles]。
 */
internal object SkillSnapshots {
    /** 什么都没读到、也没有要告诉人的说明时返回 null（不写事件） */
    suspend fun load(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath): SkillsLoaded? {
        val config = ClaudeConfigLoader.load(fs, cwd, home)
        val notes = config.notes.drop(config.settings.notes.size).toMutableList()
        val entries = config.skills.map {
            SkillEntry(
                name = it.name, description = it.description, path = it.path, kind = SkillKind.Skill, whenToUse = it.whenToUse,
                source = it.source.label, argumentHint = it.argumentHint, arguments = it.arguments,
                disableModelInvocation = it.disableModelInvocation, userInvocable = it.userInvocable, hooks = it.hooks,
            )
        } + config.commands.map {
            SkillEntry(
                name = it.name, description = it.description, path = it.path, kind = SkillKind.Command, whenToUse = it.whenToUse,
                source = it.source.label, argumentHint = it.argumentHint, arguments = it.arguments,
                disableModelInvocation = it.disableModelInvocation, userInvocable = it.userInvocable, hooks = it.hooks,
            )
        }
        val merged = config.settings.merged
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
        val body = SkillFiles.readBody(fs, entry.path)
            ?: return SkillInvocation.Rejected("skill「$name」的文件现在读不了（${entry.path.value}，可能在会话开始后被删除或改动）。告诉用户，或不用这个 skill 继续。")
        // skill 被调用（模型走的这条路）：frontmatter hooks 登记进本会话，余下回合生效（幂等，重复调用不叠加）
        entry.hooks?.let { rt.registerComponentHooks(entry.kind.name.lowercase(), entry.name, entry.source ?: "user", it) }
        val baseDir = if (entry.kind == SkillKind.Skill) entry.path.value.substringBeforeLast('/') else null
        val vars = SkillVars(
            sessionId = rt.sessionId.value,
            projectDir = state.settings.cwd.value,
            effort = state.settings.effort?.name?.lowercase(),
        )
        return SkillInvocation.Loaded(name, SkillPrompt.render(body, args, entry.arguments, baseDir, vars))
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
