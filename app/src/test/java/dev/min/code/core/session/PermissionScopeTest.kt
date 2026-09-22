package dev.min.code.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次调用要动的东西在不在 /workspace 里」。
 *
 * 分档错了有两种坏法，都不轻：把设备上的文件判成 rootfs，警示就不发朱、「始终允许」
 * 也照给不误；反过来把 /workspace 里的正常编辑判成越界，警示天天出现，很快就没人看了。
 */
class PermissionScopeTest {

    private fun input(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun `workspace 里的路径不算越界`() {
        assertEquals(PathScope.Workspace, pathScope("/workspace"))
        assertEquals(PathScope.Workspace, pathScope("/workspace/app/src/Main.kt"))
    }

    /** 相对路径跟着会话 cwd 走，不在这里二次猜测 */
    @Test
    fun `相对路径算 workspace`() {
        assertEquals(PathScope.Workspace, pathScope("app/build.gradle.kts"))
        assertEquals(PathScope.Workspace, pathScope(""))
    }

    @Test
    fun `容器里的系统目录算 rootfs`() {
        assertEquals(PathScope.Rootfs, pathScope("/etc/hosts"))
        assertEquals(PathScope.Rootfs, pathScope("/root/.bashrc"))
        assertEquals(PathScope.Rootfs, pathScope("/usr/bin/node"))
    }

    @Test
    fun `共享存储算设备`() {
        assertEquals(PathScope.Device, pathScope("/sdcard"))
        assertEquals(PathScope.Device, pathScope("/sdcard/DCIM/Camera/IMG_0001.jpg"))
        assertEquals(PathScope.Device, pathScope("/storage/emulated/0/Download/x.pdf"))
    }

    /**
     * 前缀要整段匹配。`/sdcardish` 不是 `/sdcard` 下面的东西 ——
     * 和 bind mount 那边 `/skills` 不该吃掉 `/skillsets` 是同一类错。
     */
    @Test
    fun `前缀不吃同名更长的兄弟目录`() {
        assertEquals(PathScope.Rootfs, pathScope("/sdcardish/a.txt"))
        assertEquals(PathScope.Rootfs, pathScope("/workspaces-old/a.txt"))
    }

    @Test
    fun `尾部斜杠不影响判定`() {
        assertEquals(PathScope.Workspace, pathScope("/workspace/"))
        assertEquals(PathScope.Device, pathScope("/sdcard/"))
    }

    @Test
    fun `workspace 内的编辑不报越界`() {
        assertTrue(escapedPaths(input("file_path" to "/workspace/a.kt")).isEmpty())
        assertFalse(touchesDeviceStorage(input("file_path" to "/workspace/a.kt")))
    }

    @Test
    fun `设备路径被挑出来并认定为设备`() {
        val i = input("file_path" to "/sdcard/DCIM/a.jpg")
        assertEquals(listOf("/sdcard/DCIM/a.jpg"), escapedPaths(i))
        assertTrue(touchesDeviceStorage(i))
    }

    /** rootfs 越界要提示，但不该被当成"动了你手机里的东西" */
    @Test
    fun `rootfs 越界提示但不发朱`() {
        val i = input("file_path" to "/etc/hosts")
        assertEquals(listOf("/etc/hosts"), escapedPaths(i))
        assertFalse(touchesDeviceStorage(i))
    }

    /** 一次调用同时碰到两档时，设备那条排在前面——sheet 上只显示第一条 */
    @Test
    fun `设备路径排在 rootfs 前面`() {
        val i = input("cwd" to "/etc", "file_path" to "/sdcard/a.txt")
        assertEquals(listOf("/sdcard/a.txt", "/etc"), escapedPaths(i))
    }

    /** 入参里没有路径键（Bash、WebSearch 之类）时什么都不报 */
    @Test
    fun `没有路径入参时不报`() {
        val i = input("command" to "rm -rf /sdcard/DCIM")
        assertTrue(escapedPaths(i).isEmpty())
        assertFalse(touchesDeviceStorage(i))
    }

    /** 路径键的值不是字符串时安静跳过，不要炸在权限面板这条必须立刻响应的路径上 */
    @Test
    fun `路径键不是字符串时跳过`() {
        val i = JsonObject(mapOf("file_path" to JsonPrimitive(42)))
        assertTrue(escapedPaths(i).isEmpty())
    }
}
