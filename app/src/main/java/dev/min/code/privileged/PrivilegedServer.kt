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
        val systemContext = createShellContext()
            ?: run {
                Log.e(TAG, "failed to create shell context")
                return
            }
        // DisplayManager 要求 Context.getPackageName() 与 calling uid 一致。
        // system Context 的 package 是 "android"（uid 1000），我们是 2000 →
        // SecurityException: packageName must match the calling uid。
        // 换成 com.android.shell 的 package Context（uid 2000 的正主）。
        val displayContext = runCatching {
            systemContext.createPackageContext(
                "com.android.shell",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        }.onFailure { Log.e(TAG, "createPackageContext(com.android.shell)", it) }
            .getOrElse { systemContext }
        Log.i(TAG, "displayContext package=${displayContext.packageName}")
        val service = ServiceImpl(displayContext)
        val binder = service.asBinder()
        if (!handOverBinder(systemContext, appPackage, authority, binder)) {
            Log.e(TAG, "handover to $authority failed")
            return
        }
        Log.i(TAG, "binder handed to $authority; looping (re-handover every ${REHANDOVER_MS}ms)")
        // App 被 force-stop 后会丢掉 Binder；壳进程还活着、虚拟屏也还在。
        // 定时再交一次，让用户重新打开 Min 时能自动接上，不必再走 adb。
        val handler = android.os.Handler(Looper.getMainLooper())
        val rehandover = object : Runnable {
            override fun run() {
                val ok = handOverBinder(systemContext, appPackage, authority, binder)
                if (!ok) Log.w(TAG, "re-handover failed (App may be stopped)")
                handler.postDelayed(this, REHANDOVER_MS)
            }
        }
        handler.postDelayed(rehandover, REHANDOVER_MS)
        Looper.loop()
    }

    private const val REHANDOVER_MS = 3_000L

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
        @Suppress("UNUSED_PARAMETER") systemContext: Context,
        @Suppress("UNUSED_PARAMETER") appPackage: String,
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
