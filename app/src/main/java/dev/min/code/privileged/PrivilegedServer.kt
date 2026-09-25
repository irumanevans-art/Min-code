package dev.min.code.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import java.util.concurrent.TimeUnit

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
        detachFromControllingTerminal()
        leaveAdbdCgroup()
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
        // 第一次拉起顺手把 WRITE_SECURE_SETTINGS 授给 App：之后纯流量冷启动
        // 可以自己写 adb_wifi_enabled，不必再插电脑或开公网 WiFi。
        val appPackage = args.getOrNull(1) ?: authority.removeSuffix(".privileged")
        grantWriteSecureSettings(appPackage)
        val displayContext = createShellContext()
            ?: run {
                Log.e(TAG, "failed to create shell context")
                return
            }
        Log.i(TAG, "displayContext package=${displayContext.packageName}")
        val service = ServiceImpl(displayContext)
        val binder = service.asBinder()
        if (!handOverBinder(authority, binder)) {
            Log.e(TAG, "handover to $authority failed")
            return
        }
        Log.i(TAG, "binder handed to $authority; looping (re-handover every ${REHANDOVER_MS}ms)")
        // App 被 force-stop 后会丢掉 Binder；壳进程还活着、虚拟屏也还在。
        // 定时再交一次，让用户重新打开 Min 时能自动接上，不必再走 adb。
        val handler = android.os.Handler(Looper.getMainLooper())
        val rehandover = object : Runnable {
            override fun run() {
                val ok = handOverBinder(authority, binder)
                if (!ok) Log.w(TAG, "re-handover failed (App may be stopped)")
                handler.postDelayed(this, REHANDOVER_MS)
            }
        }
        handler.postDelayed(rehandover, REHANDOVER_MS)
        Looper.loop()
    }

    private const val REHANDOVER_MS = 3_000L
    private const val SHELL_PACKAGE = "com.android.shell"

    /**
     * USB 拔线 / 无线调试关掉时 adbd 拆 PTY，内核给**还握着这个终端 fd** 的进程发 SIGHUP。
     * 只 dup2 0/1/2 不够，adb 还会把 pts 留在 3、4、5… 上。那些 fd 必须关。
     * 只关路径里带 pts/tty 的，ART 的 binder/apk fd 动不得。
     */
    private fun detachFromControllingTerminal() {
        runCatching { android.system.Os.setsid() }
            .onFailure { Log.w(TAG, "setsid", it) }
        runCatching {
            val oRdwr = android.system.OsConstants.O_RDWR
            val oWrite = android.system.OsConstants.O_WRONLY or
                android.system.OsConstants.O_CREAT or
                android.system.OsConstants.O_APPEND
            val devNull = android.system.Os.open("/dev/null", oRdwr, 0)
            android.system.Os.dup2(devNull, 0)
            val logFd = android.system.Os.open("/data/local/tmp/min-privileged.log", oWrite, 420)
            android.system.Os.dup2(logFd, 1)
            android.system.Os.dup2(logFd, 2)
            runCatching { android.system.Os.close(devNull) }
            runCatching { android.system.Os.close(logFd) }
        }.onFailure { Log.w(TAG, "redirect stdio", it) }
        closeInheritedTtys()
    }

    /**
     * adbd 把从它 fork 出来的进程放进 `/system/uid_0/pid_<adbd>`。
     * 拔 USB 时内核 SIGKILL 整组，setsid/nohup 挡不住。
     * 尽量把自己迁到 uid_2000；SELinux 多半不让，失败就靠死后自动重拉。
     */
    private fun leaveAdbdCgroup() {
        val pid = Process.myPid().toString()
        val here = runCatching { java.io.File("/proc/self/cgroup").readText() }.getOrDefault("")
        if ("uid_0/pid_" !in here && "/pid_" !in here.substringAfter("uid_0", "")) {
            Log.i(TAG, "cgroup ok: ${here.trim()}")
            return
        }
        val targets = arrayOf(
            "/sys/fs/cgroup/system/uid_2000/cgroup.procs",
            "/sys/fs/cgroup/system/cgroup.procs",
            "/sys/fs/cgroup/cgroup.procs",
        )
        for (path in targets) {
            val ok = runCatching {
                java.io.FileOutputStream(path).use { it.write(pid.toByteArray()) }
            }.isSuccess
            if (ok) {
                val now = runCatching { java.io.File("/proc/self/cgroup").readText() }.getOrDefault("")
                Log.i(TAG, "left adbd cgroup via $path now=${now.trim()}")
                return
            }
        }
        Log.w(TAG, "still in adbd cgroup (USB unplug will SIGKILL): ${here.trim()}")
    }

    private fun closeInheritedTtys() {
        val names = java.io.File("/proc/self/fd").list() ?: return
        for (name in names) {
            val n = name.toIntOrNull() ?: continue
            if (n <= 2) continue
            val target = runCatching { android.system.Os.readlink("/proc/self/fd/$n") }
                .getOrNull() ?: continue
            if ("pts" !in target && "/tty" !in target && "ptmx" !in target) continue
            Log.i(TAG, "closing inherited tty fd=$n -> $target")
            runCatching { android.os.ParcelFileDescriptor.adoptFd(n).close() }
        }
    }

    /**
     * 壳 uid 才能 pm grant 这条签名级权限。失败不挡主流程——虚拟屏照建，
     * 只是下次冷启动还得靠 WiFi / USB。
     */
    private fun grantWriteSecureSettings(pkg: String) {
        if (pkg.isBlank()) return
        runCatching {
            val p = ProcessBuilder(
                "/system/bin/cmd",
                "package",
                "grant",
                pkg,
                "android.permission.WRITE_SECURE_SETTINGS",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = p.waitFor()
            Log.i(TAG, "pm grant WRITE_SECURE_SETTINGS pkg=$pkg exit=$code out=$out")
        }.onFailure { Log.w(TAG, "pm grant WRITE_SECURE_SETTINGS failed", it) }
    }

    /**
     * 反射拿到一个能调 DisplayManager / startActivity 的 Context。
     * scrcpy / 扶摇同款：`ActivityThread.systemMain().getSystemContext()`，
     * 再把进程伪装成 [SHELL_PACKAGE]，否则 AMS 看到 package=android、uid=2000 直接拒。
     */
    @SuppressLint("PrivateApi")
    private fun createShellContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val activityThread = atClass.getDeclaredMethod("systemMain").invoke(null)
            ?: error("ActivityThread.systemMain returned null")
        disguiseAsShell(atClass, activityThread)
        val systemContext = atClass.getDeclaredMethod("getSystemContext")
            .invoke(activityThread) as Context
        runCatching {
            systemContext.createPackageContext(
                SHELL_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        }.onFailure { Log.e(TAG, "createPackageContext($SHELL_PACKAGE)", it) }
            .getOrDefault(systemContext)
    }.onFailure { Log.e(TAG, "createShellContext", it) }.getOrNull()

    /**
     * scrcpy Workarounds.fillAppInfo：给 ActivityThread 塞一份
     * packageName=com.android.shell 的 AppBindData。
     * 不塞的话 Context.startActivity / DisplayManager 都会把调用方报成 android。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun disguiseAsShell(atClass: Class<*>, activityThread: Any) {
        runCatching {
            val dataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val boundField = atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }
            var data = boundField.get(activityThread)
            if (data == null) {
                data = dataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                boundField.set(activityThread, data)
            }
            val info = ApplicationInfo().apply {
                packageName = SHELL_PACKAGE
                processName = SHELL_PACKAGE
            }
            dataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(data, info)
            runCatching {
                dataClass.getDeclaredField("processName").apply { isAccessible = true }.set(data, SHELL_PACKAGE)
            }
            Log.i(TAG, "disguised ActivityThread as $SHELL_PACKAGE")
        }.onFailure { Log.w(TAG, "disguiseAsShell", it) }
    }

    /**
     * 通过 [IActivityManager.getContentProviderExternal] + [IContentProvider.call] 交 Binder。
     *
     * 真机踩坑两条：
     * 1. 普通 `ContentResolver.call`：system Context 的 package 是 `android`，uid 是 2000，
     *    AMS 直接 `SecurityException: Given calling package android does not match…`
     * 2. abstract LocalSocket：App 连 shell 建的套接字被 SELinux 拒掉（`Permission denied`）
     *
     * `getContentProviderExternal` 是给 shell/system 用的旁路，不走 calling-package 校验；
     * Shizuku 也是这条路。拿到 IContentProvider 后再 call 我们的 Provider。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun handOverBinder(
        authority: String,
        binder: IBinder,
    ): Boolean =
        runCatching {
            val extras = android.os.Bundle()
            extras.putBinder(PrivilegedBridgeProvider.EXTRA_BINDER, binder)

            val atClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val getApplicationThread = atClass.getDeclaredMethod("getApplicationThread")
            val caller = getApplicationThread.invoke(currentActivityThread) as IBinder

            val amClass = Class.forName("android.app.IActivityManager")
            val smClass = Class.forName("android.os.ServiceManager")
            val amBinder = smClass.getDeclaredMethod("getService", String::class.java)
                .invoke(null, "activity") as IBinder
            val amStub = Class.forName("android.app.IActivityManager\$Stub")
            val am = amStub.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, amBinder)

            // ContentProviderHolder provider = am.getContentProviderExternal(auth, userId, token, tag)
            val userId = Process.myUid() / 100000
            val holder = runCatching {
                amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                ).invoke(am, authority, userId, caller, "*min*")
            }.recoverCatching {
                // 旧签名没有 tag 参数
                amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                ).invoke(am, authority, userId, caller)
            }.getOrThrow()

            val providerField = holder.javaClass.getDeclaredField("provider").apply { isAccessible = true }
            val provider = providerField.get(holder)
                ?: error("getContentProviderExternal returned null provider — is Min installed / provider registered?")

            val providerClass = Class.forName("android.content.IContentProvider")
            val attribution = android.content.AttributionSource.Builder(Process.myUid())
                .setPackageName("com.android.shell")
                .build()

            // API 31+: call(AttributionSource, authority, method, arg, extras)
            runCatching {
                providerClass.getMethod(
                    "call",
                    android.content.AttributionSource::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    android.os.Bundle::class.java,
                ).invoke(
                    provider,
                    attribution,
                    authority,
                    PrivilegedBridgeProvider.METHOD_HANDOVER,
                    null,
                    extras,
                )
            }.recoverCatching {
                // API 30: call(package, featureId, authority, method, arg, extras)
                providerClass.getMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    android.os.Bundle::class.java,
                ).invoke(
                    provider,
                    "com.android.shell",
                    null,
                    authority,
                    PrivilegedBridgeProvider.METHOD_HANDOVER,
                    null,
                    extras,
                )
            }.recoverCatching {
                // 更旧: call(package, authority, method, arg, extras)
                providerClass.getMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    android.os.Bundle::class.java,
                ).invoke(
                    provider,
                    "com.android.shell",
                    authority,
                    PrivilegedBridgeProvider.METHOD_HANDOVER,
                    null,
                    extras,
                )
            }.getOrThrow()

            runCatching {
                amClass.getMethod(
                    "removeContentProviderExternal",
                    String::class.java,
                    IBinder::class.java,
                ).invoke(am, authority, caller)
            }
            Log.i(TAG, "binder handed via getContentProviderExternal to $authority")
            true
        }.onFailure { Log.e(TAG, "handOverBinder", it) }.getOrDefault(false)

    private fun virtualDisplayFlags(): Int = VirtualDisplayFlags.forSdk(android.os.Build.VERSION.SDK_INT)

    private class ServiceImpl(private val context: Context) : IPrivilegedService.Stub() {
        private data class Held(
            val display: VirtualDisplay,
            val reader: ImageReader,
        )

        private val held = ConcurrentHashMap<Int, Held>()

        override fun getUid(): Int = Process.myUid()

        override fun ping(): String = "min-privileged uid=${Process.myUid()} displays=${held.size}"

        override fun listAgentDisplays(): IntArray = held.keys.toIntArray()

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
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            )
            // 不要 Context.startActivity：vivo 上它不抛错、也不把 Activity 放到
            // launchDisplayId 那块屏，上层会当成「已打开」。shell 的正路是 am --display。
            amStartOnDisplay(launch, displayId)
            Log.i(TAG, "launched $packageName on displayId=$displayId via am")
        }

        private fun amStartOnDisplay(intent: Intent, displayId: Int) {
            val component = intent.component
                ?: error("launch intent has no component")
            val cmd = arrayOf(
                "/system/bin/am",
                "start",
                "--user",
                "0",
                "--display",
                displayId.toString(),
                "-n",
                component.flattenToShortString(),
                "-f",
                (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).toString(),
            )
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val ok = p.waitFor(8, TimeUnit.SECONDS)
            val code = if (ok) p.exitValue() else {
                p.destroyForcibly()
                -1
            }
            Log.i(TAG, "am start display=$displayId exit=$code out=$out")
            if (!ok || code != 0 || out.contains("Error", ignoreCase = true) ||
                out.contains("Exception", ignoreCase = true)
            ) {
                error(out.ifBlank { "am start failed exit=$code" })
            }
        }

        /** 壳进程死掉时尽量把 VD 清掉，避免残留幽灵屏 */
        protected fun finalize() {
            held.keys.toList().forEach { destroyAgentDisplay(it) }
        }
    }
}
