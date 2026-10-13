package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Test

class HostOsTest {
    @Test
    fun `follows the os name property on every read`() {
        val saved = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            assertEquals(true, HostOs.isWindows)
            System.setProperty("os.name", "Linux")
            assertEquals(false, HostOs.isWindows)
        } finally {
            System.setProperty("os.name", saved)
        }
    }
}
