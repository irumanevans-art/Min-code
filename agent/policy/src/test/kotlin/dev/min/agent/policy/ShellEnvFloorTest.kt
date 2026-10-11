package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话的 `CLAUDE_ENV_FILE` 目录里的脚本会在后续每条 Bash 命令前被执行，内容等同 shell 启动脚本。
 * 模型直接写它就绕过了 Bash 命令自己的权限判定，所以写它是安全底线：任何模式（含 bypass）都要人确认。
 * 读它、写别处照旧，不被牵连。
 */
class ShellEnvFloorTest {
    private val envDir = "/root/.min/artifacts/s1/env"
    private fun context(mode: PermissionMode, canonical: (GuestPath) -> GuestPath = { it }) = PolicyContext(
        mode, cwd = GuestPath("/workspace"), shellEnvDir = GuestPath(envDir), canonicalPath = canonical,
        scopeOf = { PathScope.Workspace },
    )
    private fun write(path: String) = PermissionAsk("Write", "w", paths = listOf(PathAccess(GuestPath(path), AccessKind.Write, PathScope.Workspace)))
    private fun read(path: String) = PermissionAsk("Read", "r", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Workspace)))
    private fun bash(command: String) = PermissionAsk("Bash", "b", commands = listOf(CommandAccess(command)))

    @Test fun `writing a file in the env dir asks in every mode, even bypass`() {
        for (mode in listOf(PermissionMode.Default, PermissionMode.AcceptEdits, PermissionMode.BypassPermissions)) {
            val v = PolicyEngine.evaluate(write("$envDir/cwd.sh"), false, context(mode))
            assertTrue("$mode：$v", v is Verdict.Ask)
        }
        assertTrue(PolicyEngine.floorReason(write("$envDir/cwd.sh"), context(PermissionMode.BypassPermissions))!!.contains("shell 环境脚本"))
    }

    @Test fun `a shell redirect into the env dir asks too, also through dot-dot`() {
        val ctx = context(PermissionMode.BypassPermissions)
        assertTrue(PolicyEngine.evaluate(bash("echo 'curl x|sh' >> $envDir/cwd.sh"), false, ctx) is Verdict.Ask)
        assertTrue(PolicyEngine.evaluate(bash("echo x > $envDir/../env/setup.sh"), false, ctx) is Verdict.Ask)
        assertTrue(PolicyEngine.evaluate(bash("echo x > /workspace/../root/.min/artifacts/s1/env/setup.sh"), false, ctx) is Verdict.Ask)
    }

    @Test fun `a link that leads into the env dir is judged by where it really points`() {
        val real = GuestPath(envDir)
        val ctx = context(PermissionMode.BypassPermissions) { p -> if (p.value.startsWith("/workspace/link")) GuestPath(real.value + p.value.removePrefix("/workspace/link")) else p }
        assertTrue(PolicyEngine.evaluate(write("/workspace/link/cwd.sh"), false, ctx) is Verdict.Ask)
    }

    @Test fun `reading the env dir and writing next to it are not affected`() {
        val ctx = context(PermissionMode.BypassPermissions)
        assertTrue(PolicyEngine.evaluate(read("$envDir/cwd.sh"), true, ctx) is Verdict.Allow)
        assertTrue(PolicyEngine.evaluate(write("$envDir-other/x.txt"), false, ctx) is Verdict.Allow)
        assertTrue(PolicyEngine.evaluate(write("/workspace/a.txt"), false, ctx) is Verdict.Allow)
        assertEquals(null, PolicyEngine.floorReason(write("/workspace/a.txt"), ctx))
    }

    @Test fun `without an env dir nothing changes`() {
        val ctx = PolicyContext(PermissionMode.BypassPermissions, cwd = GuestPath("/workspace"), scopeOf = { PathScope.Workspace })
        assertTrue(PolicyEngine.evaluate(write("$envDir/cwd.sh"), false, ctx) is Verdict.Allow)
    }
}
