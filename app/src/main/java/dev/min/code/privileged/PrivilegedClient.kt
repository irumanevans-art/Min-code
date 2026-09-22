package dev.min.code.privileged

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.Display
import dev.min.code.core.device.MinAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MinPrivClient"

/**
 * App 侧看到的壳服务。
 *
 * Binder 经 abstract LocalSocket 从 shell 进程取回（见 [PrivilegedServer.handOverBinder]）。
 * 死亡时把无障碍目标 display 清回主屏。
 */
class PrivilegedClient(
    private val context: Context,
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
     * 连上壳进程开的 LocalServerSocket，把 Binder 读回来。
     * 壳进程 accept 之后才会写完；这边要在拉起命令发出后轮询连接。
     */
    fun fetchBinderFromSocket(timeoutMs: Long = 15_000L): Boolean {
        val name = PrivilegedServer.socketName(context.packageName)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var last: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                val binder = readBinderOnce(name)
                attach(binder)
                return _state.value == State.Ready
            } catch (e: Exception) {
                last = e
                Thread.sleep(200)
            }
        }
        markFailed(last?.message ?: "等待壳进程超时（@$name）")
        return false
    }

    private fun readBinderOnce(socketName: String): IBinder {
        LocalSocket().use { sock ->
            sock.connect(LocalSocketAddress(socketName))
            val input = DataInputStream(sock.inputStream)
            val size = input.readInt()
            require(size in 1..1_000_000) { "bad binder parcel size=$size" }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            val parcel = Parcel.obtain()
            return try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                parcel.readStrongBinder()
                    ?: error("null binder from shell")
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun attach(binder: IBinder) {
        runCatching {
            val svc = IPrivilegedService.Stub.asInterface(binder)
            val uid = svc.uid
            if (uid != 2000) {
                markFailed("壳进程 uid=$uid，期望 2000（shell）。请用无线调试/adb 拉起，不要用 root。")
                return
            }
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
