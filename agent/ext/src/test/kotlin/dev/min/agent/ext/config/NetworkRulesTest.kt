package dev.min.agent.ext.config

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkRulesTest {
    @org.junit.Test fun ambiguousIpv4IsRejectedBeforeAllowRules() {
        for (host in listOf("0177.0.0.1", "127.00.0.1", "2130706433", "127.1", "0x7f000001", "0x7f.0.0.1", "256.0.0.1")) {
            org.junit.Assert.assertNull(host, NetworkRules.normalizeHost(host))
            org.junit.Assert.assertEquals(host, NetworkOutcome.Deny,
                NetworkRules.evaluate(NetworkSettings(allowedDomains = listOf("*"), deniedDomains = listOf("127.0.0.1")),
                    NetworkTarget(host, 80), PermissionMode.BypassPermissions).outcome)
        }
        org.junit.Assert.assertEquals("127.0.0.1", NetworkRules.normalizeHost("127.0.0.1"))
    }
    private fun verdict(s: NetworkSettings, host: String = "api.example.com", port: Int = 443,
                        mode: PermissionMode = PermissionMode.Default, hosts: List<String> = emptyList()) =
        NetworkRules.evaluate(s, NetworkTarget(host, port), mode, hosts).outcome

    @Test fun exactWildcardAndPort() {
        assertTrue(NetworkRules.matches("example.com", NetworkTarget("EXAMPLE.com.", 80)))
        assertTrue(NetworkRules.matches("*.example.com:443", NetworkTarget("deep.api.example.com", 443)))
        assertFalse(NetworkRules.matches("*.example.com", NetworkTarget("example.com", 443)))
        assertFalse(NetworkRules.matches("*.example.com", NetworkTarget("evil-example.com", 443)))
        assertFalse(NetworkRules.matches("*.example.com:443", NetworkTarget("api.example.com", 80)))
        assertTrue(NetworkRules.matches("127.0.0.1:80", NetworkTarget("127.0.0.1", 80)))
        assertTrue(NetworkRules.matches("[::1]:443", NetworkTarget("::1", 443)))
        assertTrue(NetworkRules.matches("*", NetworkTarget("other.test", 443)))
        assertFalse(NetworkRules.matches("example.*", NetworkTarget("example.com", 443)))
    }
    @Test fun denyWinsOverAllowAndBypassAndCommand() {
        val s = NetworkSettings(true, allowedDomains = listOf("*.example.com"), deniedDomains = listOf("api.example.com"))
        for (mode in PermissionMode.entries) assertEquals(NetworkOutcome.Deny, verdict(s, mode = mode, hosts = listOf("*")))
    }
    @Test fun managedAndStrictLocksCannotBeWidenedByModeOrCommand() {
        val s = NetworkSettings(true, allowManagedDomainsOnly = true)
        for (mode in PermissionMode.entries) assertEquals(NetworkOutcome.Deny, verdict(s, mode = mode, hosts = listOf("*")))
        assertEquals(NetworkOutcome.Deny, verdict(s.copy(allowManagedDomainsOnly = false, strictAllowlist = true), mode = PermissionMode.BypassPermissions))
    }
    @Test fun permissionModesAndPerCommandAuto() {
        val s = NetworkSettings(true)
        assertEquals(NetworkOutcome.Ask, verdict(s))
        assertEquals(NetworkOutcome.Ask, verdict(s, mode = PermissionMode.AcceptEdits))
        assertEquals(NetworkOutcome.Ask, verdict(s, mode = PermissionMode.Plan))
        assertEquals(NetworkOutcome.Allow, verdict(s, mode = PermissionMode.BypassPermissions))
        assertEquals(NetworkOutcome.Deny, verdict(s, mode = PermissionMode.DontAsk))
        assertEquals(NetworkOutcome.Deny, verdict(s, mode = PermissionMode.Auto))
        assertEquals(NetworkOutcome.Allow, verdict(s, mode = PermissionMode.Auto, hosts = listOf("*.example.com")))
    }
    @Test fun actualSettingsMergeManagedTrustWebFetchAndCustomPorts() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/root/.claude/settings.json", """{"sandbox":{"enabled":true,"network":{"allowedDomains":["user.test"],"deniedDomains":["blocked.test"],"httpProxyPort":8000}},"permissions":{"allow":["WebFetch(domain:fetch.test)","WebFetch(domain:example.*)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"sandbox":{"enabled":false,"network":{"allowedDomains":["repo.test"],"allowManagedDomainsOnly":true,"httpProxyPort":9000}}}""")
        val merged = SettingsLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")).merged
        val trusted = merged.networkSettings()
        assertFalse(trusted.enabled)
        assertEquals(setOf("user.test", "repo.test", "fetch.test"), trusted.allowedDomains.toSet())
        assertFalse(trusted.allowManagedDomainsOnly)
        val untrusted = merged.networkSettings(false)
        assertTrue(untrusted.enabled); assertFalse("repo.test" in untrusted.allowedDomains)
        assertEquals(8000, untrusted.httpProxyPort)
        fs.file("/etc/claude-code/managed-settings.json", """{"sandbox":{"enabled":true,"network":{"allowManagedDomainsOnly":true,"allowedDomains":["managed.test"]}},"permissions":{"allow":["WebFetch(domain:managed-fetch.test)"]}}""")
        val locked = SettingsLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")).merged.networkSettings()
        assertTrue(locked.enabledManaged); assertTrue(locked.enabled); assertTrue(locked.allowManagedDomainsOnly)
        assertEquals(setOf("managed.test", "managed-fetch.test"), locked.allowedDomains.toSet())
        assertEquals(listOf("blocked.test"), locked.deniedDomains); assertNull(locked.httpProxyPort)
    }
}
