package dev.min.code.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeClassifyTest {
    @Test
    fun classifyHost_rfc1918_and_loopback() {
        assertEquals(AddressKind.Loopback, NetworkProbe.classifyHost("127.0.0.1"))
        assertEquals(AddressKind.Loopback, NetworkProbe.classifyHost("::1"))
        assertEquals(AddressKind.Lan, NetworkProbe.classifyHost("192.168.1.5"))
        assertEquals(AddressKind.Lan, NetworkProbe.classifyHost("10.0.2.15")) // emulator
        assertEquals(AddressKind.Lan, NetworkProbe.classifyHost("172.16.0.2"))
        assertEquals(AddressKind.Lan, NetworkProbe.classifyHost("172.31.255.1"))
        assertEquals(AddressKind.LinkLocal, NetworkProbe.classifyHost("169.254.1.1"))
        assertEquals(AddressKind.Other, NetworkProbe.classifyHost("100.64.1.2")) // CGNAT
        assertEquals(AddressKind.Other, NetworkProbe.classifyHost("8.8.8.8"))
    }

    @Test
    fun pickPrimaryLan_prefersWlanIpv4() {
        val list = listOf(
            NetAddress("172.20.10.2", AddressKind.Lan, "rmnet0"),
            NetAddress("192.168.1.8", AddressKind.Lan, "wlan0"),
            NetAddress("10.0.0.2", AddressKind.Lan, "eth0"),
            NetAddress("127.0.0.1", AddressKind.Loopback, "lo"),
        )
        val primary = NetworkProbe.pickPrimaryLan(list)
        assertEquals("192.168.1.8", primary?.host)
    }

    @Test
    fun formatUrl_bracketsIpv6() {
        assertEquals("http://127.0.0.1:8080", NetworkProbe.formatUrl("http", "127.0.0.1", 8080))
        assertEquals("http://[::1]:8080", NetworkProbe.formatUrl("http", "::1", 8080))
    }

    @Test
    fun ifacePriority_wlanBeforeRmnet() {
        assertTrue(NetworkProbe.ifacePriority("wlan0") < NetworkProbe.ifacePriority("rmnet_data0"))
        assertTrue(NetworkProbe.ifacePriority("eth0") < NetworkProbe.ifacePriority("tun0"))
    }
}
