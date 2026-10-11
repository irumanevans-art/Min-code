package dev.min.code.debug

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.min.agent.host.LoopbackNetworkProxy
import dev.min.agent.policy.GuestIsolationPolicy
import dev.min.agent.policy.IsolationStatus
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import dev.min.code.core.engine.min.MinAgentEngine
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** 从真实 App 的 untrusted_app 域跑组合根；只接受固定探针，不接收任意脚本或网络目标。 */
class IsolationProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra("case") ?: "layers"
        if (label == "settings-offline" || label == "settings-proxy") {
            setContent { dev.min.code.ui.theme.MinTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        dev.min.code.ui.settings.NetworkIsolationLayers(label == "settings-proxy")
                    }
                }
            } }
            Log.i("MinIsolationProbe", "CASE_DONE=$label")
            return
        }
        val view = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(25, 25, 25))
            setBackgroundColor(android.graphics.Color.rgb(247, 245, 240))
            setPadding(24, 70, 24, 32)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "隔离层实测 · $label\n运行中…"
        }
        setContentView(ScrollView(this).apply { addView(view) })
        lifecycleScope.launch {
            val output = try {
                withContext(Dispatchers.IO) { probe(label) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { "PROBE_FAILED ${e::class.simpleName}: ${e.message}" }
            view.text = "隔离层实测 · $label\nApp PID ${android.os.Process.myPid()}\n\n$output"
            Log.i("MinIsolationProbe", "CASE=$label\n$output\nCASE_DONE=$label")
        }
    }

    private suspend fun probe(label: String): String {
        if (label == "layers") return IsolationStatus.android(false).joinToString("\n\n") { "${it.name}\n${it.state}\n${it.strength}" }
        val engine = GlobalContext.get().get<MinAgentEngine>()
        suspend fun exec(script: String, network: GuestNetwork = GuestNetwork.Offline, env: Map<String, String> = emptyMap()): ExecResult.Exited {
            val result = engine.execForProbe.run(ShellCommand(script, GuestPath("/workspace"), env, network), ExecOptions(30_000, maxOutputChars = 8000))
            return result as? ExecResult.Exited ?: error("探针没有在期限内退出")
        }
        fun show(result: ExecResult.Exited) = "${result.output.trim()}\nEXIT=${result.exitCode}"
        return when (label) {
            "danger" -> show(exec("/tmp/isolation-probe danger"))
            "paths" -> show(exec("/tmp/isolation-probe paths"))
            "proc" -> show(exec("/tmp/isolation-probe proc ${android.os.Process.myPid()}"))
            "extras" -> show(exec("/tmp/isolation-probe extras ${android.os.Process.myPid()}"))
            "binds" -> show(exec("/tmp/isolation-probe binds", GuestNetwork.ProxyOnly(listOf(18191))))
            "host-policy" -> {
                val policy = engine.execForProbe.guestIsolation ?: error("执行器转发层丢失隔离策略")
                val context = dev.min.agent.policy.PolicyContext(dev.min.agent.protocol.PermissionMode.BypassPermissions, guestIsolation = policy)
                val ask = PermissionAsk("Write", "固定探针", paths = listOf(PathAccess(GuestPath("/etc/isolation-probe"), AccessKind.Write, PathScope.Rootfs)))
                val denied = dev.min.agent.policy.PolicyEngine.evaluate(ask, false, context) is dev.min.agent.policy.Verdict.Deny
                "HOST_WRITE_OUTSIDE_DENIED=$denied\nPASS=$denied"
            }
            "lifecycle" -> {
                val marker = java.io.File(filesDir, "workspaces/c0dec0de-0003-4000-8000-000000000003/files/min-isolation-tree.txt")
                marker.delete()
                val result = engine.execForProbe.run(ShellCommand("/tmp/isolation-probe tree", GuestPath("/workspace")), ExecOptions(30_000, 0)) as? ExecResult.Backgrounded ?: error("进程树没有启动")
                try {
                    var pids = emptyList<Int>()
                    withTimeout(10_000) {
                        while (pids.size != 3) {
                            delay(50)
                            pids = if (marker.isFile) marker.readText().trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull) else emptyList()
                        }
                    }
                    val tracer = result.handle.pid ?: error("没有 proot PID")
                    android.os.Process.sendSignal(tracer, 9)
                    delay(500)
                    val states = pids.associateWith { pid ->
                        val status = java.io.File("/proc/$pid/status")
                        try { status.readLines().firstOrNull { it.startsWith("State:") } ?: "gone" } catch (_: java.io.IOException) { "gone" }
                    }
                    val alive = states.filterValues { it != "gone" && !it.contains("Z (zombie)") }.keys
                    "PROOT_SIGKILL=$tracer\nGUEST_PIDS=$pids\nSTATES=$states\nSURVIVORS=$alive\nPASS=${alive.isEmpty()}"
                } finally { engine.execForProbe.kill(result.handle); marker.delete() }
            }
            "net" -> {
                val native = exec("/tmp/isolation-probe net")
                val curl = exec("curl --max-time 3 --noproxy '*' -sS http://127.0.0.1:5555")
                show(native) + "\n\ncurl\n" + show(curl) + "\nDENIED=${curl.exitCode != 0}"
            }
            "health" -> show(exec("set -e; export PATH=/opt/node/bin:/usr/bin:/bin; bash --version | head -1; node --version; npm --version; for i in \$(seq 1 100); do /bin/true; done; node -e 'require(\"child_process\").execFileSync(\"/bin/true\");console.log(\"NODE_SPAWN_OK\")'; echo HEALTH_PASS"))
            "health-install" -> show(exec("""
                set -e
                export PATH=/opt/node/bin:/usr/bin:/bin
                test ! -e /workspace/min-isolation-npm-probe
                mkdir -p /workspace/min-isolation-npm-probe/pkg /workspace/min-isolation-npm-probe/consumer
                trap 'rm -rf -- /workspace/min-isolation-npm-probe' EXIT
                printf '%s' '{"name":"isolation-fixture","version":"1.0.0","main":"index.js"}' > /workspace/min-isolation-npm-probe/pkg/package.json
                printf '%s' 'module.exports=42' > /workspace/min-isolation-npm-probe/pkg/index.js
                cd /workspace/min-isolation-npm-probe/consumer
                printf '%s' '{"name":"isolation-consumer","version":"1.0.0"}' > package.json
                npm install --offline --ignore-scripts --no-audit --no-fund ../pkg
                node -e 'if(require("isolation-fixture")!==42)process.exit(1);console.log("NPM_LOCAL_INSTALL_OK")'
            """.trimIndent()))
            "proxy" -> ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { origin ->
                val accepted = AtomicInteger()
                val serving = Thread({
                    try {
                        while (!origin.isClosed) origin.accept().use { client ->
                            client.soTimeout = 3000
                            val input = client.getInputStream().bufferedReader()
                            while (input.readLine()?.let { it.isNotEmpty() } == true) { }
                            val body = "ISOLATION_ORIGIN_OK\n"
                            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            accepted.incrementAndGet()
                        }
                    } catch (_: java.io.IOException) { }
                }, "isolation-probe-origin").apply { isDaemon = true; start() }
                try {
                    LoopbackNetworkProxy().use { proxy ->
                        proxy.open(SessionId("isolation-probe")) { NetworkVerdict(NetworkOutcome.Allow, NetworkSource.User, "本机固定探针") }.use { lease ->
                            val network = GuestIsolationPolicy.proxyNetwork(lease.env)
                            val direct = exec("curl --max-time 3 --noproxy '*' -sS http://127.0.0.1:${origin.localPort}", network, lease.env)
                            val via = exec("curl --max-time 3 --noproxy '' -sS http://127.0.0.1:${origin.localPort}", network, lease.env)
                            "直连\n${show(direct)}\n\n真实 L3 代理\n${show(via)}\nORIGIN_ACCEPTED=${accepted.get()}\nPASS=${direct.exitCode != 0 && via.exitCode == 0 && via.output.contains("ISOLATION_ORIGIN_OK") && accepted.get() == 1}"
                        }
                    }
                } finally { origin.close(); serving.join(1000) }
            }
            else -> "未知固定探针"
        }
    }
}
