package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bash 的 `cd` 会跨命令保留：命令里的相对路径要按 shell 当前目录解析，而不是会话目录——
 * 否则项目根是家目录、shell 停在 `.ssh` 里时，`cat id_rsa` 会被当成读项目根下的普通文件放过去。
 * 规则里的相对写法（`Edit(src/...)` 一类）仍以会话目录为准，不跟着 shell 走。
 */
class ShellCwdPolicyTest {
    private val credentials = setOf(GuestPath("/home/u/.ssh/id_rsa"))
    private fun context(shell: String?, mode: PermissionMode = PermissionMode.BypassPermissions) = PolicyContext(
        mode, cwd = GuestPath("/home/u"), home = GuestPath("/home/u"), credentialPaths = credentials,
        shellCwd = shell?.let(::GuestPath),
        scopeOf = { if (it.value == "/home/u" || it.value.startsWith("/home/u/")) PathScope.Workspace else PathScope.Device },
    )
    private fun bash(command: String) = PermissionAsk("Bash", "test", commands = listOf(CommandAccess(command)))

    @Test fun `credential file relative to the shell directory is denied`() {
        assertTrue(PolicyEngine.evaluate(bash("cat id_rsa"), false, context("/home/u/.ssh")) is Verdict.Deny)
        assertTrue(PolicyEngine.evaluate(bash("cat < id_rsa"), false, context("/home/u/.ssh")) is Verdict.Deny)
        assertTrue(PolicyEngine.evaluate(bash("cat ./id_rsa"), false, context("/home/u/.ssh")) is Verdict.Deny)
        // 对照：shell 就在项目根时，同一条命令读的是 /home/u/id_rsa，不是凭证
        assertTrue(PolicyEngine.evaluate(bash("cat id_rsa"), false, context(null)) is Verdict.Allow)
    }

    @Test fun `write targets resolve against the shell directory`() {
        val inside = PolicyEngine.evaluate(bash("echo x > ../a.txt"), false, context("/home/u/sub", PermissionMode.BypassPermissions))
        assertTrue("shell 在 /home/u/sub，../a.txt 是 /home/u/a.txt，在工作区里（Device 底线不触发）：$inside", inside is Verdict.Allow)
        val outside = PolicyEngine.evaluate(bash("echo x > ../../a.txt"), false, context("/home/u/sub", PermissionMode.BypassPermissions))
        assertTrue("../../a.txt 是 /home/a.txt，落在底线分级（这里设为 Device）的地方，任何模式都要问人：$outside", outside is Verdict.Ask)
        // 没有 shell 目录（旧行为）：../a.txt 相对会话目录 = /home/a.txt，出界
        assertTrue(PolicyEngine.evaluate(bash("echo x > ../a.txt"), false, context(null, PermissionMode.BypassPermissions)) is Verdict.Ask)
    }

    @Test fun `builtin cd and pushd move the shell directory for later redirects`() {
        // scopeOf 把 /home/u 之外都当 Device：cd 出去之后的相对写入要踩手机文件底线
        for (raw in listOf("builtin cd /sdcard && echo x > a", "pushd /sdcard && echo x > a", "builtin pushd /sdcard; echo x > a")) {
            assertEquals(raw, Verdict.Ask, PolicyEngine.evaluate(bash(raw), false, context(null)))
        }
        assertTrue(PolicyEngine.evaluate(bash("builtin cd sub && echo x > a"), false, context(null)) is Verdict.Allow)
        // pushd 不带参数 / 带 +N 是在目录栈里换位置，静态不知道去了哪：相对写入不能按原目录放行
        val allowEcho = context(null, PermissionMode.Default).copy(rules = listOf(PermissionRule.parse(RuleKind.Allow, "Bash(echo *)", "user")!!))
        assertTrue(PolicyEngine.evaluate(bash("echo x > a"), false, allowEcho) is Verdict.Allow)
        for (raw in listOf("pushd && echo x > a", "pushd +1 && echo x > a", "pushd -n /tmp && echo x > a")) {
            assertEquals(raw, Verdict.Ask, PolicyEngine.evaluate(bash(raw), false, allowEcho))
        }
    }

    @Test fun `Monitor commands run from the shell directory too, so their relative paths resolve there`() {
        // 原文没写 Monitor 的起点目录，按「同 Bash」处理：起点是 shell 目录，分析就必须按同一个目录做
        fun monitor(command: String) = PermissionAsk("Monitor", "test", commands = listOf(CommandAccess(command)))
        assertTrue(PolicyEngine.evaluate(monitor("cat id_rsa"), false, context("/home/u/.ssh")) is Verdict.Deny)
        assertTrue(PolicyEngine.evaluate(monitor("cat id_rsa"), false, context(null)) is Verdict.Allow)
    }
}
