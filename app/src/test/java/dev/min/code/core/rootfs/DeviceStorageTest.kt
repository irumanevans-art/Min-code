package dev.min.code.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 共享存储挂不挂的三个条件。
 *
 * 值得单独测，是因为这三个条件里有两个会在**两次会话之间**悄悄变掉：用户可能去系统
 * 设置里把权限收回，也可能把开关关了却没重启 App。漏判一次的后果不是少个功能，
 * 而是 rootfs 里摆着一个 `/sdcard` 却什么都读不到。
 */
class DeviceStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `开关与权限齐了才挂`() {
        val storage = tempFolder.newFolder("sdcard")
        val mount = deviceStorageBindMount(enabled = true, granted = true, storageRoot = storage)
        assertEquals(storage, mount?.source)
        assertEquals(ROOTFS_SDCARD_DIR, mount?.target)
    }

    @Test
    fun `没开开关就不挂`() {
        val storage = tempFolder.newFolder("sdcard")
        assertNull(deviceStorageBindMount(enabled = false, granted = true, storageRoot = storage))
    }

    /** 用户在系统设置里把「所有文件访问权限」收回了——开关还开着，但不能再挂 */
    @Test
    fun `权限被收回后不挂`() {
        val storage = tempFolder.newFolder("sdcard")
        assertNull(deviceStorageBindMount(enabled = true, granted = false, storageRoot = storage))
    }

    /** 外置存储没挂载：提前返回 null，而不是交给 proot 去跳过一个不存在的路径 */
    @Test
    fun `存储根不存在时不挂`() {
        val missing = java.io.File(tempFolder.root, "not-there")
        assertNull(deviceStorageBindMount(enabled = true, granted = true, storageRoot = missing))
    }

    /** 传进来的是文件而不是目录（理论上不会，但 -b 一个文件的行为没人想要） */
    @Test
    fun `存储根是文件时不挂`() {
        val file = tempFolder.newFile("sdcard-as-file")
        assertNull(deviceStorageBindMount(enabled = true, granted = true, storageRoot = file))
    }
}
