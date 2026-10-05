package dev.min.agent.ext.styles

import dev.min.agent.ext.config.ConfigIo
import dev.min.agent.ext.config.FrontmatterFields
import dev.min.agent.ext.config.FrontmatterParser
import dev.min.agent.ext.config.GuestPaths
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs

/** 内置样式 Explanatory 的正文（自己写的，意思对齐 CC，不抄原文）：做事的同时讲清为什么、给取舍 */
private val EXPLANATORY = """
    You are a coding agent whose user wants to understand the work, not just receive it. As you go:
    - For every meaningful choice — which file to change, which approach to take, which tool to use — say in one or two
      sentences why you chose it and what the trade-off was. Point at the actual code or command, not abstractions.
    - After finishing a step, summarize what changed and why it had to change that way, including anything you decided
      not to do.
    - Keep it short. When the reason is obvious from the request or irrelevant to the outcome, do the work without
      commentary; the explanation serves the user's understanding, not your own narration.
""".trimIndent()

/** 内置样式 Learning 的正文（自己写的）：探索、规划、杂活代理做，关键的几处留给用户亲手写并解释 */
private val LEARNING = """
    You are a coding agent working with a user who is learning this codebase and wants to write the key parts themselves.
    - Do the exploring, planning, and scaffolding yourself: find where things live, lay out the approach, prepare the boring parts.
    - For the few changes that teach the most — the core logic, the tricky fix, the new interface — do not edit them yourself.
      Show the relevant file and lines, explain what needs to happen and why, and hand the keyboard to the user to write it.
    - When the user asks you to review what they wrote, point out issues concretely and suggest fixes in words; rewrite their
      code only when they ask you to.
    - Everything else (tests, boilerplate, chores, running commands) you handle as usual, narrating briefly what you did.
""".trimIndent()

/**
 * 一个 output style 定义（CC output-styles.md）：frontmatter（`name` 默认文件名、`description`、
 * `keep-coding-instructions` 默认 false——自定义样式默认**省略**内置的软件工程指令）+ 正文。
 * 拼错的字段静默忽略；YAML 坏了仍按文件名加载（frontmatter 解析器已经是这个语义）。
 */
data class OutputStyleDef(
    val name: String,
    val description: String? = null,
    val keepCodingInstructions: Boolean = false,
    val body: String,
)

/**
 * 读磁盘上的 output styles（CC output-styles.md「File locations」）：
 * 用户 `~/.claude/output-styles`、项目 `.claude/output-styles`（**自 cwd 到仓库根逐层**找，近者胜——
 * 与 settings / skills 不一样，这个资源文档写了要往上找）、managed 目录下的 `.claude/output-styles`。
 * 同名时 project > user > managed（照 skills 的优先级约定；文档没写跨作用域谁赢）。插件目录以后再接。
 */
object OutputStyleFiles {
    /** CC 的内置样式里 Min 先做三个：Default 不改系统提示，另外两个的正文是自己写的（意思对齐，不抄 CC 原文） */
    val BUILTINS: Map<String, OutputStyleDef> = listOf(
        OutputStyleDef(name = "Default", body = ""),
        OutputStyleDef(name = "Explanatory", body = EXPLANATORY),
        OutputStyleDef(name = "Learning", body = LEARNING),
    ).associateBy { it.name }

    /** 磁盘上读到的样式，连同读文件时攒下的说明（YAML 坏了按文件名加载之类，进快照给人看） */
    data class DiskStyles(val styles: Map<String, OutputStyleDef>, val notes: List<String>)

    /** 名字 → 定义 + 说明。只读磁盘；内置样式不在这里（[resolve] 才把两边合起来，磁盘可覆盖内置同名） */
    suspend fun load(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath): DiskStyles {
        val io = ConfigIo(fs)
        val styles = scan(io, fs, cwd, home)
        return DiskStyles(styles, io.notes.toList())
    }

    /** 只列名字用（CLI 的 /output-style 无参）；说明丢弃 */
    suspend fun available(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath): Map<String, OutputStyleDef> =
        scan(ConfigIo(fs), fs, cwd, home)

    private suspend fun scan(io: ConfigIo, fs: WorkspaceFs, cwd: GuestPath, home: GuestPath): Map<String, OutputStyleDef> {
        val cwdPath = GuestPaths.normalize(cwd.value)
        val userDir = styleDir(GuestPaths.normalize(home.value))
        val managedDir = styleDir(GuestPaths.parentOf(GuestPaths.normalize(SettingsLoader.DEFAULT_MANAGED_PATH.value)))
        // 低的先放、高的后放（覆盖）：managed → user → 项目链（仓库根最远、cwd 最近，最近的最后放）。
        // cwd 就是家目录时项目层不重复算（同 skills 的处理）
        val projectDirs = projectDirs(io, cwdPath).map(::styleDir).filterNot { it == userDir }
        val dirs = listOf(managedDir, userDir) + projectDirs.asReversed()
        val byName = LinkedHashMap<String, OutputStyleDef>()
        for (dir in dirs) {
            for (entry in io.entries(dir)) {
                if (!entry.name.endsWith(".md")) continue
                if (io.resolvedKind(dir, entry) != FileKind.File) continue
                read(io, GuestPath(GuestPaths.join(dir, entry.name)))?.let { style ->
                    byName[style.name] = style
                }
            }
        }
        return byName
    }

    /** 按设置值选样式（大小写敏感，CC 同）；磁盘没有再看内置 */
    fun resolve(disk: Map<String, OutputStyleDef>, name: String): OutputStyleDef? = disk[name] ?: BUILTINS[name]

    private fun styleDir(root: String) = GuestPaths.join(GuestPaths.join(root, ".claude"), "output-styles")

    private suspend fun read(io: ConfigIo, file: GuestPath): OutputStyleDef? {
        val text = io.readText(file.value) ?: return null
        val fm = FrontmatterParser.parse(text, file.value)
        io.notes += fm.notes
        val f = FrontmatterFields(fm.fields, file.value, io.notes)
        val name = f.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: file.value.substringAfterLast('/').removeSuffix(".md")
        return OutputStyleDef(
            name = name,
            description = f.string("description")?.trim()?.takeIf { it.isNotEmpty() },
            keepCodingInstructions = f.bool("keep-coding-instructions") ?: false,
            body = fm.body,
        )
    }
}

/** 项目目录链：从 cwd（最近）到仓库根（含），近 → 远。没有 .git 时只有 cwd（文档说往上找到 repo root 为止） */
private suspend fun projectDirs(io: ConfigIo, cwd: String): List<String> {
    // ancestors 是宽 → 窄（/ 在最前、cwd 在最后）；往上走遇到的**最近** .git = 列表里最后一个带 .git 的
    val up = GuestPaths.ancestors(cwd)
    val root = up.indexOfLast { io.kind(GuestPaths.join(it, ".git")) != null }
    return if (root >= 0) up.subList(root, up.size).asReversed() else listOf(cwd)
}
