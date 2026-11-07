package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重定向写文件的两个漏洞：
 * 1. 一段里只有赋值 / 重定向、没有命令词（`> ~/.bashrc`、`ls; > /sdcard/x`）：重定向照样会建 / 截断文件，不能被丢掉；
 * 2. 命令里有子 shell / 命令替换（看不透）时，字面的 `>` / `>>` 目标仍要对 Edit 拒绝规则与底线（CC 2.1.309）。
 */
class RedirectWritePolicyTest {
    private fun bash(raw: String) = PermissionAsk("Bash", "跑命令", commands = listOf(CommandAccess(raw)))
    private fun ctx(mode: PermissionMode, vararg deny: String) =
        PolicyContext(mode, deny.map { PermissionRule.parse(RuleKind.Deny, it, "user")!! })
    private fun v(raw: String, context: PolicyContext) = PolicyEngine.evaluate(bash(raw), false, context)

    private val bareRedirects = listOf("> ~/.bashrc", "X=1 > .git/hooks/pre-commit", "ls; > /sdcard/x")

    @Test fun `segments without a command word keep their redirects`() {
        val cmds = (ShellAnalyzer.analyze("ls; > /sdcard/x") as ShellAnalysis.Parsed).commands
        assertEquals(listOf(Redirect(">", "/sdcard/x")), cmds.flatMap { it.redirects })
        // 只有赋值的一行仍什么都不执行
        assertTrue(PolicyEngine.evaluate(bash("X=1"), false, ctx(PermissionMode.Default)) is Verdict.Allow)
    }

    @Test fun `bare redirect segments are not read-only`() {
        for (raw in bareRedirects) assertEquals(raw, Verdict.Ask, v(raw, ctx(PermissionMode.Default)))
    }

    @Test fun `bare redirect segments hit the floors even in bypass`() {
        for (raw in bareRedirects) {
            assertNotNull(raw, PolicyEngine.floorReason(bash(raw), ctx(PermissionMode.BypassPermissions)))
            assertEquals(raw, Verdict.Ask, v(raw, ctx(PermissionMode.BypassPermissions)))
        }
        assertNotNull(PolicyEngine.floorReason(bash("> /dev/sda"), ctx(PermissionMode.BypassPermissions)))
    }

    @Test fun `Edit deny blocks bare redirect segments`() {
        for (raw in listOf("> /etc/hosts", "ls; > /etc/hosts", "X=1 >> /etc/hosts")) {
            assertTrue(raw, v(raw, ctx(PermissionMode.BypassPermissions, "Edit(//etc/**)")) is Verdict.Deny)
        }
    }

    @Test fun `suggestRule tolerates bare redirect segments`() {
        assertNull(PolicyEngine.suggestRule(bash("> out.txt"), ctx(PermissionMode.Default)))
    }

    @Test fun `Edit deny blocks literal redirect targets in untrusted commands`() {
        val deny = ctx(PermissionMode.BypassPermissions, "Edit(//etc/**)")
        for (raw in listOf(
            "(true); echo x > /etc/hosts", "echo \$(date) > /etc/hosts", "echo \"\$(date)\" >> '/etc/hosts'",
            "echo `id` 2> /etc/x", "(cd /tmp && echo x >/etc/hosts)", "echo \$(date) &> \"/etc/a b\"",
        )) assertTrue(raw, v(raw, deny) is Verdict.Deny)
        // 对照：引号里的 `>` 不是重定向；目标含变量的不提取；没有重定向的照常放行
        for (raw in listOf("echo \"\$(date) > /etc/hosts\"", "echo \$(date) > \$DIR/hosts", "echo \$(date)", "echo \$(date) 2>&1")) {
            assertTrue(raw, v(raw, deny) is Verdict.Allow)
        }
    }

    @Test fun `floors fire on literal redirect targets in untrusted commands`() {
        val bypass = ctx(PermissionMode.BypassPermissions)
        for (raw in listOf(
            "echo \$(date) >> ~/.bashrc", "(true); echo x > /sdcard/a", "echo \$(id) > .git/hooks/pre-commit",
            "echo \$(id) > /workspace/.claude/settings.json",
        )) {
            assertNotNull(raw, PolicyEngine.floorReason(bash(raw), bypass))
            assertEquals(raw, Verdict.Ask, v(raw, bypass))
        }
        // here-doc 正文里以 > 开头的行不是重定向
        assertNull(PolicyEngine.floorReason(bash("cat <<EOF\n> ~/.bashrc\nEOF"), bypass))
    }

    @Test fun `extracting untrusted targets never loosens a decision`() {
        // 看不透的命令在默认模式照旧问人，即便目标在工作区里
        assertEquals(Verdict.Ask, v("echo \$(date) > /workspace/a.txt", ctx(PermissionMode.Default)))
        assertEquals(Verdict.Ask, v("echo \$(date) > /workspace/a.txt", ctx(PermissionMode.AcceptEdits)))
    }
}
