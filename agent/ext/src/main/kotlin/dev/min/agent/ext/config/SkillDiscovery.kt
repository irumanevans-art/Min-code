package dev.min.agent.ext.config

import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs

/**
 * 嵌套目录与仓库祖先里的 skills（Claude Code 的 skills 文档）：
 * - 会话主目录往上直到仓库根（含 `.git` 的那一层）的每个 `.claude/skills` 在启动时就加载；
 * - 子目录里的 `.claude/skills` 不在启动时加载，**模型第一次读写那个子目录里的文件时**才加载，之后整个会话可用。
 *
 * 纯逻辑，只经 [WorkspaceFs] 读。内核把结果记进日志（`skills_discovered`），这里不记。
 */
object SkillDiscovery {
    /**
     * [cwd] 与它往上直到仓库根（含）的 `.claude/skills` 目录，近的在前。不在 git 仓库里只返回 cwd 自己的。
     * 为什么不无条件往上找到 `/`：文档只说到仓库根；不在仓库里往上找，会把别的项目或家目录的 skill 拖进来。
     */
    suspend fun ancestorSkillDirs(fs: WorkspaceFs, cwd: GuestPath): List<String> {
        val io = ConfigIo(fs)
        val start = GuestPaths.normalize(cwd.value)
        var dir = start
        val chain = mutableListOf<String>()
        while (true) {
            chain += dir
            if (io.kind(GuestPaths.join(dir, ".git")) != null) return chain.map { GuestPaths.join(it, ".claude/skills") }
            val parent = GuestPaths.parentOf(dir)
            if (parent == dir) return listOf(GuestPaths.join(start, ".claude/skills"))
            dir = parent
        }
    }

    /** 一次懒加载的结果。[checked] 是这次检查过的 `.claude/skills` 目录（有没有都记，同一个目录不重复查）；[skills] 是新找到的 */
    class Nested(val checked: List<String>, val skills: List<dev.min.agent.ext.config.SkillDef>, val notes: List<String>)

    /**
     * [touched] 是这批工具碰到的文件。对每个文件，从 cwd 的下一层到它所在目录（含）逐层看有没有 `.claude/skills`，
     * [checked] 里已经查过的跳过。[taken] 是会话里已经占着的 skill 名（清单与之前的嵌套）。
     *
     * 重名（文档：「when a nested skill's directory name matches another skill's name, both stay available」）：
     * 嵌套的那个取 `<相对目录>:<名字>`，例如 `apps/web:deploy`；根上的 `deploy` 不动。加了限定名还撞的，跳过并记一条说明。
     */
    suspend fun nestedSkills(fs: WorkspaceFs, cwd: GuestPath, touched: List<GuestPath>, checked: Set<String>, taken: Set<String>): Nested {
        val io = ConfigIo(fs)
        val root = GuestPaths.normalize(cwd.value)
        val dirs = linkedSetOf<String>()
        for (file in touched) {
            val path = GuestPaths.normalize(file.value)
            if (!path.startsWith("$root/")) continue
            val parent = GuestPaths.parentOf(path)
            val segments = parent.removePrefix(root).split('/').filter { it.isNotEmpty() }
            for (i in segments.indices) dirs += GuestPaths.join(root, segments.subList(0, i + 1).joinToString("/"))
        }
        val names = taken.toMutableSet()
        val checkedNow = mutableListOf<String>()
        val found = mutableListOf<SkillDef>()
        for (dir in dirs) {
            val skillsDir = GuestPaths.join(dir, ".claude/skills")
            if (skillsDir in checked || skillsDir in checkedNow) continue
            checkedNow += skillsDir
            if (io.kind(skillsDir) != FileKind.Directory) continue
            val relative = dir.removePrefix("$root/")
            for (skill in SkillLoader.load(io, listOf(DefinitionRoot(ConfigSource.Project, skillsDir)))) {
                val name = if (skill.name in names) "$relative:${skill.name}" else skill.name
                if (name in names) {
                    io.notes += "嵌套的 skill `${skill.name}`（${skill.path.value}）与已有的同名，限定名 `$name` 也被占用，跳过"
                    continue
                }
                names += name
                found += if (name == skill.name) skill else skill.copy(name = name)
            }
        }
        return Nested(checkedNow, found, io.notes.toList())
    }
}

/** `--add-dir` 目录里的 skills，以及加载时的说明 */
class AddDirSkills(val skills: List<SkillDef>, val notes: List<String>)

/**
 * 只读 `--add-dir` 目录的 `.claude/skills`。`--bare` 只要这一块（CC 的 CLI reference：「Skills in a directory you pass with
 * `--add-dir` still load」）；完整档里 [ClaudeConfigLoader] 已经连同其它层一起读了。
 */
suspend fun addDirSkills(fs: WorkspaceFs, dirs: List<GuestPath>): AddDirSkills {
    val io = ConfigIo(fs)
    val roots = dirs.map { DefinitionRoot(ConfigSource.AddDir, GuestPaths.join(GuestPaths.normalize(it.value), ".claude/skills")) }
    return AddDirSkills(SkillLoader.load(io, roots), io.notes.toList())
}

/** 热更新重扫要看的目录：[skillDirs] 下的 `<名字>/SKILL.md`、[commandDirs] 下的命令文件（[commandDirs] 只在手动重扫时有） */
class SkillWatchRoots(val skillDirs: List<String>, val commandDirs: List<String>) {
    /** [path] 是否落在这几个目录之下（嵌套目录懒加载、托管、内置、插件的清单项都不在内） */
    fun covers(path: String, command: Boolean): Boolean = (if (command) commandDirs else skillDirs).any { path.startsWith("$it/") }
}

/**
 * 会话中途重扫的目录（CC skills 文档监视的：用户 `~/.claude/skills`、项目 `.claude/skills`（含仓库祖先，与启动时同一套）、`--add-dir` 的
 * `.claude/skills`）。[manual]（`/reload-skills`）再加上同样几处的 `commands` 目录；[bare] 只剩 `--add-dir` 的 skills。
 * `--restricted` 不读用户目录。纯路径计算，不读盘（祖先链要问 `.git` 在不在，经 [fs]）。
 */
suspend fun skillWatchRoots(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath, addDirs: List<GuestPath>, restricted: Boolean, manual: Boolean, bare: Boolean): SkillWatchRoots {
    val skillDirs = mutableListOf<String>()
    val commandDirs = mutableListOf<String>()
    addDirs.map { GuestPaths.normalize(it.value) }.distinct().forEach { dir ->
        skillDirs += GuestPaths.join(dir, ".claude/skills")
        if (manual && !bare) commandDirs += GuestPaths.join(dir, ".claude/commands")
    }
    if (!bare) {
        if (!restricted) {
            val userDir = GuestPaths.join(GuestPaths.normalize(home.value), ".claude")
            skillDirs += GuestPaths.join(userDir, "skills")
            if (manual) commandDirs += GuestPaths.join(userDir, "commands")
        }
        skillDirs += SkillDiscovery.ancestorSkillDirs(fs, cwd)
        if (manual) commandDirs += GuestPaths.join(GuestPaths.normalize(cwd.value), ".claude/commands")
    }
    return SkillWatchRoots(skillDirs, commandDirs)
}
