package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.*
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

    @Test fun concurrentConsentIsIdempotent() = runBlocking {
        val store=FileWorkflowApprovals(java.io.File(tmp.root,"nested"))
        (0 until 50).map { async(Dispatchers.Default) { store.approve(GuestPath("/work"),"a".repeat(64)); store.consentAuto() } }.awaitAll()
        assertTrue(store.autoConsented()); assertTrue(store.contains(GuestPath("/work"),"a".repeat(64)))
    }

    @Test fun legacyOnceMarkerDoesNotCountAsExplicitRemember() = runBlocking {
        val cwd=GuestPath("/work"); val hash="a".repeat(64)
        val legacy=java.security.MessageDigest.getInstance("SHA-256").digest("${cwd.value}\u0000$hash".toByteArray()).joinToString("") { "%02x".format(it) }
        java.io.File(tmp.root,legacy).createNewFile()
        val store=FileWorkflowApprovals(tmp.root)
        assertFalse("A legacy Once must not silently become project Remember",store.contains(cwd,hash))
        store.approve(cwd,hash); assertTrue(store.contains(cwd,hash))
    }
}
