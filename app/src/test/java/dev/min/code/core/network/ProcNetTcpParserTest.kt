package dev.min.code.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcNetTcpParserTest {
    @Test
    fun decodeIpv4_loopbackAndAny() {
        assertEquals("127.0.0.1", ProcNetTcpParser.decodeIpv4("0100007F"))
        assertEquals("0.0.0.0", ProcNetTcpParser.decodeIpv4("00000000"))
        assertEquals("192.168.1.10", ProcNetTcpParser.decodeIpv4("0A01A8C0"))
    }

    @Test
    fun parseTcp_listenRowsOnly() {
        // 0A = LISTEN, 01 = ESTABLISHED
        val text = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             0: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 00000000 100 0 0 10 0
             1: 0100007F:18EB 0100007F:1F90 01 00000000:00000000 00:00000000 00000000     0        0 99999 1 00000000 100 0 0 10 0
             2: 0100007F:0050 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 54321 1 00000000 100 0 0 10 0
        """.trimIndent()
        val entries = ProcNetTcpParser.parseTcp(text)
        assertEquals(2, entries.size)
        assertEquals(0x1F90, entries[0].port) // 8080
        assertEquals("0.0.0.0", entries[0].localHost)
        assertEquals(80, entries[1].port)
        assertEquals("127.0.0.1", entries[1].localHost)
        assertEquals(12345L, entries[0].inode)
    }

    @Test
    fun decodeIpv6_loopbackAndAny() {
        assertEquals("::", ProcNetTcpParser.decodeIpv6("00000000000000000000000000000000"))
        assertEquals("::1", ProcNetTcpParser.decodeIpv6("00000000000000000000000001000000"))
    }

    @Test
    fun parseBoth_merges() {
        val tcp = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             0: 00000000:22B8 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1 00000000 100 0 0 10 0
        """.trimIndent()
        val all = ProcNetTcpParser.parseBoth(tcp, null)
        assertEquals(1, all.size)
        assertEquals(8888, all[0].port)
        assertTrue(all[0].proto == "tcp")
    }
}
