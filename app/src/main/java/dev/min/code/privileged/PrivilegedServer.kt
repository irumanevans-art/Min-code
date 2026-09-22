package dev.min.code.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 跑在 **shell uid (2000)** 的特权进程入口。
 *
 * ## 怎么起来
 *
 * App 通过无线调试 / USB adb 发：
 * ```
 * CLASSPATH=<apk> app_process /system/bin --nice-name=min-privileged \
 *   dev.min.code.privileged.PrivilegedServer <authority>
 * ```
 * 起来之后把 [IPrivilegedService] Binder 交给 App 的 [PrivilegedBridgeProvider]。
 *
 * ## 为什么必须是 shell 而不是 root
 *
 * DisplayManager 对 root 有 packageName/uid 一致性校验，root 建 TRUSTED 虚拟屏
 * 反而会 `SecurityException`（Ynkcc/VirtualDisplay、scrcpy 文档同款）。uid==0 时直接拒绝。
 *
 * ## 看不见
 *
 * Surface 接到本进程的 [ImageReader]，**不**接到任何物理屏 / TextureView。
 * 这和开发者选项的 `overlay_display_devices`（主屏上的可视浮窗）是两回事。
 */
object PrivilegedServer {

    private const val TAG = "MinPrivileged"


    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val uid = Process.myUid()
        Log.i(TAG, "starting uid=$uid args=${args.toList()}")
        if (uid == 0) {
            Log.e(TAG, "refusing to run as root — DisplayManager rejects TRUSTED VD from uid 0")
            return
        }
        if (uid != 2000) {
            Log.w(TAG, "uid=$uid is not shell(2000); VD may fail on some OEMs")
        }
        val authority = args.getOrNull(0)
            ?: run {
                Log.e(TAG, "missing provider authority argument")
                return
            }
        // authority = "<package>.privileged" → package 用来 createPackageContext
        val appPackage = authority.removeSuffix(".privileged")
        val context = createShellContext()
            ?: run {
                Log.e(TAG, "failed to create shell context")
                return
            }
        val service = ServiceImpl(context)
        if (!handOverBinder(context, appPackage, authority, service.asBinder())) {
            Log.e(TAG, "handover to $authority failed")
            return
        }
        Log.i(TAG, "binder handed to $authority; looping")
        Looper.loop()
    }

    /**
     * 反射拿到一个能调 DisplayManager / startActivity 的 Context。
     * scrcpy / uxspace 同款：`ActivityThread.systemMain().getSystemContext()`。
     */
    @SuppressLint("PrivateApi")
    private fun createShellContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val systemMain: Method = atClass.getDeclaredMethod("systemMain")
        val activityThread = systemMain.invoke(null)
        val getSystemContext: Method = atClass.getDeclaredMethod("getSystemContext")
        getSystemContext.invoke(activityThread) as Context
    }.onFailure { Log.e(TAG, "createShellContext", it) }.getOrNull()

    /**
     * 通过 abstract LocalServerSocket 把 Binder 交回 App。
     *
     * 真机踩坑：ContentProvider.call 从 system Context 发出时 AMS 校验
     * `package android` vs `uid 2000`，直接 SecurityException；createPackageContext
     * 也改不了 ContentResolver 里记的 calling package。跨 uid 交 Binder 的稳妥做法是
     * LocalSocket + Parcel.writeStrongBinder（Shizuku / 不少 app_process 服务同款）。
     *
     * 套接字名带 package，避免同机多个 Min 变体抢同一个抽象名。
     */
    private fun handOverBinder(
        @Suppress("UNUSED_PARAMETER") systemContext: Context,
        appPackage: String,
        @Suppress("UNUSED_PARAMETER") authority: String,
        binder: IBinder,
    ): Boolean =
        runCatching {
            val name = socketName(appPackage)
            // 先清可能残留的旧监听（上次崩溃没关掉）
            runCatching { android.net.LocalServerSocket(name).close() }
            val server = android.net.LocalServerSocket(name)
            Log.i(TAG, "waiting for app on @$name")
            server.use { ss ->
                // accept 会阻塞；App 侧 PrivilegedStarter / Client 连上来取 Binder
                val client = ss.accept()
                client.use { sock ->
                    val parcel = android.os.Parcel.obtain()
                    try {
                        parcel.writeStrongBinder(binder)
                        val bytes = parcel.marshall()
                        val out = java.io.DataOutputStream(sock.outputStream)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()
                    } finally {
                        parcel.recycle()
                    }
                }
            }
            Log.i(TAG, "binder sent over @$name")
            true
        }.onFailure { Log.e(TAG, "handOverBinder", it) }.getOrDefault(false)

    internal fun socketName(appPackage: String): String = "min.privileged.$appPackage"

    private fun virtualDisplayFlags(): Int = VirtualDisplayFlags.forSdk(android.os.Build.VERSION.SDK_INT)

    private class ServiceImpl(private val context: Context) : IPrivilegedService.Stub() {
        private data class Held(
            val display: VirtualDisplay,
            val reader: ImageReader,
        )

        private val held = ConcurrentHashMap<Int, Held>()

        override fun getUid(): Int = Process.myUid()

        override fun ping(): String = "min-privileged uid=${Process.myUid()} displays=${held.size}"

        override fun createAgentDisplay(width: Int, height: Int, densityDpi: Int): Int {
            require(width >= 200 && height >= 200) { "display too small: ${width}x$height" }
            require(densityDpi in 120..640) { "density out of range: $densityDpi" }
            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val surface: Surface = reader.surface
            val dm = context.getSystemService(DisplayManager::class.java)
                ?: error("DisplayManager missing")
            val flags = virtualDisplayFlags()
            Log.i(TAG, "createVirtualDisplay ${width}x$height@$densityDpi flags=0x${flags.toString(16)}")
            val vd = try {
                dm.createVirtualDisplay(
                    "min-agent-${System.currentTimeMillis()}",
                    width,
                    height,
                    densityDpi,
                    surface,
                    flags,
                )
            } catch (e: SecurityException) {
                reader.close()
                throw IllegalStateException(
                    "createVirtualDisplay rejected (uid=${Process.myUid()}): ${e.message}",
                    e,
                )
            } ?: run {
                reader.close()
                error("createVirtualDisplay returned null")
            }
            val id = vd.display.displayId
            held[id] = Held(vd, reader)
            Log.i(TAG, "created displayId=$id")
            return id
        }

        override fun destroyAgentDisplay(displayId: Int) {
            val h = held.remove(displayId) ?: return
            runCatching { h.display.release() }
            runCatching { h.reader.close() }
            Log.i(TAG, "destroyed displayId=$displayId")
        }

        override fun launchOnDisplay(packageName: String, displayId: Int) {
            require(packageName.isNotBlank()) { "empty package" }
            if (displayId != 0 && !held.containsKey(displayId)) {
                error("unknown displayId=$displayId — create it first")
            }
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage(packageName)
                ?: error("no launch activity for $packageName")
            launch.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            )
            val options = android.app.ActivityOptions.makeBasic()
            options.launchDisplayId = displayId
            context.startActivity(launch, options.toBundle())
            Log.i(TAG, "launched $packageName on displayId=$displayId")
        }

        /** 壳进程死掉时尽量把 VD 清掉，避免残留幽灵屏 */
        protected fun finalize() {
            held.keys.toList().forEach { destroyAgentDisplay(it) }
        }
    }
}
