package dev.min.code.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * flag 组合必须按 API 分层。漏掉 TRUSTED 的话，第三方 App 上不了虚拟屏——
 * 整档能力当场废掉，而且只在真机壳进程里才暴露，单测这边先钉住。
 */
class VirtualDisplayFlagsTest {

    @Test
    fun `API 32 还没有 TRUSTED`() {
        val f = VirtualDisplayFlags.forSdk(32)
        assertFalse(f and VirtualDisplayFlags.TRUSTED != 0)
        assertTrue(f and VirtualDisplayFlags.PUBLIC != 0)
        assertTrue(f and VirtualDisplayFlags.DESTROY_CONTENT_ON_REMOVAL != 0)
    }

    @Test
    fun `API 33 带上 TRUSTED 与 OWN_DISPLAY_GROUP`() {
        val f = VirtualDisplayFlags.forSdk(33)
        assertTrue(f and VirtualDisplayFlags.TRUSTED != 0)
        assertTrue(f and VirtualDisplayFlags.OWN_DISPLAY_GROUP != 0)
        assertTrue(f and VirtualDisplayFlags.ALWAYS_UNLOCKED != 0)
        assertFalse(f and VirtualDisplayFlags.OWN_FOCUS != 0)
    }

    @Test
    fun `API 34 再带 OWN_FOCUS`() {
        val f = VirtualDisplayFlags.forSdk(34)
        assertTrue(f and VirtualDisplayFlags.TRUSTED != 0)
        assertTrue(f and VirtualDisplayFlags.OWN_FOCUS != 0)
        assertTrue(f and VirtualDisplayFlags.DEVICE_DISPLAY_GROUP != 0)
    }

    @Test
    fun `数值与 AOSP hide 常量一致`() {
        // 防回归：哪天有人「顺手改成别的移位」会让真机 SecurityException
        assertEquals(1 shl 10, VirtualDisplayFlags.TRUSTED)
        assertEquals(1 shl 14, VirtualDisplayFlags.OWN_FOCUS)
    }
}
