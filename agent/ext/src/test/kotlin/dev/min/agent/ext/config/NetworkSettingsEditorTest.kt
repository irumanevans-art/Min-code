package dev.min.agent.ext.config

import dev.min.agent.spi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkSettingsEditorTest {
    @Test fun allowsRemovalAndWebFetchRevocationWithoutLosingOtherSettings() = runBlocking {
        val fs = InMemoryFs(allowWrites = true).apply { file("/root/.claude/settings.json", """{"model":"keep","sandbox":{"enabled":false,"network":{"deniedDomains":["bad.test"]}},"permissions":{"allow":["WebFetch(domain:fetch.test)","Bash"]}}""") }
        val home = GuestPath("/root"); val cwd = GuestPath("/workspace")
        NetworkSettingsEditor.setEnabled(fs, cwd, home, true)
        NetworkSettingsEditor.allow(fs, home, "allowed.test:443")
        val view = NetworkSettingsEditor.view(fs, cwd, home)
        assertTrue(view.settings.enabled)
        assertEquals(setOf("allowed.test:443", "fetch.test", "bad.test"), view.domains.map { it.domain }.toSet())
        for (entry in view.domains) NetworkSettingsEditor.remove(fs, entry)
        val remaining = NetworkSettingsEditor.view(fs, cwd, home)
        assertTrue(remaining.domains.isEmpty())
        val raw = fs.read(GuestPath("/root/.claude/settings.json")).decodeToString()
        assertTrue(raw.contains("keep")); assertTrue(raw.contains("Bash"))
    }
    @Test fun enabledSwitchEditsTheEffectiveTrustedLayer() = runBlocking {
        val fs = InMemoryFs(allowWrites = true).apply {
            file("/root/.claude/settings.json", """{"sandbox":{"enabled":false}}""")
            file("/workspace/.claude/settings.json", """{"model":"keep","sandbox":{"enabled":false}}""")
        }
        val home = GuestPath("/root"); val cwd = GuestPath("/workspace")
        NetworkSettingsEditor.setEnabled(fs, cwd, home, true)
        assertTrue(NetworkSettingsEditor.view(fs, cwd, home).settings.enabled)
        assertTrue(fs.read(GuestPath("/workspace/.claude/settings.json")).decodeToString().contains("keep"))
        assertFalse(NetworkSettingsEditor.view(fs, cwd, home, trust = false).settings.enabled)
    }
    @Test fun managedValuesRemainReadOnly() = runBlocking {
        val fs = InMemoryFs(allowWrites = true).apply { file("/etc/claude-code/managed-settings.json", """{"sandbox":{"enabled":true,"network":{"allowManagedDomainsOnly":true,"allowedDomains":["managed.test"]}}}""") }
        val home = GuestPath("/root"); val cwd = GuestPath("/workspace")
        val view = NetworkSettingsEditor.view(fs, cwd, home)
        try { NetworkSettingsEditor.setEnabled(fs, cwd, home, false); fail("托管开关不能关闭") } catch (_: IllegalStateException) { }
        try { NetworkSettingsEditor.remove(fs, view.domains.single()); fail("托管名单不能删除") } catch (_: IllegalArgumentException) { }
        assertTrue(NetworkSettingsEditor.view(fs, cwd, home).settings.enabled)
    }
}
