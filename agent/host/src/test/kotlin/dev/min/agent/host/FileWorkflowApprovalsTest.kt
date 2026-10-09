package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWorkflowApprovalsTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun persistentConsentHasProjectAndScriptScopeAndRevokesWithoutCache() = runBlocking {
        val one=FileWorkflowApprovals(tmp.root); val two=FileWorkflowApprovals(tmp.root)
        val cwd=GuestPath("/work"); val hash="a".repeat(64)
        one.approve(cwd,hash)
        assertTrue(two.contains(cwd,hash)); assertFalse(two.contains(GuestPath("/other"),hash)); assertFalse(two.contains(cwd,"b".repeat(64)))
        two.revoke(cwd,hash); assertFalse(one.contains(cwd,hash))
        one.consentAuto(); assertTrue(two.autoConsented()); two.revokeAuto(); assertFalse(one.autoConsented())
    }
}
