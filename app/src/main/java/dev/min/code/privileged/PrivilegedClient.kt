package dev.min.code.privileged

import android.content.Context
import android.os.IBinder
import android.util.Log
import android.view.Display
import dev.min.code.core.device.MinAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MinPrivClient"

/**
 * App 侧看到的壳服务。
 *
 * Binder 由 shell 进程经 [PrivilegedBridgeProvider] 交过来
 *（`getContentProviderExternal` 旁路，见 [PrivilegedServer]）。
 * 死亡时把无障碍目标 display 清回主屏。
 */
class PrivilegedClient(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {

    enum class State {
        Disconnected,
        Starting,
        Ready,
        Failed,
    }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    private val serviceRef = AtomicReference<IPrivilegedService?>(null)
    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "privileged binder died")
        onDisconnected()
    }

    private val handoverListener: (IBinder) -> Unit = { binder -> attach(binder) }

    init {
        // addListener 会把已经交过来的那一个当场补发给我们，不用再自己 attach 一遍
        PrivilegedBridgeProvider.addListener(handoverListener)
    }

    fun service(): IPrivilegedService? = serviceRef.get()

    fun markStarting() {
        lastError = null
        _state.value = State.Starting
    }

    fun markFailed(message: String) {
        lastError = message
        _state.value = State.Failed
    }

    /**
     * 等 Provider 收到壳进程的交接。
     * 壳用 getContentProviderExternal 直接 call Provider，成功后 [handoverListener] 会 attach。
     */
    fun waitUntilReady(timeoutMs: Long = 15_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            when (_state.value) {
                State.Ready -> return true
                State.Failed -> return false
                else -> Thread.sleep(150)
            }
            // 壳可能在我们进入 Starting 之前就交过一次（外部 adb 先拉起）
            PrivilegedBridgeProvider.currentBinder()?.let { binder ->
                if (serviceRef.get() == null) attach(binder)
            }
        }
        if (_state.value != State.Ready) {
            markFailed("等待壳进程超时——请确认命令已执行，且 Min 在前台")
        }
        return _state.value == State.Ready
    }

    @Synchronized
    private fun attach(binder: IBinder) {
        // 壳每 3 秒把同一个 Binder 再交一次（见 PrivilegedServer）。已经接着的就到此为止：
        // 否则每次都多一趟问 uid 的 IPC、多挂一个死亡回调，壳一死 onDisconnected 连跑 N 遍
        val current = serviceRef.get()?.asBinder()
        if (current === binder) return
        runCatching {
            val svc = IPrivilegedService.Stub.asInterface(binder)
            val uid = svc.uid
            if (uid != 2000) {
                markFailed("壳进程 uid=$uid，期望 2000（shell）。请用无线调试/adb 拉起，不要用 root。")
                return
            }
            // 换了一个壳进程：旧的那个以后死了，不能把新接上的也一起清掉
            current?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
            runCatching { binder.linkToDeath(deathRecipient, 0) }
            serviceRef.set(svc)
            lastError = null
            _state.value = State.Ready
            Log.i(TAG, "ready: ${svc.ping()}")
        }.onFailure {
            Log.e(TAG, "attach failed", it)
            markFailed(it.message ?: "attach failed")
        }
    }

    private fun onDisconnected() {
        serviceRef.set(null)
        PrivilegedBridgeProvider.clear()
        MinAccessibilityService.instance.get()?.setTargetDisplay(Display.DEFAULT_DISPLAY)
        if (_state.value != State.Failed) {
            _state.value = State.Disconnected
        }
    }

    fun createAgentDisplay(width: Int, height: Int, densityDpi: Int): Int {
        val svc = service() ?: error("壳服务未就绪")
        return svc.createAgentDisplay(width, height, densityDpi)
    }

    fun destroyAgentDisplay(displayId: Int) {
        runCatching { service()?.destroyAgentDisplay(displayId) }
    }

    fun launchOnDisplay(packageName: String, displayId: Int) {
        val svc = service() ?: error("壳服务未就绪")
        svc.launchOnDisplay(packageName, displayId)
    }
}
