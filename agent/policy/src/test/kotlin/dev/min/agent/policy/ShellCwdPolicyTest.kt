package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
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
}
