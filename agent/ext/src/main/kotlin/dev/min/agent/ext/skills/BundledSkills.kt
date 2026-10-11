package dev.min.agent.ext.skills

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.config.SkillLoader

/**
 * 随包自带的 skill（CC 的 bundled skills）。正文是本模块资源里的 `bundled-skills/<名字>/SKILL.md`，
 * 不在用户的文件系统上：路径用 [PATH_PREFIX] 开头的假路径表示，[SkillFiles.readBody] 认得它、从资源读。
 *
 * 优先级（照 CC 文档「你的 skill 替换内置的」）：同名时磁盘上的 managed / user / project、插件 skill 胜出，
 * 内置的只在没有同名时进清单；由 [dev.min.agent.kernel.SkillSnapshots] 合并，这里不做覆盖。
 * 关闭：settings 里 `disableBundledSkills: true`；`--bare` / `--safe-mode` 整体不发现 skills，内置的也不进（见内核）。
 *
 * [NAMES] 是清单顺序，也是资源目录的白名单：新增一个内置 skill = 加资源文件 + 名字加进这里。
 */
object BundledSkills {
    /** 内置 skill 的假路径前缀（不是文件系统路径，只在这个模块里认）：`/.min-bundled-skills/loop/SKILL.md`；GuestPath 要求绝对路径 */
    const val PATH_PREFIX = "/.min-bundled-skills/"

    /** 内置 skill 名，按清单顺序 */
    val NAMES: List<String> = listOf("loop", "code-review", "security-review", "simplify", "batch")

    fun path(name: String): String = "$PATH_PREFIX$name/SKILL.md"

    fun isBundled(path: String): Boolean = path.startsWith(PATH_PREFIX)

    /** 假路径 → 名字；不是内置的路径或名字不在白名单里返回 null（不去资源目录里乱找） */
    private fun nameOf(path: String): String? {
        if (!isBundled(path)) return null
        val name = path.removePrefix(PATH_PREFIX).substringBefore('/')
        return name.takeIf { it in NAMES && path == path(it) }
    }

    /** 资源里的 SKILL.md 全文；没有这个名字返回 null */
    fun readText(path: String): String? {
        val name = nameOf(path) ?: return null
        val stream = BundledSkills::class.java.getResourceAsStream("/bundled-skills/$name/SKILL.md") ?: return null
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * `/loop` 用的两处 loop.md 原文：项目 `.claude/loop.md` 与用户 `~/.claude/loop.md`（home 为 null 时没有用户层）。
     * 选哪个、截断多少由内核的 `LoopCommand` 定；这里只读文件，读不到是 null。
     */
    suspend fun loopMdTexts(fs: dev.min.agent.spi.WorkspaceFs, cwd: dev.min.agent.spi.GuestPath, home: dev.min.agent.spi.GuestPath?): Pair<String?, String?> {
        val io = dev.min.agent.ext.config.ConfigIo(fs)
        val project = io.readText(dev.min.agent.ext.config.GuestPaths.join(dev.min.agent.ext.config.GuestPaths.normalize(cwd.value), ".claude/loop.md"))
        val user = home?.let { io.readText(dev.min.agent.ext.config.GuestPaths.join(dev.min.agent.ext.config.GuestPaths.normalize(it.value), ".claude/loop.md")) }
        return project to user
    }

    /** 全部内置 skill 的定义（清单用）；资源缺了某个的话跳过它，不影响其余 */
    fun defs(): List<SkillDef> = NAMES.mapNotNull { name ->
        val p = path(name)
        val text = readText(p) ?: return@mapNotNull null
        SkillLoader.skillFromText(text, ConfigSource.Bundled, p, name, mutableListOf())
    }
}
