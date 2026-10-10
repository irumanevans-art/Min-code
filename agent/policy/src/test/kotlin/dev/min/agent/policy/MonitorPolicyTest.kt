package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monitor 的权限（CC：命令源「用与 Bash 同一套权限规则」，Auto 下点名 Monitor 的 allow 规则暂停，
 * WebSocket 源每次打开都要批准、Auto 下交分类器）。工具层只声明命令 / 地址，判定全在这里。
 */
class MonitorPolicyTest {
    private fun monitor(command: String) = PermissionAsk("Monitor", "盯", commands = listOf(CommandAccess(command)))
    private fun bash(command: String) = PermissionAsk("Bash", "跑", commands = listOf(CommandAccess(command)))
    private val socket = PermissionAsk("Monitor", "打开 WebSocket：wss://feed.example.com/x")

    private fun ctx(mode: PermissionMode = PermissionMode.Default, vararg rules: Pair<RuleKind, String>, credentials: Set<GuestPath> = emptySet()) =
        PolicyContext(mode, rules.map { (k, t) -> PermissionRule.parse(k, t, "user")!! }, credentialPaths = credentials)

    private fun v(ask: PermissionAsk, c: PolicyContext) = PolicyEngine.evaluate(ask, false, c)

    // ---- 命令源：与 Bash 同一套规则 ----

    @Test
    fun `Bash 的 allow 规则对 Monitor 的命令同样生效`() {
        val c = ctx(rules = arrayOf(RuleKind.Allow to "Bash(npm run watch *)"))
        assertTrue(v(monitor("npm run watch tests"), c) is Verdict.Allow)
        assertEquals("别的命令仍要问", Verdict.Ask, v(monitor("npm run deploy"), c))
        assertTrue("Bash 本身也一样", v(bash("npm run watch tests"), c) is Verdict.Allow)
    }

    @Test
    fun `Bash 的 deny 规则对 Monitor 的命令同样生效`() {
        val c = ctx(rules = arrayOf(RuleKind.Deny to "Bash(curl *)", RuleKind.Allow to "Bash"))
        assertTrue(v(monitor("curl https://x.example/health"), c) is Verdict.Deny)
        assertTrue("复合命令里有一段命中也拒", v(monitor("sleep 1 && curl https://x"), c) is Verdict.Deny)
    }

    @Test
    fun `Bash 的 ask 规则对 Monitor 的命令同样生效`() {
        val c = ctx(PermissionMode.BypassPermissions, RuleKind.Ask to "Bash(git push *)")
        assertEquals(Verdict.Ask, v(monitor("git push origin main"), c))
    }

    @Test
    fun `只读命令与 Bash 一样在默认模式免批，改文件的要问`() {
        assertTrue(v(monitor("tail -f app.log"), ctx()) is Verdict.Allow)
        assertEquals(Verdict.Ask, v(monitor("npm install"), ctx()))
        assertEquals(Verdict.Ask, v(monitor("tail -f app.log > /tmp/copy.log"), ctx()))
    }

    @Test
    fun `dontAsk 下没被预先允许的命令源被拒，bypass 放行`() {
        assertTrue(v(monitor("npm run watch"), ctx(PermissionMode.DontAsk)) is Verdict.Deny)
        assertTrue(v(monitor("npm run watch"), ctx(PermissionMode.BypassPermissions)) is Verdict.Allow)
    }

    // ---- Auto：点名 Monitor 的 allow 规则暂停 ----

    @Test
    fun `Auto 下点名 Monitor 的 allow 规则被暂停交分类器，其它模式照常生效`() {
        for (rule in listOf("Monitor", "Monitor(*)", "Monitor(npm run watch)")) {
            val auto = ctx(PermissionMode.Auto, RuleKind.Allow to rule)
            assertEquals("$rule 在 Auto 下不放行", Verdict.Ask, v(monitor("npm run watch"), auto))
            if (rule == "Monitor") {
                assertTrue("默认模式下点名整个 Monitor 的 allow 是用户的明确放行", v(monitor("npm run watch"), ctx(PermissionMode.Default, RuleKind.Allow to rule)) is Verdict.Allow)
            }
        }
    }

    @Test
    fun `Auto 下窄的 Bash 命令规则仍对 Monitor 生效，宽的（通配或解释器）被暂停`() {
        assertTrue(v(monitor("npm test"), ctx(PermissionMode.Auto, RuleKind.Allow to "Bash(npm test)")) is Verdict.Allow)
        assertEquals(Verdict.Ask, v(monitor("python3 x.py"), ctx(PermissionMode.Auto, RuleKind.Allow to "Bash(python3 *)")))
    }

    @Test
    fun `classifyAllShell 下 Monitor 的命令连只读也送审`() {
        val c = PolicyContext(PermissionMode.Auto, classifyAllShell = true)
        assertEquals(Verdict.Ask, v(monitor("tail -f app.log"), c))
    }

    // ---- 凭证 ----

    @Test
    fun `Monitor 的命令碰凭证目录与 Bash 一样被拒（含只给上层目录的递归读取）`() {
        val credentials = setOf(GuestPath("/root/.min-agent/mcp-oauth.json*"))
        for (command in listOf("cat ~/.min-agent/mcp-oauth.json", "rg token ~", "tail -f ~/.min-agent/mcp-oauth.json")) {
            assertTrue("$command", v(monitor(command), ctx(PermissionMode.BypassPermissions, credentials = credentials)) is Verdict.Deny)
            assertTrue("Bash 对照：$command", v(bash(command), ctx(PermissionMode.BypassPermissions, credentials = credentials)) is Verdict.Deny)
        }
    }

    @Test
    fun `Read 的 deny 规则也挡 Monitor 命令往那里写`() {
        val c = ctx(PermissionMode.BypassPermissions, RuleKind.Deny to "Read(*.env)")
        assertTrue("Bash 对照", v(bash("echo x > /workspace/prod.env"), c) is Verdict.Deny)
        assertTrue(v(monitor("echo x > /workspace/prod.env"), c) is Verdict.Deny)
    }

    // ---- WebSocket 源 ----

    @Test
    fun `WebSocket 源每次都要批准：WebFetch 的 allow 放不过它`() {
        val c = ctx(PermissionMode.Default, RuleKind.Allow to "WebFetch(domain:*)", RuleKind.Allow to "WebFetch(domain:feed.example.com)")
        assertEquals(Verdict.Ask, v(socket, c))
        assertEquals(Verdict.Ask, v(socket, ctx(PermissionMode.AcceptEdits)))
        assertEquals(Verdict.Ask, v(socket, ctx(PermissionMode.Plan)))
    }

    @Test
    fun `WebSocket 源在 Auto 下交分类器（点名 Monitor 的 allow 被暂停），dontAsk 拒，bypass 放行`() {
        assertEquals(Verdict.Ask, v(socket, ctx(PermissionMode.Auto)))
        assertEquals(Verdict.Ask, v(socket, ctx(PermissionMode.Auto, RuleKind.Allow to "Monitor")))
        assertTrue(v(socket, ctx(PermissionMode.DontAsk)) is Verdict.Deny)
        assertTrue(v(socket, ctx(PermissionMode.BypassPermissions)) is Verdict.Allow)
    }

    @Test
    fun `WebSocket 弹窗不给以后别问，没有建议规则；命令源的建议是 Bash 规则`() {
        assertNull(PolicyEngine.suggestRule(socket, ctx()))
        assertEquals("Bash(npm run *)", PolicyEngine.suggestRule(monitor("npm run watch"), ctx()))
    }
}
