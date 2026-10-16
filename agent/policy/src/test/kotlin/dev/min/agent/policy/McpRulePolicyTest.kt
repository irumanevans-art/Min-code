package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.McpNames
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 规则按服务器的规范化名匹配（CC 2.1.299：`mcp__a` 不再放行服务器 `a__b` / `a_`） */
class McpRulePolicyTest {
    private fun call(tool: String, server: String?) = PermissionAsk(tool, "调用 MCP 工具", mcpServer = server)
    private fun rule(kind: RuleKind, text: String) = PermissionRule.parse(kind, text, "user")!!
    private fun v(ask: PermissionAsk, vararg rules: PermissionRule) =
        PolicyEngine.evaluate(ask, readOnly = false, context = PolicyContext(PermissionMode.Default, rules.toList()))

    @Test fun `a server allow rule does not reach servers whose names merely start with it`() {
        val allowA = rule(RuleKind.Allow, "mcp__a")
        assertTrue(v(call("mcp__a__tool", "a"), allowA) is Verdict.Allow)
        assertEquals(Verdict.Ask, v(call("mcp__a__b__tool", "a__b"), allowA))
        assertEquals(Verdict.Ask, v(call("mcp__a___x", "a_"), allowA))
        assertEquals(Verdict.Ask, v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Allow, "mcp__a__*")))
        // 服务器名含 __ 时写全名
        assertTrue(v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Allow, "mcp__a__b__*")) is Verdict.Allow)
        assertTrue(v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Allow, "mcp__a__b")) is Verdict.Allow)
    }

    @Test fun `deny and ask rules for a server with a double underscore apply`() {
        assertTrue(v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Deny, "mcp__a__b")) is Verdict.Deny)
        assertTrue(v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Deny, "mcp__a__b__*")) is Verdict.Deny)
        assertEquals(Verdict.Ask, v(call("mcp__a__b__tool", "a__b"), rule(RuleKind.Ask, "mcp__a__b"), rule(RuleKind.Allow, "mcp__a__b")))
        assertTrue(PolicyEngine.deniesWholeTool("mcp__a__b__tool", PolicyContext(PermissionMode.Default, listOf(rule(RuleKind.Deny, "mcp__a__b"))), "a__b"))
        // 只拦点名的那台
        assertFalse(PolicyEngine.deniesWholeTool("mcp__a__b__tool", PolicyContext(PermissionMode.Default, listOf(rule(RuleKind.Deny, "mcp__a"))), "a__b"))
    }

    @Test fun `exact tool rules and the all-servers wildcard are unchanged`() {
        assertTrue(v(call("mcp__github__create_issue", "github"), rule(RuleKind.Allow, "mcp__github__create_issue")) is Verdict.Allow)
        assertEquals(Verdict.Ask, v(call("mcp__github__delete_repo", "github"), rule(RuleKind.Allow, "mcp__github__create_issue")))
        assertTrue(v(call("mcp__x__y", "x"), rule(RuleKind.Deny, "mcp__*")) is Verdict.Deny)
        assertTrue(v(call("mcp__x__y", "x"), rule(RuleKind.Deny, "*")) is Verdict.Deny)
        assertTrue(v(call("mcp__github__get_issue", "github"), rule(RuleKind.Allow, "mcp__github__get_*")) is Verdict.Allow)
    }

    @Test fun `without the server name deny keeps the prefix and allow needs the exact name`() {
        assertTrue(v(call("mcp__a__b__tool", null), rule(RuleKind.Deny, "mcp__a")) is Verdict.Deny)
        assertEquals(Verdict.Ask, v(call("mcp__a__tool", null), rule(RuleKind.Allow, "mcp__a")))
        assertTrue(v(call("mcp__a__tool", null), rule(RuleKind.Allow, "mcp__a__tool")) is Verdict.Allow)
    }

    @Test fun `a long server name still matches after its full name is truncated and hashed`() {
        val server = "a_really_long_server_name_from_some_enterprise_directory"
        val full = McpNames.assign(listOf(server to "search")).values.single()
        assertFalse("全名应已截断：$full", full.startsWith("mcp__${server}__"))
        assertTrue(v(call(full, McpNames.sanitize(server)), rule(RuleKind.Deny, "mcp__$server")) is Verdict.Deny)
        assertTrue(v(call(full, McpNames.sanitize(server)), rule(RuleKind.Allow, "mcp__$server")) is Verdict.Allow)
    }
}
