package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import org.junit.Assert.*
import org.junit.Test

class CredentialPolicyTest {
    private val credentials = setOf(GuestPath("/root/.min-agent/mcp-oauth.json*"), GuestPath("/root/.min-agent/.mcp-oauth-*"))
    private fun context(mode: PermissionMode) = PolicyContext(mode, credentialPaths=credentials,
        rules=listOfNotNull(PermissionRule.parse(RuleKind.Allow,"Bash","test"), PermissionRule.parse(RuleKind.Allow,"Read","test")),
        canonicalPath={ if(it.value=="/workspace/alias") GuestPath("/root/.min-agent/mcp-oauth.json") else it })
    @Test fun `credential files deny all tools and modes before approvals`() {
        for(mode in PermissionMode.entries) {
            for(tool in listOf("Read","Write","Edit","Grep","Glob","NotebookEdit")) {
                for(path in listOf("/root/.min-agent/mcp-oauth.json","/root/.min-agent/mcp-oauth.json.lock","/root/.min-agent/.mcp-oauth-123.tmp","/root/.min-agent/../.min-agent/mcp-oauth.json","/workspace/alias")) {
                    val verdict=PolicyEngine.evaluate(PermissionAsk(tool,"test",paths=listOf(PathAccess(GuestPath(path),if(tool in setOf("Write","Edit","NotebookEdit")) AccessKind.Write else AccessKind.Read,PathScope.Rootfs))),tool in setOf("Read","Grep","Glob"),context(mode))
                    assertTrue("$mode $tool $path must deny",verdict is Verdict.Deny)
                    assertTrue((verdict as Verdict.Deny).messageToModel.contains("这是 Min 保存的凭证文件"))
                }
            }
        }
    }
    @Test fun `shell credential operands redirects globs and recursive roots deny`() {
        for(mode in listOf(PermissionMode.Default,PermissionMode.AcceptEdits,PermissionMode.BypassPermissions)) {
            for(command in listOf("cat ~/.min-agent/mcp-oauth.json","cat < ~/.min-agent/mcp-oauth.json","echo x > ~/.min-agent/mcp-oauth.json","cat ~/.min-agent/mcp-oauth.*","cat ~/.min-agent/*","cd ~/.min-agent && cat mcp-oauth.json","cp ~/.min-agent/mcp-oauth.json /workspace/copy","rg token ~","grep -r token ~/.min-agent")) {
                assertTrue("$mode $command must deny",PolicyEngine.evaluate(PermissionAsk("Bash","test",commands=listOf(CommandAccess(command))),false,context(mode)) is Verdict.Deny)
            }
            assertTrue(PolicyEngine.evaluate(PermissionAsk("Grep","search",paths=listOf(PathAccess(GuestPath("/root"),AccessKind.Read,PathScope.Rootfs))),true,context(mode)) is Verdict.Allow)
            assertTrue(PolicyEngine.evaluate(PermissionAsk("Read","ordinary",paths=listOf(PathAccess(GuestPath("/root/.min-agent/notes.txt"),AccessKind.Read,PathScope.Rootfs))),true,context(mode)) is Verdict.Allow)
        }
    }
}
