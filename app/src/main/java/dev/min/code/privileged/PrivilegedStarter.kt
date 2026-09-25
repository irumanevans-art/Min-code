package dev.min.code.privileged

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.AdbPairingRequiredException
import dev.min.code.AppScope
import dev.min.code.core.device.AgentDisplaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MinPrivStarter"

/**
 * 把 [PrivilegedServer] 以 shell uid 拉起来。
 *
 * ## 三条路
 *
 * 1. **明文 tcpip :5555**（优先）：`adb tcpip 5555` 之后 adbd 在 loopback 听明文端口，
 *    **不靠 WiFi 射频**。这台 vivo 上关 WiFi 之后 5555 还在，应用 uid 也能连。
 *    拔 USB 时只要 adbd 已经切到 TCP 模式，也不会跟着死。
 * 2. **应用内无线调试**（API 31+）：配对一次，扫 30000–50000 的 TLS 端口。vivo 关
 *    WiFi 会把 `adb_wifi_enabled` 吞掉，所以这条路射频一关就没。连上之后立刻发
 *    `tcpip:5555`，下次走第 1 条。
 * 3. **电脑 adb**：开发期 / 旧系统兜底——`adb shell` 跑同一条命令（[launchCommand]）。
 *
 * 重启 adbd 会丢掉 5555（不能写 `persist.adb.tcp.port`），所以重启手机后还要再拉一次。
 */
class PrivilegedStarter(
    private val context: Context,
    private val client: PrivilegedClient,
    private val manager: MinAdbManager,
    private val display: AgentDisplaySession,
    private val appScope: AppScope,
) {

    private val starting = AtomicBoolean(false)

    init {
        client.onDied = {
            appScope.launch(Dispatchers.IO) { recoverAfterDeath() }
        }
        display.onUserStopped = { releaseLocalOnlyHotspot() }
    }

    /** 纯流量冷启动时短暂持有，拉起结束再关——立刻关射频会跟着灭 */
    @Volatile
    private var localOnlyReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * 拔 USB 时 adbd 把整组 cgroup SIGKILL，屏一起没。用户开过虚拟屏会话的话
     * 自动再拉一次壳、再建一块屏。无线调试当时如果也关了，会走仅本地热点那条。
     */
    private suspend fun recoverAfterDeath() {
        display.forgetRemote()
        if (!display.keepAlive) return
        Log.i(TAG, "shell died with virtual display keep-alive; relaunching")
        delay(2.seconds)
        var last: String? = null
        repeat(2) { attempt ->
            if (!display.keepAlive) return
            val result = startViaLocalAdb(fromRecovery = true)
            if (result.isSuccess) {
                runCatching { display.start() }
                    .onFailure { Log.e(TAG, "recreate virtual display after relaunch", it) }
                    .onSuccess { Log.i(TAG, "virtual display recreated ${it.displayId}") }
                return
            }
            last = result.exceptionOrNull()?.message
            Log.w(TAG, "relaunch attempt ${attempt + 1} failed: $last")
            delay(2.seconds)
        }
        client.markFailed(
            last ?: "壳被杀掉后没接到本机 5555，无线调试也没了。插一次电脑执行 adb tcpip 5555，或开 WiFi 再点拉起。",
        )
    }

    /**
     * 配对：把这台设备的 key 交给 adbd 授权。一次性——之后连接不再需要配对码。
     *
     * @param port 无线调试页「使用配对码配对设备」里显示的**配对端口**（不是连接端口）。
     * @param code 六位配对码。
     */
    suspend fun pair(code: String, port: Int, host: String = "127.0.0.1"): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@withContext Result.failure(
                    IllegalStateException("应用内配对需要 Android 12 及以上；旧系统请复制 adb 命令到电脑执行"),
                )
            }
            Log.i(TAG, "pair: $host:$port code.len=${code.length}")
            runCatching {
                withTimeout(20.seconds) {
                    synchronized(manager) { manager.pair(host, port, code) }
                }
                Unit
            }.onFailure { Log.e(TAG, "pair failed", it) }
                .onSuccess { Log.i(TAG, "pair: ok") }
        }

    /**
     * 配对 + 拉起的完整编排。设置页输入框和通知栏回复都走这一个入口。
     * 失败时把状态推到 [PrivilegedClient.State.Failed]，设置页读 [PrivilegedClient.lastError]。
     */
    suspend fun pairAndStart(code: String, port: Int, host: String = "127.0.0.1"): Boolean {
        client.markStarting()
        val paired = pair(code, port, host)
        if (paired.isFailure) {
            client.markFailed(
                "配对失败：${paired.exceptionOrNull()?.message ?: "请核对配对端口与六位码"}",
            )
            return false
        }
        return startViaLocalAdb(host).isSuccess
    }

    /**
     * 连本机 adbd、拉起壳进程。
     *
     * 优先明文 [TCPIP_PORT]（不靠 WiFi）。没有的话才扫无线调试 TLS 端口；
     * 连上 TLS 之后立刻切到 tcpip，避免下次关 WiFi / 拔 USB 又没了。
     *
     * 无线调试没配对时会抛 [AdbPairingRequiredException]，翻成先配对的提示。
     */
    suspend fun startViaLocalAdb(host: String = "127.0.0.1", fromRecovery: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (client.state.value == PrivilegedClient.State.Ready && client.service() != null) {
                return@withContext Result.success(Unit)
            }
            if (!starting.compareAndSet(false, true)) {
                return@withContext Result.failure(IllegalStateException("正在拉起"))
            }
            client.markStarting()
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    error("应用内拉起需要 Android 12 及以上；旧系统请复制 adb 命令到电脑执行")
                }
                withTimeout(45.seconds) {
                    connectAndLaunch(host)
                    val ok = client.waitUntilReady(timeoutMs = 15_000)
                    if (!ok) error(client.lastError ?: "未拿到壳进程 Binder")
                }
                Result.success(Unit)
            } catch (e: AdbPairingRequiredException) {
                Log.e(TAG, "startViaLocalAdb: pairing required", e)
                if (!fromRecovery) {
                    client.markFailed("这台设备还没配对。请先在无线调试页用「配对码」配一次对")
                }
                Result.failure(e)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "startViaLocalAdb timed out", e)
                if (!fromRecovery) {
                    client.markFailed("等待壳进程超时——请确认命令已执行，且 Min 在前台")
                }
                Result.failure(e)
            } catch (e: CancellationException) {
                // 离开设置页、切网重建 Activity 都会取消 ViewModel 的 job。
                // 这不是拉起失败，千万别把 "Job was cancelled" 写到红字里。
                Log.i(TAG, "startViaLocalAdb cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startViaLocalAdb failed", e)
                if (!fromRecovery) {
                    client.markFailed(e.message ?: e.javaClass.simpleName)
                }
                Result.failure(e)
            } finally {
                // 虚拟屏还要保活时不能关热点：关了射频，无线调试又灭，壳再被 SIGKILL。
                if (!display.keepAlive) releaseLocalOnlyHotspot()
                runCatching { synchronized(manager) { manager.disconnect() } }
                starting.set(false)
            }
        }

    /**
     * 先明文 :5555，没有再走无线调试。TLS 连上立刻切 tcpip，避免下次关 WiFi / 拔 USB 又没了。
     * [sendLaunchCommand] 必须在已经连上的 manager 上调。
     */
    private suspend fun connectAndLaunch(host: String) {
        if (tryConnect(host, TCPIP_PORT, plaintext = true)) {
            Log.i(TAG, "connected plaintext :$TCPIP_PORT")
            synchronized(manager) { sendLaunchCommand() }
            return
        }
        ensureWirelessDebuggingOn()
        val tlsPort = waitForConnectPort(host)
            ?: error(
                "没接到本机 $TCPIP_PORT，也没扫到无线调试端口。" +
                    "插电脑执行 adb tcpip $TCPIP_PORT，或打开 WiFi 后再点拉起。",
            )
        Log.i(TAG, "connected wireless :$tlsPort, switching to tcpip")
        synchronized(manager) {
            manager.setApi(Build.VERSION.SDK_INT)
            manager.setTimeout(30_000L, TimeUnit.MILLISECONDS)
            manager.connect(host, tlsPort)
            requestTcpipMode()
            runCatching { manager.disconnect() }
        }
        waitUntilTcpipListening(host)
        if (!tryConnect(host, TCPIP_PORT, plaintext = true)) {
            error("已切到 tcpip 但连不上 :$TCPIP_PORT")
        }
        synchronized(manager) { sendLaunchCommand() }
    }

    private fun tryConnect(host: String, port: Int, plaintext: Boolean): Boolean {
        if (!portOpen(host, port)) return false
        synchronized(manager) {
            runCatching { manager.disconnect() }
            manager.setApi(if (plaintext) Build.VERSION_CODES.N else Build.VERSION.SDK_INT)
            // 明文 AUTH 可能要过 RSA，2s 探活超时不够
            manager.setTimeout(if (plaintext) 8_000L else ADB_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return try {
                val ok = manager.connect(host, port)
                if (!ok) runCatching { manager.disconnect() }
                ok
            } catch (e: AdbPairingRequiredException) {
                runCatching { manager.disconnect() }
                throw e
            } catch (t: Throwable) {
                Log.i(TAG, "connect $host:$port plaintext=$plaintext: ${t.message}")
                runCatching { manager.disconnect() }
                false
            }
        }
    }

    private fun sendLaunchCommand() {
        manager.setTimeout(30_000L, TimeUnit.MILLISECONDS)
        val cmd = launchCommand()
        Log.i(TAG, "shell: $cmd")
        // 走 openStream("shell:"+cmd)，不要 LocalServices.SHELL：它会把带空格的
        // 整条命令再套一层双引号，adbd 当成单个可执行文件名，命令根本不跑。
        // 命令末尾 sleep 2：给 setsid 时间把 app_process 拉起来再关 PTY。
        // 关流时 AdbStream.read 会抛 "Stream closed."——这是预期，不当失败。
        manager.openStream("shell:$cmd").use { stream ->
            runCatching {
                stream.openInputStream().use { it.readBytes() }
            }.onFailure { Log.i(TAG, "shell stream ended: ${it.message}") }
        }
    }

    /** 让 adbd 切到明文 TCP。这条会重启 adbd，当前连接随即断开。 */
    private fun requestTcpipMode() {
        Log.i(TAG, "request tcpip:$TCPIP_PORT")
        runCatching {
            manager.openStream("tcpip:$TCPIP_PORT").use { stream ->
                runCatching { stream.openInputStream().use { it.readBytes() } }
            }
        }.onFailure { Log.i(TAG, "tcpip stream: ${it.message}") }
    }

    private suspend fun waitUntilTcpipListening(host: String) {
        repeat(20) {
            if (portOpen(host, TCPIP_PORT)) {
                delay(300)
                return
            }
            delay(250)
        }
        error("adbd 没有在 :$TCPIP_PORT 听。请插电脑执行 adb tcpip $TCPIP_PORT")
    }

    /**
     * 给电脑端 `adb shell` 用的同一条命令。开发期验证壳服务时直接：
     * `adb shell "$(…)"` 或由设置页「复制命令」导出。
     */
    fun launchCommand(): String {
        val apk = context.applicationInfo.sourceDir
        val authority = PrivilegedBridgeProvider.authority(context)
        val pkg = context.packageName
        val cls = PrivilegedServer::class.java.name
        val script = "/data/local/tmp/start-min-priv.sh"
        val log = "/data/local/tmp/min-privileged.log"
        // 单行 printf 写脚本再跑。嵌套 sh -c 引号会把 trap/CLASSPATH 弄断。
        // 子 shell 里先把 0/1/2 从 PTY 挪走、关掉 3–15（adb 继承的 pts/socket），
        // 再 exec app_process；外层立刻 exit。只 setsid 不够——还握着 PTY 时
        // 拔 USB 内核照样给控制终端发 SIGHUP，壳和虚拟屏一起没。
        return buildString {
            append("printf '%s\\n' ")
            append("'#!/system/bin/sh' ")
            append("'trap \"\" HUP' ")
            append("'export CLASSPATH=").append(apk).append("' ")
            append("'(' ")
            append("'trap \"\" HUP' ")
            append("'exec 0</dev/null' ")
            append("'exec 1>>").append(log).append("' ")
            append("'exec 2>&1' ")
            append("'exec 3>&-; exec 4>&-; exec 5>&-; exec 6>&-; exec 7>&-; exec 8>&-; exec 9>&-' ")
            append("'exec /system/bin/app_process /system/bin --nice-name=min-privileged ")
            append(cls).append(' ').append(authority).append(' ').append(pkg).append("' ")
            append("') &' ")
            append("'exit 0' ")
            append("> $script && chmod 755 $script && sh $script ; sleep 2")
        }
    }

    /**
     * 用户把命令拷到电脑上跑时用：进入 Starting，并在后台等 Provider 收到交接。
     * 外部 `adb shell` 把壳拉起来后，Provider 一收到 Binder 这边就会 Ready。
     */
    fun markWaitingForExternalStart() {
        client.markStarting()
        Thread({
            client.waitUntilReady(timeoutMs = 60_000)
        }, "min-priv-wait").apply { isDaemon = true }.start()
    }

    /**
     * 尽量把无线调试打开。顺序：
     * 1. 已经开着 → 直接过。
     * 2. 射频关着 → 仅本地热点点亮（不连任何网）。失败就让用户自己开 WiFi / 热点。
     * 3. 有 WRITE_SECURE_SETTINGS → 自己写 adb_wifi_enabled。
     * 4. 都没有 → 把原因抛给设置页。
     */
    private suspend fun ensureWirelessDebuggingOn() {
        // 射频关着时就算 adb_wifi 还读到 1 也不可信——关 WiFi 的瞬间开关还没清掉，
        // 这时候去扫端口一定空。必须先把仅本地热点拉起来。
        if (!wifiRadioOn()) {
            // vivo 的 adb_wifi 只认真正的 STA WiFi；仅本地热点点亮射频不够，开关会被吞掉。
            runCatching {
                @Suppress("DEPRECATION")
                context.applicationContext.getSystemService(WifiManager::class.java)
                    ?.isWifiEnabled = true
            }.onFailure { Log.w(TAG, "setWifiEnabled", it) }
            if (canWriteSecureSettings()) {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, "wifi_on", 1)
                }.onFailure { Log.w(TAG, "put wifi_on", it) }
            }
            var waited = 0
            while (waited < 12 && !wifiRadioOn()) {
                delay(250)
                waited++
            }
            if (!wifiRadioOn()) {
                val hot = startLocalOnlyHotspotBriefly()
                if (!hot && !wifiRadioOn()) {
                    error(wirelessDebuggingOffReason() ?: "无线调试已关")
                }
            }
        }
        if (isAdbWifiEnabled()) return
        if (canWriteSecureSettings()) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            }.onFailure { Log.w(TAG, "put adb_wifi_enabled", it) }
            repeat(12) {
                if (isAdbWifiEnabled()) return
                delay(250)
            }
        }
        if (isAdbWifiEnabled()) return
        error(wirelessDebuggingOffReason() ?: "无线调试已关")
    }

    /** 写完 adb_wifi 之后 TLS 端口要过一会儿才听。连扫两次，别空转几分钟。 */
    private suspend fun waitForConnectPort(host: String): Int? {
        repeat(2) { i ->
            val port = discoverConnectPort(host)
            if (port != null) return port
            Log.i(TAG, "connect port not up yet, retry ${i + 1}")
            delay(800)
        }
        return null
    }

    /**
     * 仅本地热点：公开 API，点亮射频但不连网、也不开个人热点。
     * 预约拿到后一直拿到拉起结束（[releaseLocalOnlyHotspot]）——立刻关，射频会跟着灭。
     * Android 13+ 要 NEARBY_WIFI_DEVICES；没授就当失败，上层走原来的提示。
     */
    private suspend fun startLocalOnlyHotspotBriefly(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
            if (!nearby) {
                Log.i(TAG, "local-only hotspot skipped: no NEARBY_WIFI_DEVICES")
                return false
            }
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return false
        releaseLocalOnlyHotspot()
        return try {
            withTimeout(12.seconds) {
                suspendCancellableCoroutine { cont ->
                    val main = Handler(Looper.getMainLooper())
                    val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                            Log.i(TAG, "local-only hotspot started, radio up")
                            if (reservation == null) {
                                if (cont.isActive) cont.resume(false)
                                return
                            }
                            localOnlyReservation = reservation
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onFailed(reason: Int) {
                            Log.w(TAG, "local-only hotspot failed reason=$reason")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onStopped() {
                            Log.i(TAG, "local-only hotspot stopped")
                        }
                    }
                    main.post {
                        runCatching { wifi.startLocalOnlyHotspot(cb, main) }
                            .onFailure {
                                Log.w(TAG, "startLocalOnlyHotspot", it)
                                if (cont.isActive) cont.resume(false)
                            }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "local-only hotspot timed out", e)
            releaseLocalOnlyHotspot()
            false
        }.also { Log.i(TAG, "local-only hotspot result=$it radio=${wifiRadioOn()}") }
    }

    private fun releaseLocalOnlyHotspot() {
        val held = localOnlyReservation
        localOnlyReservation = null
        if (held != null) {
            runCatching { held.close() }
                .onFailure { Log.w(TAG, "close local-only hotspot", it) }
        }
    }

    private fun isAdbWifiEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun wifiRadioOn(): Boolean {
        if (localOnlyReservation != null) return true
        val wifiOn = context.applicationContext
            .getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val apOn = Settings.Global.getInt(context.contentResolver, "wifi_ap_on", 0) != 0
        return wifiOn || apOn
    }

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 射频关着、又没「附近的设备」时，点拉起要先要这个权限才能走仅本地热点 */
    fun needsNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (wifiRadioOn()) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * 无线调试在这台机上挂在 WiFi 射频上：关 WiFi = 系统把开关一起拔掉。
     * 第一次需要 USB 或任意 WiFi 把壳拉起来一次（壳会授 WRITE_SECURE_SETTINGS）。
     * 之后纯流量可以走仅本地热点 + 自己写开关。
     */
    private fun wirelessDebuggingOffReason(): String? {
        if (isAdbWifiEnabled()) return null
        return if (!wifiRadioOn()) {
            if (canWriteSecureSettings()) {
                "没接到本机 $TCPIP_PORT，无线调试也关着。插电脑执行 adb tcpip $TCPIP_PORT，或打开 WiFi / 个人热点后再点拉起。"
            } else {
                "没接到本机 $TCPIP_PORT。第一次请插电脑执行 adb tcpip $TCPIP_PORT，或打开 WiFi 走无线调试；之后关 WiFi 也能自己拉。"
            }
        } else {
            "无线调试已关。请打开「开发者选项 → 无线调试」，或等第一次拉起成功后由壳授权，下次就能自己开。"
        }
    }

    /**
     * 找本机无线调试的连接端口。
     *
     * 不走 mDNS：vivo 等厂商的 adbd 不发 `_adb-tls-connect` 广播，也不应答查询，查了白查。
     * 端口是系统在 [PORT_RANGE] 里随机分的，所以并发把区间里每个开着的端口都试真连——
     * 只有真正的 adb-tls-connect 端口能完成 TLS 握手，其它端口握手即失败、很快淘汰。
     *
     * 没配对时真连会抛 [AdbPairingRequiredException]，这里放它冒泡上去，让上层提示配对。
     */
    private suspend fun discoverConnectPort(host: String): Int? {
        val open = coroutineScope {
            val gate = Semaphore(PORT_SCAN_PARALLELISM)
            PORT_RANGE.map { port ->
                async(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    gate.withPermit { if (portOpen(host, port)) port else null }
                }
            }.awaitAll().filterNotNull()
        }
        Log.i(TAG, "port scan: ${open.size} open ${open.take(12)}")
        return open.firstOrNull { tryAdbConnect(host, it) }
    }

    /** 粗扫只判断 TCP 开没开，不握手——没开的端口马上拒绝，这一轮才快 */
    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    /** 对开着的端口试真正的 ADB+TLS 连接：连上就是它，随即断开交给正式流程重连 */
    private fun tryAdbConnect(host: String, port: Int): Boolean = synchronized(manager) {
        try {
            manager.setApi(Build.VERSION.SDK_INT)
            manager.setTimeout(ADB_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val ok = manager.connect(host, port)
            manager.disconnect()
            ok
        } catch (e: AdbPairingRequiredException) {
            throw e // 端口对了但没配对——冒泡上去
        } catch (_: Throwable) {
            runCatching { manager.disconnect() }
            false
        }
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** API 30 以下没有无线调试配对 API，只能 USB adb 或真屏档；应用内 TLS 配对需要 31+ */
        fun supportsInAppStart(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** `adb tcpip` 默认端口。明文、不靠 WiFi 射频，关 WiFi / 拔 USB 都还在。 */
        private const val TCPIP_PORT = 5555

        /** 无线调试连接端口的分配区间（AdbService 的常量） */
        private val PORT_RANGE = 30000..50000

        /** 并发探测的路数。关着的端口马上拒绝，200 路扫完两万端口是几秒的事 */
        private const val PORT_SCAN_PARALLELISM = 200

        private const val PORT_PROBE_TIMEOUT_MS = 200

        /** adb 握手的连接与读超时。不是 adb 的端口握手不成，不限时就会把扫描拖死 */
        private const val ADB_HANDSHAKE_TIMEOUT_MS = 2_000L
    }
}
