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
 * 壳服务没就绪时 [start] 直接失败，上层应退回真屏路径，不要假装有虚拟屏。
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

    /**
     * @param width/height 建议与主屏同级或略小；太小（&lt;200）壳侧会拒
     */
    fun start(
        width: Int = 1080,
        height: Int = 1920,
        densityDpi: Int = 320,
    ): Active {
        stop()
        val id = privileged.createAgentDisplay(width, height, densityDpi)
        MinAccessibilityService.instance.get()?.setTargetDisplay(id)
            ?: Log.w(TAG, "a11y service not connected; display $id created but gestures may miss")
        val session = Active(id, width, height, densityDpi)
        _active.value = session
        Log.i(TAG, "started $session")
        return session
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
