package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.*
import org.junit.Test

class PermissionAlignmentTest {
    private fun context(mode: PermissionMode = PermissionMode.Default, kind: RuleKind = RuleKind.Deny, vararg rules: String) =
        PolicyContext(mode, rules.mapNotNull { PermissionRule.parse(kind, it, "project", GuestPath("/workspace")) })
    private fun bash(raw: String) = PermissionAsk("Bash", raw, commands = listOf(CommandAccess(raw)))
    private fun file(path: String, write: Boolean = false, tool: String = if (write) "Write" else "Read") =
        PermissionAsk(tool, path, paths = listOf(PathAccess(GuestPath(path), if (write) AccessKind.Write else AccessKind.Read, defaultScope(GuestPath(path)))))
    private fun eval(ask: PermissionAsk, context: PolicyContext = context(), readOnly: Boolean = false) =
        PolicyEngine.evaluate(ask, readOnly, context)
    private fun command(raw: String) = (ShellAnalyzer.analyze(raw) as ShellAnalysis.Parsed).commands.single()

    @Test fun `r20 bare xargs matches its inner rule but flags retain xargs semantics`() {
        val m = RuleMatcher(GuestPath("/workspace"), GuestPath("/root"))
        val rule = PermissionRule.parse(RuleKind.Allow, "Bash(grep *)", "p")!!
        assertTrue(m.matchesCommand(rule, command("xargs grep pattern")))
        assertFalse(m.matchesCommand(rule, command("xargs -n1 grep pattern")))
        assertEquals(Verdict.Ask, eval(bash("xargs -n1 grep pattern"), context(kind = RuleKind.Allow, rules = arrayOf("Bash(grep *)"))))
        assertEquals(Verdict.Ask, eval(bash("xargs rm -rf build"), context(PermissionMode.BypassPermissions)))
        assertEquals(Verdict.Ask, eval(bash("xargs bash -c 'rm -rf build'"), context(PermissionMode.BypassPermissions)))
        assertTrue(eval(bash("bash -c 'rm -rf build/'"), context(rules = arrayOf("Bash(rm *)"))) is Verdict.Deny)
    }

}
