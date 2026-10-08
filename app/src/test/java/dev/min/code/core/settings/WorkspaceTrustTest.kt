package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTrustTest {
    @Test
    fun `nothing recorded means not asked yet`() {
        assertNull(WorkspaceTrust.decision(emptySet(), "/workspace/app"))
    }

    @Test
    fun `trust is inherited by subdirectories but a refusal is not`() {
        val trusted = WorkspaceTrust.with(emptySet(), "/workspace", true)
        assertEquals(true, WorkspaceTrust.decision(trusted, "/workspace/app/sub"))
        assertEquals("/workspace", WorkspaceTrust.inheritedFrom(trusted, "/workspace/app"))
        val refused = WorkspaceTrust.with(emptySet(), "/workspace", false)
        assertNull("不信任只管这个目录本身", WorkspaceTrust.decision(refused, "/workspace/app"))
        assertEquals(false, WorkspaceTrust.decision(refused, "/workspace"))
    }

    @Test
    fun `an own refusal beats an inherited trust`() {
        val entries = WorkspaceTrust.with(WorkspaceTrust.with(emptySet(), "/workspace", true), "/workspace/risky", false)
        assertEquals(false, WorkspaceTrust.decision(entries, "/workspace/risky"))
        assertEquals(true, WorkspaceTrust.decision(entries, "/workspace/fine"))
    }

    @Test
    fun `dot-dot cannot borrow a trusted ancestor`() {
        val trusted = WorkspaceTrust.with(emptySet(), "/workspace", true)
        assertNull(WorkspaceTrust.decision(trusted, "/workspace/../etc"))
        assertEquals(true, WorkspaceTrust.decision(trusted, "/workspace/a/../b"))
    }

    @Test
    fun `a later decision replaces the earlier one and null forgets it`() {
        var entries = WorkspaceTrust.with(emptySet(), "/workspace/app/", true)
        assertTrue(WorkspaceTrust.isOwn(entries, "/workspace/app"))
        entries = WorkspaceTrust.with(entries, "/workspace/app", false)
        assertEquals(setOf("-/workspace/app"), entries)
        assertFalse(WorkspaceTrust.isOwn(entries, "/workspace/app"))
        entries = WorkspaceTrust.with(entries, "/workspace/app", null)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `revoking an own trust leaves an inherited one alone`() {
        val entries = WorkspaceTrust.with(WorkspaceTrust.with(emptySet(), "/workspace", true), "/workspace/app", true)
        val revoked = WorkspaceTrust.with(entries, "/workspace/app", null)
        assertEquals("祖先的信任还在", true, WorkspaceTrust.decision(revoked, "/workspace/app"))
        assertEquals("/workspace", WorkspaceTrust.inheritedFrom(revoked, "/workspace/app"))
    }
}
