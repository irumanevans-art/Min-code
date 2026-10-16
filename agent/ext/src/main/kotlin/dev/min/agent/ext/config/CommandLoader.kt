package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath

/**
 * `.claude/commands/` 下的 `.md`（递归）。命名照 skills 文档的表：
 * 子目录路径用 `:` 连接再接去掉扩展名的文件名，`frontend/component.md` → `frontend:component`；frontmatter 里的 `name` 与 `paths` 不适用，忽略。
 *
 * 同名规则：
 * - command 与 skill 同名：skill 赢（文档明说），command 被丢弃并记一条说明。
 * - 两个来源的 command 同名（user 与 project）：**文档没写**。commands 已并入 skills，这里沿用 skills 的顺序 managed > user > project；
 *   这与子 agent（项目压过用户）相反，是一处可能与 CC 不符的选择，见 README「已知限制」。
 * - managed 位置的 commands 目录文档没提，所以只读 user 与 project。
 */
internal object CommandLoader {
    /** [roots] 按优先级高 → 低；[skillNames] 是已生效的 skill 名，同名的 command 让位 */
    suspend fun load(io: ConfigIo, roots: List<DefinitionRoot>, skillNames: Set<String>): List<CommandDef> {
        val byName = LinkedHashMap<String, CommandDef>()
        for (root in roots) {
            for (segments in io.markdownFiles(root.dir)) {
                val name = (segments.dropLast(1) + segments.last().removeSuffix(".md")).joinToString(":")
                val file = GuestPaths.join(root.dir, segments.joinToString("/"))
                if (isReservedSkillName(name)) {
                    io.notes += "跳过 $file：命令名 `$name` 是保留名，插件之外不加载（Claude Code 同样如此）"
                    continue
                }
                if (name in skillNames) {
                    io.notes += "命令 `$name`（$file）被同名的 skill 覆盖：同名时 skill 赢"
                    continue
                }
                val command = read(io, root.source, file, name) ?: continue
                val winner = byName[name]
                if (winner == null) {
                    byName[name] = command
                } else {
                    io.notes += "命令 `$name`（$file，${root.source.label}）被 ${winner.path}（${winner.source.label}）覆盖：同名时 managed > user > project"
                }
            }
        }
        return byName.values.toList()
    }

    internal suspend fun read(io: ConfigIo, source: ConfigSource, file: String, name: String): CommandDef? {
        // 文件 / 文件夹名带隐藏 / 改变方向的字符或 < >：不提供，改名后才会出现（CC 2.1.299）；插件的 commands 也走这里
        if (!isOfferableName(name)) {
            io.notes += "跳过 ${shownName(file)}：命令名 `${shownName(name)}` 不合格，$NAME_RULE_NOTE"
            return null
        }
        val text = io.readText(file) ?: return null
        val fm = FrontmatterParser.parse(text, file)
        io.notes += fm.notes
        val f = FrontmatterFields(fm.fields, file, io.notes)
        val p = PromptFields.read(f)
        return CommandDef(
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
            shell = p.shell,
            metadata = p.metadata,
            license = p.license,
            compatibility = p.compatibility,
        )
    }
}
