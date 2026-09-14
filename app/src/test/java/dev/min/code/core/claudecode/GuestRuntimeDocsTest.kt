package dev.min.code.core.claudecode

import dev.min.code.core.network.AddressKind
import dev.min.code.core.network.NetAddress
import dev.min.code.core.network.NetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GuestRuntimeDocsTest {
    @Test
    fun upsertBlock_appendsWhenMissing() {
        val next = GuestRuntimeDocs.upsertBlock("hello\n", GuestRuntimeDocs.renderBlock())
        assertTrue(next.contains("hello"))
        assertTrue(next.contains(GuestRuntimeDocs.BLOCK_START))
        assertTrue(next.contains(GuestRuntimeDocs.BLOCK_END))
    }

    @Test
    fun upsertBlock_replacesInPlaceWithoutDup() {
        val first = GuestRuntimeDocs.upsertBlock("", GuestRuntimeDocs.renderBlock())
        val second = GuestRuntimeDocs.upsertBlock(
            first + "\nuser note\n",
            GuestRuntimeDocs.renderBlock(
                NetworkSnapshot(
                    addresses = listOf(
                        NetAddress("192.168.0.3", AddressKind.Lan, "wlan0", primary = true),
                    ),
                    ports = emptyList(),
                    portsReadable = false,
                    primaryLanHost = "192.168.0.3",
                ),
            ),
        )
        assertEquals(1, second.split(GuestRuntimeDocs.BLOCK_START).size - 1)
        assertTrue(second.contains("192.168.0.3"))
        assertTrue(second.contains("user note"))
    }

    @Test
    fun ensureSeeded_twiceIsIdempotentContent() {
        val dir = createTempDirectory("min-guest-docs").toFile()
        val file = File(dir, "CLAUDE.md")
        assertTrue(GuestRuntimeDocs.ensureSeeded(file))
        val once = file.readText()
        // same snapshot-less block → no rewrite needed if identical
        val again = GuestRuntimeDocs.ensureSeeded(file)
        // may be false if identical; content must still be single block
        assertEquals(1, file.readText().split(GuestRuntimeDocs.BLOCK_START).size - 1)
        assertTrue(once.contains(GuestRuntimeDocs.BLOCK_START))
        if (!again) {
            assertEquals(once, file.readText())
        }
        dir.deleteRecursively()
    }

    @Test
    fun envFrom_setsKeys() {
        val snap = NetworkSnapshot(
            addresses = listOf(
                NetAddress("10.0.0.2", AddressKind.Lan, "wlan0", primary = true),
                NetAddress("127.0.0.1", AddressKind.Loopback, "lo"),
            ),
            ports = emptyList(),
            portsReadable = true,
            primaryLanHost = "10.0.0.2",
        )
        val env = GuestRuntimeDocs.envFrom(snap)
        assertEquals("10.0.0.2", env[GuestRuntimeDocs.ENV_LAN_IP])
        assertTrue(env[GuestRuntimeDocs.ENV_REACHABLE_BASES]!!.contains("http://10.0.0.2"))
        assertEquals("127.0.0.1", env[GuestRuntimeDocs.ENV_LOOPBACK])
        assertFalse(env[GuestRuntimeDocs.ENV_NETWORK_NOTE].isNullOrBlank())
    }
}
