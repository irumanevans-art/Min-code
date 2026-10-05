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

    @Test fun `r26 deny and ask single-directory patterns search only within cwd`() {
        for (kind in listOf(RuleKind.Deny, RuleKind.Ask)) {
            val c = context(kind = kind, rules = arrayOf("Read(secrets/**)"))
            val result = eval(file("/workspace/vendor/pkg/secrets/key"), c, true)
            if (kind == RuleKind.Deny) assertTrue(result is Verdict.Deny) else assertEquals(Verdict.Ask, result)
            assertTrue(eval(file("/other/secrets/key"), c, true) is Verdict.Allow)
        }
        val m = RuleMatcher(GuestPath("/workspace"), GuestPath("/root"))
        val allow = PermissionRule.parse(RuleKind.Allow, "Edit(secrets/**)", "project")!!
        assertFalse(m.matchesPath(allow, file("/workspace/vendor/secrets/key", true).paths.single()))
        assertTrue(m.matchesPath(allow, file("/workspace/secrets", true).paths.single()))
    }

    @Test fun `r48 dot segments and parent segments normalize at their anchors`() {
        for (rule in listOf("Read(.env)", "Read(./.env)", "Read(dir/../.env)", "Read(/dir/../.env)")) {
            assertTrue(rule, eval(file("/workspace/a/../.env"), context(rules = arrayOf(rule)), true) is Verdict.Deny)
        }
        for (rule in listOf("Read(.env)", "Read(./.env)", "Read(dir/../.env)")) {
            assertTrue(rule, eval(file("/workspace/nested/.env"), context(rules = arrayOf(rule)), true) is Verdict.Deny)
        }
        assertTrue(eval(file("/outside.env"), context(rules = arrayOf("Read(../outside.env)")), true) is Verdict.Deny)
        assertTrue(eval(file("/outside.env"), context(rules = arrayOf("Read(~/../outside.env)")), true) is Verdict.Deny)
        assertTrue(eval(file("/outside.env"), context(rules = arrayOf("Read(.env)")), true) is Verdict.Allow)
    }

    @Test fun `r27 read deny blocks Edit and Write but other rule kinds do not grant writes`() {
        for (tool in listOf("Edit", "Write")) assertTrue(eval(file("/workspace/.env", true, tool), context(rules = arrayOf("Read(.env)"))) is Verdict.Deny)
        assertTrue(eval(file("/workspace/.env", true), context(rules = arrayOf("Read"))) is Verdict.Deny)
        assertEquals(Verdict.Ask, eval(file("/workspace/.env", true), context(kind = RuleKind.Allow, rules = arrayOf("Read(.env)"))))
        assertEquals(Verdict.Ask, eval(file("/workspace/.env", true), context(kind = RuleKind.Ask, rules = arrayOf("Read(.env)"))))
        assertTrue(eval(file("/workspace/.env"), context(rules = arrayOf("Edit(.env)")), true) is Verdict.Allow)
        assertEquals(Verdict.Ask, eval(file("/workspace/.env", true, "NotebookEdit"), context(rules = arrayOf("Read(.env)"))))
    }

    @Test fun `r29 search roots include the denied directory itself`() {
        for (tool in listOf("Grep", "Glob")) for (path in listOf("/workspace/secrets", "/workspace/vendor/secrets")) {
            assertTrue(eval(file(path, tool = tool), context(rules = arrayOf("Read(secrets/**)")), true) is Verdict.Deny)
        }
    }

    @Test fun `r38 allow MCP globs require a literal server but deny and ask can span servers`() {
        for (pattern in listOf("mcp__*", "mcp__git*__get_*", "mcp__?__*")) assertNull(PermissionRule.parse(RuleKind.Allow, pattern, "p"))
        val ask = PermissionAsk("mcp__github__get_issue", "mcp")
        assertTrue(eval(ask, context(kind = RuleKind.Allow, rules = arrayOf("mcp__github__get_*"))) is Verdict.Allow)
        assertTrue(eval(ask, context(rules = arrayOf("mcp__*"))) is Verdict.Deny)
        assertTrue(eval(ask, context(rules = arrayOf("mcp__githu?__get_*"))) is Verdict.Deny)
        assertEquals(Verdict.Ask, eval(ask, context(PermissionMode.BypassPermissions, RuleKind.Ask, "mcp__githu?__get_*")))
        assertEquals(Verdict.Ask, eval(ask, context(PermissionMode.BypassPermissions, RuleKind.Ask, "mcp__*")))
        assertTrue(PolicyEngine.matchesCondition("mcp__*", ask, context()))
    }

    @Test fun `r07 and r09 command wildcard table distinguishes a single trailing wildcard`() {
        val m = RuleMatcher(GuestPath("/workspace"), GuestPath("/root"))
        for ((pattern, matches, misses) in listOf(
            Triple("git log * main", "git log --oneline main", "git log main"),
            Triple("git * main", "git push origin main", "git log"),
            Triple("* --version", "bash -c 'echo hi' --version", "node -v"),
            Triple("* --help *", "npm --help x", "npm --help"),
            Triple("ls *", "ls", "lsof"),
            Triple("npm run *", "npm run", "npm install"),
            Triple("ls:*", "ls -la", "lsof"),
        )) {
            val rule = PermissionRule.parse(RuleKind.Allow, "Bash($pattern)", "p")!!
            assertTrue(pattern, m.matchesCommand(rule, command(matches)))
            assertFalse(pattern, m.matchesCommand(rule, command(misses)))
        }
        assertTrue(m.matchesCommand(PermissionRule.parse(RuleKind.Allow, "Bash(ls*)", "p")!!, command("lsof")))
        for ((pattern, raw) in listOf(
            "npm run build" to "npm run build", "npm run *" to "npm run test --watch",
            "git log * main" to "git log -5 main", "git log * main" to "git log --output=file main",
            "git * main" to "git merge main", "git * main" to "git -c core.fsmonitor=script diff main",
            "* --version" to "node --version", "ls *" to "ls -la", "ls*" to "ls -la",
        )) assertTrue(raw, m.matchesCommand(PermissionRule.parse(RuleKind.Allow, "Bash($pattern)", "p")!!, command(raw)))
        assertFalse(m.matchesCommand(PermissionRule.parse(RuleKind.Allow, "Bash(npm run build)", "p")!!, command("npm run build --watch")))
        assertFalse(m.matchesCommand(PermissionRule.parse(RuleKind.Allow, "Bash(git log * main)", "p")!!, command("git push origin main")))
        // 规则没命中与最终要不要审批分开：git log 的只读基线依然生效。
        assertTrue(eval(bash("git log main"), context(kind = RuleKind.Allow, rules = arrayOf("Bash(git log * main)"))) is Verdict.Allow)
    }

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

    @Test fun `r28 Write path rules are not consulted but bare Write tool rules still apply`() {
        assertEquals(Verdict.Ask, eval(file("/workspace/a", true), context(kind = RuleKind.Allow, rules = arrayOf("Write(a)"))))
        assertTrue(eval(file("/workspace/a", true), context(kind = RuleKind.Allow, rules = arrayOf("Write"))) is Verdict.Allow)
    }

}
