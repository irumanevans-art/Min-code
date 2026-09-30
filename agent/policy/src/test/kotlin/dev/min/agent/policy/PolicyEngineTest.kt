package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private fun write(path: String, scope: PathScope) =
        PermissionAsk("Write", "写 $path", paths = listOf(PathAccess(GuestPath(path), AccessKind.Write, scope)))

    private val workspaceWrite = write("/workspace/a.kt", PathScope.Workspace)
    private val deviceWrite = write("/sdcard/DCIM/x.jpg", PathScope.Device)
    private val command = PermissionAsk("Bash", "跑构建", commands = listOf(CommandAccess("./gradlew build")))
    private val read = PermissionAsk("Read", "读 a.kt")

    @Test
    fun `writing real phone files always asks, even when bypassing`() {
        PermissionMode.entries.forEach { mode ->
            assertEquals("模式 $mode", Verdict.Ask, PolicyEngine.evaluate(deviceWrite, readOnly = false, mode = mode))
        }
    }

    @Test
    fun `reads are allowed in every mode`() {
        PermissionMode.entries.forEach { mode ->
            assertTrue("模式 $mode", PolicyEngine.evaluate(read, readOnly = true, mode = mode) is Verdict.Allow)
        }
    }

    @Test
    fun `each mode treats writes and commands as documented`() {
        fun v(ask: PermissionAsk, mode: PermissionMode) = PolicyEngine.evaluate(ask, readOnly = false, mode = mode)

        assertEquals(Verdict.Ask, v(workspaceWrite, PermissionMode.Default))
        assertEquals(Verdict.Ask, v(command, PermissionMode.Default))
        assertTrue(v(workspaceWrite, PermissionMode.AcceptEdits) is Verdict.Allow)
        assertEquals(Verdict.Ask, v(command, PermissionMode.AcceptEdits))
        assertEquals(Verdict.Ask, v(write("/etc/hosts", PathScope.Rootfs), PermissionMode.AcceptEdits))
        assertTrue(v(command, PermissionMode.BypassPermissions) is Verdict.Allow)
        assertTrue(v(workspaceWrite, PermissionMode.Plan) is Verdict.Deny)
        assertTrue(v(command, PermissionMode.DontAsk) is Verdict.Deny)
    }
}
