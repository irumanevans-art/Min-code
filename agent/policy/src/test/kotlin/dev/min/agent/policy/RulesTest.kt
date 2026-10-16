package dev.min.agent.policy

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val m = RuleMatcher(cwd = GuestPath("/workspace/app"), home = GuestPath("/root"))

    private fun rule(text: String, baseDir: String? = null) =
        PermissionRule.parse(RuleKind.Allow, text, "test", baseDir?.let(::GuestPath))!!

    private fun cmd(script: String) = (ShellAnalyzer.analyze(script) as ShellAnalysis.Parsed).commands.single()

    private fun read(path: String) = PathAccess(GuestPath(path), AccessKind.Read, PathScope.Workspace)
    private fun write(path: String) = PathAccess(GuestPath(path), AccessKind.Write, PathScope.Workspace)

    @Test
    fun `parses tool and specifier`() {
        assertEquals(PermissionRule(RuleKind.Deny, "Bash", "git push *", "user"), PermissionRule.parse(RuleKind.Deny, " Bash(git push *) ", "user"))
        assertEquals(PermissionRule(RuleKind.Allow, "WebFetch", null, "user"), PermissionRule.parse(RuleKind.Allow, "WebFetch", "user"))
        assertEquals("mcp__github__get_issue", PermissionRule.parse(RuleKind.Allow, "mcp__github__get_issue", "u")!!.raw)
        assertEquals("Bash(npm run *)", rule("Bash(npm run *)").raw)
        assertNull(PermissionRule.parse(RuleKind.Allow, "Bash()", "u"))
        assertNull(PermissionRule.parse(RuleKind.Allow, "Bash(x", "u"))
        assertNull(PermissionRule.parse(RuleKind.Allow, "", "u"))
        assertNull(PermissionRule.parse(RuleKind.Allow, "two words", "u"))
    }

    @Test
    fun `whole-tool rules and tool families`() {
        assertTrue(m.matchesTool(rule("Bash"), "Bash"))
        assertFalse(m.matchesTool(rule("Bash(ls)"), "Bash"))
        assertFalse(m.matchesTool(rule("Bash"), "BashOutput"))
        assertTrue(m.matchesTool(rule("Read"), "Grep"))
        assertTrue(m.matchesTool(rule("Read"), "Glob"))
        assertTrue(m.matchesTool(rule("Edit"), "Write"))
        assertFalse(m.matchesTool(rule("Write"), "Edit"))
        assertFalse(m.matchesTool(rule("Grep"), "Read"))
    }

    @Test
    fun `mcp rules match a whole server or one tool`() {
        assertTrue(m.matchesTool(rule("mcp__github"), "mcp__github__get_issue", "github"))
        assertTrue(m.matchesTool(rule("mcp__github__*"), "mcp__github__get_issue", "github"))
        assertTrue(m.matchesTool(rule("mcp__github__get_issue"), "mcp__github__get_issue", "github"))
        assertFalse(m.matchesTool(rule("mcp__github__get_issue"), "mcp__github__create_issue", "github"))
        assertFalse(m.matchesTool(rule("mcp__git"), "mcp__github__get_issue", "github"))
    }

    @Test
    fun `bash prefix rules respect word boundaries`() {
        val ls = rule("Bash(ls *)")
        assertTrue(m.matchesCommand(ls, cmd("ls")))
        assertTrue(m.matchesCommand(ls, cmd("ls -la /etc")))
        assertFalse(m.matchesCommand(ls, cmd("lsof")))
        val legacy = rule("Bash(npm run test:*)")
        assertTrue(m.matchesCommand(legacy, cmd("npm run test")))
        assertTrue(m.matchesCommand(legacy, cmd("npm run test --watch")))
        assertFalse(m.matchesCommand(legacy, cmd("npm run testx")))
        assertFalse(m.matchesCommand(rule("Bash(git commit *)"), cmd("git push")))
    }

    @Test
    fun `bash exact and inner wildcards`() {
        assertTrue(m.matchesCommand(rule("Bash(npm test)"), cmd("npm test")))
        assertFalse(m.matchesCommand(rule("Bash(npm test)"), cmd("npm test --x")))
        assertTrue(m.matchesCommand(rule("Bash(* --version)"), cmd("node --version")))
        assertTrue(m.matchesCommand(rule("Bash(git * main)"), cmd("git checkout main")))
        // 正则元字符按字面量
        assertFalse(m.matchesCommand(rule("Bash(a.c)"), cmd("abc")))
        assertTrue(m.matchesCommand(rule("Bash(a.c)"), cmd("a.c")))
    }

    @Test
    fun `bash rules see through wrappers but also match the original form`() {
        assertTrue(m.matchesCommand(rule("Bash(npm test *)"), cmd("timeout 60 npm test")))
        assertTrue(m.matchesCommand(rule("Bash(timeout *)"), cmd("timeout 60 npm test")))
        assertTrue(m.matchesCommand(rule("Bash(npm  test   *)"), cmd("npm test")))
        assertFalse(m.matchesCommand(rule("Read(npm *)"), cmd("npm test")))
    }

    @Test
    fun `path rules anchor the way Claude Code documents`() {
        assertTrue(m.matchesPath(rule("Edit(//etc/**)"), write("/etc/hosts")))
        assertTrue(m.matchesPath(rule("Edit(~/notes/*)"), write("/root/notes/a.md")))
        assertTrue(m.matchesPath(rule("Edit(/src/**)", baseDir = "/workspace"), write("/workspace/src/a/b.kt")))
        assertFalse(m.matchesPath(rule("Edit(/src/**)", baseDir = "/workspace"), write("/workspace/app/src/a.kt")))
        // 没有 baseDir 时 / 相对 cwd
        assertTrue(m.matchesPath(rule("Edit(/src/**)"), write("/workspace/app/src/a.kt")))
        assertTrue(m.matchesPath(rule("Edit(src/*.kt)"), write("/workspace/app/src/a.kt")))
        assertFalse(m.matchesPath(rule("Edit(src/*.kt)"), write("/workspace/app/src/x/a.kt")))
        // 不含斜杠：任意深度的文件名
        assertTrue(m.matchesPath(rule("Read(.env)"), read("/workspace/app/config/.env")))
        assertFalse(m.matchesPath(rule("Read(*.pem)"), read("/root/keys/id.pem")))
        assertFalse(m.matchesPath(rule("Read(.env)"), read("/workspace/app/.env.example")))
        assertTrue(m.matchesPath(rule("Read(//workspace/**/secret?.txt)"), read("/workspace/a/b/secret1.txt")))
        assertTrue(m.matchesPath(rule("Read(//workspace/**/secret?.txt)"), read("/workspace/secret1.txt")))
    }

    @Test
    fun `read rules do not cover writes and edit rules do not cover reads`() {
        assertFalse(m.matchesPath(rule("Read(//etc/**)"), write("/etc/hosts")))
        assertFalse(m.matchesPath(rule("Edit(//etc/**)"), read("/etc/hosts")))
        assertFalse(m.matchesPath(rule("Write(//etc/**)"), write("/etc/hosts")))
        assertFalse(m.matchesPath(rule("Bash(//etc/**)"), write("/etc/hosts")))
        assertFalse(m.matchesPath(rule("Edit"), write("/etc/hosts")))
    }

    @Test
    fun `negated path rules match like their positive form`() {
        val neg = rule("Read(!sample.env)")
        assertTrue(m.isNegation(neg))
        assertTrue(m.matchesPath(neg, read("/workspace/app/sample.env")))
        assertFalse(m.isNegation(rule("Read(.env)")))
    }

    @Test
    fun `domain rules`() {
        assertTrue(m.matchesDomain(rule("WebFetch(domain:example.com)"), "Example.COM"))
        assertFalse(m.matchesDomain(rule("WebFetch(domain:example.com)"), "api.example.com"))
        assertTrue(m.matchesDomain(rule("WebFetch(domain:*.example.com)"), "a.b.example.com"))
        assertFalse(m.matchesDomain(rule("WebFetch(domain:*.example.com)"), "example.com"))
        assertFalse(m.matchesDomain(rule("WebFetch(domain:*.example.com)"), "evilexample.com"))
        assertTrue(m.matchesDomain(rule("WebFetch(domain:*)"), "anything.org"))
        assertFalse(m.matchesDomain(rule("WebFetch(example.com)"), "example.com"))
        assertFalse(m.matchesDomain(rule("WebSearch(domain:example.com)"), "example.com"))
    }
}
