package dev.min.code.core.engine.min

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MinWorkflowApprovalsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun approvalsPersistAndStayBoundToProjectAndScriptContent() = runBlocking {
        val first = MinWorkflowApprovals(tmp.root)
        val cwd = GuestPath("/work"); val hash = "a".repeat(64)
        assertFalse(first.contains(cwd, hash))
        first.approve(cwd, hash)
        val reopened = MinWorkflowApprovals(tmp.root)
        assertTrue(reopened.contains(cwd, hash))
        assertFalse(reopened.contains(GuestPath("/other"), hash))
        assertFalse(reopened.contains(cwd, "b".repeat(64)))
    }
}
