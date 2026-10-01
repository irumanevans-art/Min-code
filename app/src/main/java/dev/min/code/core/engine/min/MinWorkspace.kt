package dev.min.code.core.engine.min

import dev.min.agent.host.BindMount
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.host.ScopeRules
import dev.min.agent.spi.GuestPath
import me.rerere.workspace.WorkspaceShellContext
import java.io.File

/** 文件工具与 Bash 必须消费同一次挂载快照，否则开关变化会让 Read 与 cat 看见不同内容。 */
internal fun agentFileSystem(context: WorkspaceShellContext): RootfsFileSystem = RootfsFileSystem(
    root = context.linuxDir.toPath(),
    binds = agentMounts(context),
    scopes = ScopeRules(),
)

internal fun agentMounts(context: WorkspaceShellContext): List<BindMount> =
    listOf(BindMount(GuestPath("/workspace"), context.filesDir.toPath())) +
        context.bindMounts.filter { it.source.exists() }.map {
            BindMount(GuestPath(it.target.trimEnd('/').ifEmpty { "/" }), it.source.toPath())
        }

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun agentCommandContext(base: WorkspaceShellContext, cwd: GuestPath, script: String): WorkspaceShellContext =
    base.copy(
        // runner 的 cwd 按 /workspace 相对路径解释；显式 cd 保留 /root、/sdcard 等 guest cwd 的含义。
        cwd = "",
        command = "cd -- ${shellQuote(cwd.value)} && {\n$script\n}",
        env = emptyMap(),
        stdin = null,
        killOnExit = true,
    )

internal fun agentSessionsDir(filesDir: File): File = File(filesDir, "agent/sessions")
