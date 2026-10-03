package dev.min.code.debug

import android.util.Log
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MCP0 的设备验收探针（`op proc-probe`）：在真实 proot 里验证长寿进程端口。输出都以 `PROBE` 开头，
 * 最后一行 `PROBE_DONE ok=true|false`。release 源集不含。
 *
 * - echo：`cat` 的 stdin → stdout 往返，含中文与 300KB 数据，字节逐一比对
 * - env：`env` 里没有 App 进程的环境、没有任何 `*_API_KEY`
 * - tree：`sh` 里留一个忽略 SIGTERM 的孙进程（`sleep 6013`），shutdown 之后从宿主 /proc 看它是否还在
 * - cancel：调用 spawn 的协程被取消，同样看孙进程是否还在
 * - quota：连起超过上限个进程，最后一个必须被明确拒绝
 */
internal object ProcessProbe {
    private const val TAG = "MinAgentDebug"

    suspend fun run(processes: ProcessPort) {
        var ok = true
        fun check(name: String, pass: Boolean, detail: String = "") {
            Log.i(TAG, "PROBE $name ${if (pass) "OK" else "FAIL"} $detail".trim())
            if (!pass) ok = false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            echo(processes, scope, ::check)
            env(processes, scope, ::check)
            tree(processes, scope, ::check)
            cancel(processes, scope, ::check)
            quota(processes, ::check)
        } catch (e: Exception) {
            Log.e(TAG, "PROBE EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            ok = false
        } finally {
            scope.cancel()
        }
        Log.i(TAG, "PROBE_DONE ok=$ok")
    }

    private class Sink(flow: Flow<ByteArray>, scope: CoroutineScope) {
        private val buf = ByteArrayOutputStream()
        val job = scope.launch { flow.collect { synchronized(buf) { buf.write(it) } } }
        fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
        fun text() = bytes().toString(Charsets.UTF_8)
    }

    private fun spec(label: String, vararg argv: String, env: Map<String, String> = emptyMap()) =
        ProcessSpec(argv.toList(), GuestPath("/root"), env, label)

    private suspend fun echo(processes: ProcessPort, scope: CoroutineScope, check: (String, Boolean, String) -> Unit) {
        val p = processes.spawn(spec("probe-echo", "cat"))
        try {
            val out = Sink(p.stdout, scope)
            val payload = ByteArray(300_000) { ((it * 31) and 0xFF).toByte() } + "你好，世界\n".toByteArray()
            val writer = scope.launch { p.write(payload); p.closeStdin() }
            withTimeout(60_000) { writer.join() }
            val code = withTimeout(30_000) { p.exit.await() }
            out.job.join()
            check("echo", code == 0 && payload.contentEquals(out.bytes()), "exit=$code bytes=${out.bytes().size}/${payload.size}")
        } finally {
            p.shutdown()
        }
    }

    private suspend fun env(processes: ProcessPort, scope: CoroutineScope, check: (String, Boolean, String) -> Unit) {
        val p = processes.spawn(spec("probe-env", "env", env = mapOf("PROBE_FLAG" to "1")))
        try {
            val out = Sink(p.stdout, scope)
            withTimeout(30_000) { p.exit.await() }
            out.job.join()
            val text = out.text()
            Log.i(TAG, "PROBE env-dump ${text.replace("\n", " | ").take(600)}")
            val appEnvLeak = System.getenv().keys.filter { it !in setOf("HOME", "PATH", "LANG", "TERM", "TMPDIR", "USER", "SHELL") }
                .any { name -> text.lines().any { it.startsWith("$name=") } }
            check("env", text.contains("PROBE_FLAG=1") && !text.contains("_API_KEY") && !appEnvLeak, "appEnvLeak=$appEnvLeak")
        } finally {
            p.shutdown()
        }
    }

    /** 宿主 /proc 里还活着的、命令行含 [marker] 的进程（App 对自己的后代可读） */
    private fun hostProcessesWith(marker: String): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val cmd = try { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ') } catch (_: Exception) { return@mapNotNull null }
            val stat = try { File(dir, "stat").readText().substringAfterLast(')').trim().firstOrNull() } catch (_: Exception) { null }
            pid.takeIf { cmd.contains(marker) && !cmd.contains("proc-probe") && stat != 'Z' && stat != 'X' }
        }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            kotlinx.coroutines.delay(100)
        }
        return cond()
    }

    private suspend fun tree(processes: ProcessPort, scope: CoroutineScope, check: (String, Boolean, String) -> Unit) {
        val marker = "sleep 6013"
        val p = processes.spawn(spec("probe-tree", "sh", "-c", "trap '' TERM; sleep 6013 & wait"))
        check("tree-started", waitUntil(15_000) { hostProcessesWith(marker).isNotEmpty() }, "pids=${hostProcessesWith(marker)}")
        val t0 = System.nanoTime()
        p.shutdown()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val gone = waitUntil(10_000) { hostProcessesWith(marker).isEmpty() }
        check("tree-shutdown", gone && p.exit.isCompleted, "shutdownMs=$ms left=${hostProcessesWith(marker)}")
    }

    private suspend fun cancel(processes: ProcessPort, scope: CoroutineScope, check: (String, Boolean, String) -> Unit) {
        val marker = "sleep 6014"
        var live: LiveProcess? = null
        val owner = scope.launch {
            live = processes.spawn(spec("probe-cancel", "sh", "-c", "trap '' TERM; sleep 6014 & wait"))
            kotlinx.coroutines.awaitCancellation()
        }
        check("cancel-started", waitUntil(15_000) { hostProcessesWith(marker).isNotEmpty() }, "pids=${hostProcessesWith(marker)}")
        owner.cancel()
        owner.join()
        val gone = waitUntil(15_000) { hostProcessesWith(marker).isEmpty() }
        check("cancel-reaped", gone && live?.exit?.isCompleted == true, "left=${hostProcessesWith(marker)}")
    }

    private suspend fun quota(processes: ProcessPort, check: (String, Boolean, String) -> Unit) {
        val started = ArrayList<LiveProcess>()
        var rejected: String? = null
        try {
            repeat(12) { i ->
                try {
                    started += processes.spawn(spec("probe-quota-$i", "cat"))
                } catch (e: ProcessSpawnException) {
                    if (rejected == null) rejected = e.message
                }
            }
        } finally {
            started.forEach { it.shutdown() }
        }
        Log.i(TAG, "PROBE quota-message $rejected")
        check("quota", started.size == 8 && rejected != null, "started=${started.size}")
    }
}
