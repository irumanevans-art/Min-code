package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath

/**
 * `.claude/agents/` 下的子 agent 定义（03-extensions.md §3.1、§3.2）。
 *
 * - 优先级：managed > project > user（§3.2；**与 skills 相反**）。身份只看 `name`，同名时高优先级位置胜，被覆盖的记一条说明。
 *   `--agents` 会话级 JSON 与插件 agents 不在这一单里。
 * - 目录**递归**扫描（§3.2 原文「目录递归扫描」；任务单写的是 `*.md` 一层，以文档为准）。同一目录树里 `name` 重复：按路径顺序先到先得，其余记 note。
 * - 必填 `name` 与 `description`：缺了或类型不对就跳过这个文件并说明——没有 description 就没有委派依据，硬收进来只会变成永远不被选中的幽灵。
 * - `name` 不能以 `-` 开头、不能含 `:`（`:` 留给插件作用域），违反则跳过并说明。
 * - 未知字段静默忽略（文档原文），不记 note。
 * - managed 的位置文档没写，取 managed 设置所在目录下的 `.claude/agents`（与 skills 文档里「managed settings directory 下的 `.claude/skills`」同构）。
 */
internal object AgentLoader {
    /** [roots] 按优先级高 → 低 */
    suspend fun load(io: ConfigIo, roots: List<DefinitionRoot>): List<AgentDef> {
        val byName = LinkedHashMap<String, AgentDef>()
        for (root in roots) {
            for (segments in io.markdownFiles(root.dir)) {
                val file = GuestPaths.join(root.dir, segments.joinToString("/"))
                val agent = read(io, root.source, file) ?: continue
                val winner = byName[agent.name]
                if (winner == null) {
                    byName[agent.name] = agent
                } else {
                    val why = if (winner.source == agent.source) "同一来源里先到先得" else "同名时 managed > project > user"
                    io.notes += "子 agent `${agent.name}`（${agent.path}，${agent.source.label}）被 ${winner.path}（${winner.source.label}）覆盖：$why"
                }
            }
        }
        return byName.values.toList()
    }

    internal suspend fun read(io: ConfigIo, source: ConfigSource, file: String, fallbackName: String? = null): AgentDef? {
        val text = io.readText(file) ?: return null
        val fm = FrontmatterParser.parse(text, file)
        io.notes += fm.notes
        val f = FrontmatterFields(fm.fields, file, io.notes)
        val name = (f.string("name") ?: fallbackName)?.trim().orEmpty()
        val description = f.string("description")?.trim().orEmpty()
        when {
            name.isEmpty() -> return skip(io, file, "缺少必填的 `name`")
            description.isEmpty() -> return skip(io, file, "缺少必填的 `description`")
            name.startsWith("-") || ':' in name -> return skip(io, file, "`name` 不能以 `-` 开头或含 `:`（`$name`）")
        }
        val list = FrontmatterFields.Separators.CommaOrSpace
        return AgentDef(
            name = name,
            description = description,
            prompt = fm.body,
            path = GuestPath(file),
            source = source,
            tools = f.list("tools", list),
            disallowedTools = f.list("disallowedTools", list).orEmpty(),
            model = f.string("model"),
            permissionMode = f.string("permissionMode"),
            maxTurns = f.int("maxTurns"),
            skills = f.list("skills", list).orEmpty(),
            mcpServers = f.json("mcpServers"),
            hooks = f.obj("hooks"),
            memory = f.string("memory"),
            background = f.bool("background"),
            omitClaudeMd = f.bool("omitClaudeMd") ?: false,
            effort = f.string("effort"),
            isolation = f.string("isolation"),
            color = f.string("color"),
            initialPrompt = f.string("initialPrompt"),
            experimental = f.obj("experimental"),
        )
    }

    private fun skip(io: ConfigIo, file: String, why: String): AgentDef? {
        io.notes += "跳过子 agent 定义 $file：$why"
        return null
    }
}
