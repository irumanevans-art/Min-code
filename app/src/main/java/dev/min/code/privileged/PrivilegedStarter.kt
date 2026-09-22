package dev.min.code.privileged

import android.content.Context
import android.os.Build
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MinPrivStarter"

/**
 * 把 [PrivilegedServer] 以 shell uid 拉起来。
 *
 * ## 两条路
 *
 * 1. **应用内**（API 30+）：用户打开无线调试后，对本机 `localhost:连接端口` 用
 *    [Dadb] 发 `app_process`。配对码只在首次需要。
 * 2. **电脑 adb**：开发期 / 旧系统兜底——`adb shell` 跑同一条命令。
 *
 * 两条路的命令相同，见 [launchCommand]。
 *
 * ## 做不到的
 *
 * 不能替用户打开「无线调试」开关（没有权限）。也不能在无 root 下开机自启。
 * 这两条和官方 Shizuku 一样，设置页必须写明。
 */
class PrivilegedStarter(
    private val context: Context,
    private val client: PrivilegedClient,
) {

    private val starting = AtomicBoolean(false)

    /**
     * 通过已经连上的本机无线调试端口拉起壳进程。
     *
     * @param port 无线调试「连接端口」（不是配对端口）。设置页里用户能看到，
     *   或由 [discoverConnectPort] 扫出来。
     */
    suspend fun startViaLocalAdb(port: Int, host: String = "127.0.0.1"): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!starting.compareAndSet(false, true)) {
                return@withContext Result.failure(IllegalStateException("正在拉起"))
            }
            client.markStarting()
            try {
                withTimeout(20.seconds) {
                    Dadb.create(host, port).use { dadb ->
                        val cmd = launchCommand()
                        Log.i(TAG, "shell: $cmd")
                        // 后台拉起：app_process 会卡在 Looper，不能同步等 exit
                        dadb.shell(cmd)
                    }
                    // 并行去 LocalSocket 取 Binder
                    val ok = client.fetchBinderFromSocket(timeoutMs = 15_000)
                    if (!ok) error(client.lastError ?: "未拿到壳进程 Binder")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "startViaLocalAdb failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                client.markFailed(msg)
                Result.failure(e)
            } finally {
                starting.set(false)
            }
        }

    /**
     * 给电脑端 `adb shell` 用的同一条命令。开发期验证壳服务时直接：
     * `adb shell "$(…)"` 或由设置页「复制命令」导出。
     */
    fun launchCommand(): String {
        val apk = context.applicationInfo.sourceDir
        val authority = PrivilegedBridgeProvider.authority(context)
        // Android 14+ 对可写 DEX 更严；APK 本身是只读的，CLASSPATH 指向 APK 即可，
        // 不必像 rish 那样先 chmod 一份拷贝
        return buildString {
            append("CLASSPATH=").append(shellQuote(apk))
            append(" app_process /system/bin")
            append(" --nice-name=min-privileged")
            append(" ").append(PrivilegedServer::class.java.name)
            append(" ").append(shellQuote(authority))
            // 后台跑：否则 adb shell 会话一断进程就没
            append(" >/data/local/tmp/min-privileged.log 2>&1 &")
        }
    }

    /**
     * 用户把命令拷到电脑上跑时用：进入 Starting，并在 IO 上轮询 LocalSocket。
     * 外部 `adb shell` 把壳拉起来后，这边会自动取到 Binder。
     */
    fun markWaitingForExternalStart() {
        client.markStarting()
        Thread({
            client.fetchBinderFromSocket(timeoutMs = 60_000)
        }, "min-priv-wait").apply { isDaemon = true }.start()
    }

    /**
     * 粗暴扫描本机常见无线调试端口是否已经能连上 adb。
     *
     * 完整的 mDNS 配对发现更准，但实现量大；先用扫描让「用户已打开无线调试、
     * 只差一键拉起」这条路能走通。扫到第一个能 `shell id` 返回 shell/uid 的端口即用。
     */
    suspend fun discoverConnectPort(
        candidates: IntRange = 30000..50000,
        step: Int = 37,
    ): Int? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        // 步进采样，避免扫 2 万个端口；无线调试端口是系统随机分配的，步进不保证
        // 一次命中——设置页仍允许手填。命中后再验证 id 命令
        var p = candidates.first
        while (p <= candidates.last) {
            runCatching {
                Dadb.create("127.0.0.1", p).use { dadb ->
                    val out = dadb.shell("id").allOutput
                    if (out.contains("uid=2000") || out.contains("uid=0")) return@withContext p
                }
            }
            p += step
        }
        null
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** API 30 以下没有无线调试配对 API，只能 USB adb 或真屏档 */
        fun supportsInAppStart(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}
