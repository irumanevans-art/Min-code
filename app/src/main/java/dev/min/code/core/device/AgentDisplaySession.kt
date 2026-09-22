package dev.min.code.core.device

import android.util.Log
import android.view.Display
import dev.min.code.privileged.PrivilegedClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AgentDisplay"

/**
 * 虚拟屏会话：建屏 → 把无障碍目标切过去 → 用完释放。
 *
 * ## 清后台之后
 *
 * 壳进程是独立的 shell 进程，`am force-stop` Min **不会**杀掉它，VirtualDisplay 也还在。
 * App 再打开时 [restoreIfPossible] 向壳询问仍持有的 displayId，接回会话，不必重建、不必再 adb。
 */
class AgentDisplaySession(
    private val privileged: PrivilegedClient,
) {
    data class Active(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    val isActive: Boolean get() = _active.value != null

    fun start(
        width: Int = 1080,
        height: Int = 1920,
        densityDpi: Int = 320,
    ): Active {
        stop()
        val id = privileged.createAgentDisplay(width, height, densityDpi)
        bindLocal(id, width, height, densityDpi)
        return _active.value!!
    }

    /**
     * 壳还活着、屏还在时，把本地会话状态接回去。
     * @return 接回的会话；壳未就绪或没有现存屏时 null
     */
    fun restoreIfPossible(
        width: Int = 1080,
        height: Int = 1920,
        densityDpi: Int = 320,
    ): Active? {
        if (_active.value != null) return _active.value
        val svc = privileged.service() ?: return null
        val ids = runCatching { svc.listAgentDisplays() }.getOrNull() ?: return null
        val id = ids.maxOrNull() ?: return null
        bindLocal(id, width, height, densityDpi)
        Log.i(TAG, "restored displayId=$id (shell still held it)")
        return _active.value
    }

    private fun bindLocal(id: Int, width: Int, height: Int, densityDpi: Int) {
        MinAccessibilityService.instance.get()?.setTargetDisplay(id)
            ?: Log.w(TAG, "a11y service not connected; display $id bound but gestures may miss")
        val session = Active(id, width, height, densityDpi)
        _active.value = session
        Log.i(TAG, "bound $session")
    }

    fun launch(packageName: String) {
        val a = _active.value ?: error("虚拟屏会话未开始")
        privileged.launchOnDisplay(packageName, a.displayId)
    }

    fun stop() {
        val a = _active.value ?: return
        runCatching { privileged.destroyAgentDisplay(a.displayId) }
        MinAccessibilityService.instance.get()?.setTargetDisplay(Display.DEFAULT_DISPLAY)
        _active.value = null
        Log.i(TAG, "stopped displayId=${a.displayId}")
    }
}
