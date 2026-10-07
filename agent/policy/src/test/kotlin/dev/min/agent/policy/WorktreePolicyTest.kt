package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import org.junit.Assert.*
import org.junit.Test

class WorktreePolicyTest {
    private val root = GuestPath("/project/.claude/worktrees/w")
    private val context = PolicyContext(PermissionMode.AcceptEdits, cwd = root, worktreeRoot = root, scopeOf = { if (it.value.startsWith(root.value + "/")) PathScope.Workspace else PathScope.Rootfs })
    private fun write(path: String) = PermissionAsk("Write", "write", paths = listOf(PathAccess(GuestPath(path), AccessKind.Write, PathScope.Workspace)))
    @Test fun `only the active verified worktree prefix is exempt from config protection`() {
        val ask = write("${root.value}/ordinary.txt")
        assertNull(PolicyEngine.floorReason(ask, context))
        assertTrue(PolicyEngine.evaluate(ask, false, context) is Verdict.Allow)
        assertEquals(Verdict.Ask, PolicyEngine.evaluate(ask, false, context.copy(worktreeRoot = null)))
        for (suffix in listOf(".git/config", ".claude/settings.json", ".bashrc", "../other/file")) {
            assertEquals(suffix, Verdict.Ask, PolicyEngine.evaluate(write("${root.value}/$suffix"), false, context.copy(mode = PermissionMode.BypassPermissions)))
        }
        val command = PermissionAsk("Bash", "write", commands = listOf(CommandAccess("printf content > ordinary.txt")))
        assertNull(PolicyEngine.floorReason(command, context))
        assertTrue(PolicyEngine.evaluate(command, false, context.copy(mode = PermissionMode.BypassPermissions)) is Verdict.Allow)
        val device = context.copy(scopeOf = { PathScope.Device }, mode = PermissionMode.BypassPermissions)
        assertEquals(Verdict.Ask, PolicyEngine.evaluate(ask, false, device))
        val credential = context.copy(credentialPaths = setOf(GuestPath("${root.value}/ordinary.txt")), mode = PermissionMode.BypassPermissions)
        assertTrue(PolicyEngine.evaluate(ask, false, credential) is Verdict.Deny)
    }
    @Test fun `worktree tool permission remains nonreadonly and default asks unless explicit rule`() {
        for (tool in listOf("EnterWorktree", "ExitWorktree")) {
            val ask = PermissionAsk(tool, "worktree")
            assertEquals(Verdict.Ask, PolicyEngine.evaluate(ask, false, PolicyContext(PermissionMode.Default)))
            val rule = PermissionRule.parse(RuleKind.Allow, tool, "user")!!
            assertTrue(PolicyEngine.matchesCondition(tool, ask, context))
            assertTrue(PolicyEngine.evaluate(ask, false, context.copy(rules = listOf(rule))) is Verdict.Allow)
            assertTrue(PolicyEngine.evaluate(ask, false, context.copy(mode = PermissionMode.DontAsk)) is Verdict.Deny)
        }
    }
}
