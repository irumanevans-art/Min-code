package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs

/**
 * Claude Code 配置的统一入口：settings 四层 + skills + commands + agents。
 * 用户把现成的 `~/.claude/` 与项目 `.claude/` 原样放进 rootfs，这里读出和 Claude Code 一样的结果。
 *
 * **只读 + 解析 + 合并**：不执行 hooks、不调用 skill、不连 MCP，也不往模型上下文里放任何东西
 * （模型什么时候、以什么形式看到它们是以后各子系统的事，见 agent/ext/README.md）。
 *
 * 项目目录一律取 [cwd]（CC 的「session's primary working directory」）：skills / commands / agents 与 settings.json 一样不往上找。
 */
object ClaudeConfigLoader {
    suspend fun load(
        fs: WorkspaceFs,
        cwd: GuestPath,
        home: GuestPath,
        managedPath: GuestPath = SettingsLoader.DEFAULT_MANAGED_PATH,
    ): ClaudeConfig {
        val settings = SettingsLoader.load(fs, cwd, home, managedPath)
        val io = ConfigIo(fs)
        val cwdPath = GuestPaths.normalize(cwd.value)
        val userDir = GuestPaths.join(GuestPaths.normalize(home.value), ".claude")
        val projectDir = GuestPaths.join(cwdPath, ".claude")
        // cwd 是家目录时「项目的 .claude」就是「用户的 .claude」：只算 user 层，免得同一份定义被当成两个来源互相覆盖
        val projectIsUser = projectDir == userDir
        val managedDir = GuestPaths.join(GuestPaths.parentOf(GuestPaths.normalize(managedPath.value)), ".claude")

        fun roots(sub: String, vararg order: ConfigSource): List<DefinitionRoot> = order.mapNotNull { source ->
            when (source) {
                ConfigSource.Managed -> DefinitionRoot(source, GuestPaths.join(managedDir, sub))
                ConfigSource.User -> DefinitionRoot(source, GuestPaths.join(userDir, sub))
                ConfigSource.Project -> if (projectIsUser) null else DefinitionRoot(source, GuestPaths.join(projectDir, sub))
                ConfigSource.Local, ConfigSource.Plugin, ConfigSource.Bundled -> null
            }
        }

        val skills = SkillLoader.load(io, roots("skills", ConfigSource.Managed, ConfigSource.User, ConfigSource.Project))
        val commands = CommandLoader.load(io, roots("commands", ConfigSource.User, ConfigSource.Project), skills.map { it.name }.toSet())
        val agents = AgentLoader.load(io, roots("agents", ConfigSource.Managed, ConfigSource.Project, ConfigSource.User))
        return ClaudeConfig(settings, skills, commands, agents, settings.notes + io.notes)
    }
}
