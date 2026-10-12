package dev.min.agent.policy

import dev.min.agent.spi.*
import org.junit.Assert.*
import org.junit.Test

class GuestIsolationPolicyTest {
    @Test fun onlyWorkspaceTemporaryAndCacheRootsAreWritable() {
        val policy = GuestIsolationPolicy.forCommand()
        for (path in listOf("/workspace/a", "/tmp/a", "/root/.npm/a", "/root/.min/exec-logs/x"))
            assertTrue(path, GuestIsolationPolicy.canWrite(policy, GuestPath(path)))
        for (path in listOf("/etc/a", "/root/settings.json", "/sdcard/a", "/workspace-other/a", "/root/.claude/settings.json"))
            assertFalse(path, GuestIsolationPolicy.canWrite(policy, GuestPath(path)))
        assertEquals(GuestNetwork.Offline, policy.network)
    }
    @Test fun onlyHookStagingAndEnvFileDirsCanBeGrantedOnTop() {
        val policy = GuestIsolationPolicy.forCommand(extraWritable = listOf(GuestPath("/root/.min/hooks"), GuestPath("/root/.min/artifacts/s_1/env")))
        for (path in listOf("/root/.min/hooks/batch/out.0", "/root/.min/artifacts/s_1/env/sessionstart-startup.sh", "/workspace/a"))
            assertTrue(path, GuestIsolationPolicy.canWrite(policy, GuestPath(path)))
        // 放行只限清单里的目录：附件目录的其余部分、.min 的其余部分仍不可写
        for (path in listOf("/root/.min/artifacts/s_1/other.txt", "/root/.min/artifacts/s_2/env/a", "/root/.min/open/x", "/root/.min/hooks-evil/x"))
            assertFalse(path, GuestIsolationPolicy.canWrite(policy, GuestPath(path)))
        // 别的目录不能借这个口子放行：/etc、/root、整个 .min、附件根、越级路径、空段
        for (bad in listOf("/etc", "/root", "/root/.min", "/root/.min/artifacts", "/root/.min/artifacts/s_1", "/root/.min/hooks/../open", "/root/.min/artifacts/s_1/env/sub",
            "/root/.min/artifacts/s_1/../s_2/env", "/root/.min//hooks", "/root/.min/hooks/", "/sdcard", "/workspace-other"))
            try { GuestIsolationPolicy.forCommand(extraWritable = listOf(GuestPath(bad))); fail("granted $bad") } catch (_: IllegalArgumentException) { }
    }
    @Test fun proxyPortsAreExplicitCapabilities() {
        assertEquals(GuestNetwork.ProxyOnly(listOf(8123)), GuestIsolationPolicy.forCommand(GuestNetwork.ProxyOnly(listOf(8123))).network)
        try { GuestNetwork.ProxyOnly(listOf(0)); fail("invalid port") } catch (_: IllegalArgumentException) { }
    }
    @Test fun fileToolsCannotBypassTheSameWritePolicy() {
        val context = PolicyContext(dev.min.agent.protocol.PermissionMode.BypassPermissions, guestIsolation = GuestIsolationPolicy.forCommand())
        val outside = PermissionAsk("Write", "outside", paths = listOf(PathAccess(GuestPath("/etc/a"), AccessKind.Write, PathScope.Rootfs)))
        assertTrue(PolicyEngine.evaluate(outside, false, context) is Verdict.Deny)
        assertTrue(PolicyEngine.evaluate(outside.copy(paths = listOf(PathAccess(GuestPath("/workspace/a"), AccessKind.Write, PathScope.Workspace))), false, context) is Verdict.Allow)
        val alias = context.copy(canonicalPath = { GuestPath("/etc/a") })
        assertTrue(PolicyEngine.evaluate(outside.copy(paths = listOf(PathAccess(GuestPath("/workspace/link"), AccessKind.Write, PathScope.Workspace))), false, alias) is Verdict.Deny)
    }
    @Test fun onlyTrustedLoopbackProxyUrlsYieldCapabilities() {
        assertEquals(GuestNetwork.ProxyOnly(listOf(8123, 8124)), GuestIsolationPolicy.proxyNetwork(mapOf("HTTP_PROXY" to "http://test@127.0.0.1:8123", "ALL_PROXY" to "socks5h://[::1]:8124")))
        for (value in listOf("http://external.test:8123", "http://localhost:8123", "http://127.0.0.1", "bad")) {
            try { GuestIsolationPolicy.proxyNetwork(mapOf("HTTP_PROXY" to value)); fail("invalid proxy accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
