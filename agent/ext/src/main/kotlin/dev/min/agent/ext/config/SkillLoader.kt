package dev.min.agent.ext.config

import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.JsonObject

/** 一个按优先级排好的定义目录：[source] 是它的层名，[dir] 是 `.claude/skills` 之类的目录本身 */
internal data class DefinitionRoot(val source: ConfigSource, val dir: String)

/** skills 与 commands 共用的 frontmatter 字段（文档：command「accepts the same fields except name and paths」） */
internal class PromptFields(
    val whenToUse: String?,
    val argumentHint: String?,
    val arguments: List<String>,
    val disableModelInvocation: Boolean,
    val userInvocable: Boolean,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val model: String?,
    val effort: String?,
    val context: String?,
    val agent: String?,
    val background: Boolean,
    val hooks: JsonObject?,
    val shell: String?,
    val metadata: JsonObject?,
    val license: String?,
    val compatibility: String?,
) {
    companion object {
        fun read(f: FrontmatterFields) = PromptFields(
            whenToUse = f.string("when_to_use"),
            argumentHint = f.hint("argument-hint"),
            arguments = f.list("arguments", FrontmatterFields.Separators.CommaOrSpace).orEmpty(),
            disableModelInvocation = f.bool("disable-model-invocation") ?: false,
            userInvocable = f.bool("user-invocable") ?: true,
            allowedTools = f.list("allowed-tools", FrontmatterFields.Separators.CommaOrSpace).orEmpty(),
            disallowedTools = f.list("disallowed-tools", FrontmatterFields.Separators.CommaOrSpace).orEmpty(),
            model = f.string("model"),
            effort = f.string("effort"),
            context = f.string("context"),
            agent = f.string("agent"),
            background = f.bool("background") ?: true,
            hooks = f.obj("hooks"),
            shell = f.string("shell"),
            // 文档：metadata「drops a value that isn't a map」——obj() 正是这个行为，并且会记 note
            metadata = f.obj("metadata"),
            license = f.string("license"),
            compatibility = f.string("compatibility"),
        )
    }
}

/**
 * 描述缺省时取正文第一个非空行（skills 文档原文：「If omitted, uses the first non-empty line of the markdown content」）。
 * 原样取，不去掉 Markdown 标题的 `#`——文档没说去。
 */
internal fun descriptionOrFirstLine(declared: String?, body: String): String =
    declared?.trim()?.takeIf { it.isNotEmpty() } ?: body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** 文档：目录或命令文件名为 `anthropic-skills`（或以 `anthropic-skills:` 开头）时，插件之外不加载 */
internal fun isReservedSkillName(name: String): Boolean = name == "anthropic-skills" || name.startsWith("anthropic-skills:")

/**
 * `<目录>/<名字>/SKILL.md`。优先级照文档 §7.3 的表：managed（企业）> user（个人）> project，
 * **与子 agent 相反**（agent 是项目压过用户）——同名时高优先级胜，被覆盖的记一条说明。
 *
 * 嵌套的 `<子目录>/.claude/skills` 不在这里（它是会话中的事，见 [SkillDiscovery]）；`--add-dir` 由 [ClaudeConfigLoader] 传入根；插件另有来源；claude.ai 同步不做。
 * 保留名：文档写明 `synced` 目录（claude.ai 同步用）不能作 skill 目录名，在 managed / user / project 位置会被跳过。
 */
internal object SkillLoader {
    /** [roots] 按优先级高 → 低 */
    suspend fun load(io: ConfigIo, roots: List<DefinitionRoot>): List<SkillDef> {
        val byName = LinkedHashMap<String, SkillDef>()
        for (root in roots) {
            for (entry in io.entries(root.dir)) {
                if (io.resolvedKind(root.dir, entry) != FileKind.Directory) continue
                val dirPath = GuestPaths.join(root.dir, entry.name)
                if (entry.name.equals("synced", ignoreCase = true)) {
                    io.notes += "跳过 $dirPath：`synced` 是 Claude Code 给 claude.ai 同步技能保留的目录名"
                    continue
                }
                val skill = readSkill(io, root.source, dirPath, entry.name) ?: continue
                if (isReservedSkillName(skill.name)) {
                    io.notes += "跳过 ${skill.path}：技能名 `${skill.name}` 是保留名，插件之外不加载（Claude Code 同样如此）"
                    continue
                }
                val winner = byName[skill.name]
                if (winner == null) {
                    byName[skill.name] = skill
                } else {
                    io.notes += "技能 `${skill.name}`（${skill.path}，${skill.source.label}）被 ${winner.path}（${winner.source.label}）覆盖：" +
                        "同名时 managed > user > project"
                }
            }
        }
        return byName.values.toList()
    }

    internal suspend fun readSkill(io: ConfigIo, source: ConfigSource, dir: String, dirName: String): SkillDef? {
        val file = GuestPaths.join(dir, "SKILL.md")
        val text = io.readText(file) ?: return null
        return skillFromText(text, source, file, dirName, io.notes)
    }

    /** 一份 SKILL.md 的文本 → 定义；磁盘与随包资源（[BundledSkills]）共用这一条解析 */
    internal fun skillFromText(text: String, source: ConfigSource, file: String, dirName: String, notes: MutableList<String>): SkillDef {
        val fm = FrontmatterParser.parse(text, file)
        notes += fm.notes
        val f = FrontmatterFields(fm.fields, file, notes)
        val p = PromptFields.read(f)
        val name = f.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: dirName
        return SkillDef(
            name = name,
            description = descriptionOrFirstLine(f.string("description"), fm.body),
            path = GuestPath(file),
            body = fm.body,
            source = source,
            whenToUse = p.whenToUse,
            argumentHint = p.argumentHint,
            arguments = p.arguments,
            disableModelInvocation = p.disableModelInvocation,
            userInvocable = p.userInvocable,
            allowedTools = p.allowedTools,
            disallowedTools = p.disallowedTools,
            model = p.model,
            effort = p.effort,
            context = p.context,
            agent = p.agent,
            background = p.background,
            hooks = p.hooks,
            paths = f.list("paths", FrontmatterFields.Separators.CommaOnly).orEmpty(),
            shell = p.shell,
            metadata = p.metadata,
            license = p.license,
            compatibility = p.compatibility,
        )
    }
}
