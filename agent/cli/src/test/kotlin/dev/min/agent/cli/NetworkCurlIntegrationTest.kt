package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.LoopbackNetworkProxy
import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.tools.BashTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.*
import java.nio.file.Path

/** 真 Git Bash 与 curl 进程访问假域名，本机服务转发不依赖外网或付费模型。 */
class NetworkCurlIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun curlAllowsDeniesAndAsksThroughConsoleApprove() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val text = "NETWORK_OK".toByteArray()
            exchange.sendResponseHeaders(200, text.size.toLong()); exchange.responseBody.use { it.write(text) }
        }
        server.start()
        try {
            for ((host, approval, expected) in listOf(Triple("allowed.test", ApprovalAnswer.No, true),
                Triple("denied.test", ApprovalAnswer.Yes, false), Triple("ask.test", ApprovalAnswer.Yes, true), Triple("ask.test", ApprovalAnswer.No, false))) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val proxy = LoopbackNetworkProxy(connect = { Socket("127.0.0.1", server.address.port) })
                try {
                    val root = tmp.newFolder().toPath()
                    val fs = FakeFs().apply { put("/root/.claude/settings.json", """{"sandbox":{"enabled":true,"network":{"allowedDomains":["allowed.test"],"deniedDomains":["denied.test"]}},"permissions":{"allow":["Bash"]}}""") }
                    val command = "curl --fail --silent --show-error http://$host/"
                    val model = ScriptedModel(reply(ToolCallPart(CallId("c"), "Bash", args("command" to command)), stop = StopReason.ToolUse), reply(TextPart("done")))
                    val base = testDeps(model, listOf(BashTool()))
                    val shell = if (System.getProperty("os.name").startsWith("Windows")) listOf("C:/Program Files/Git/bin/bash.exe", "-c") else listOf("bash", "-c")
                    val exec = LocalExec({ root }, root.resolve("logs"), GuestPath("/logs"), shell)
                    val deps = KernelDeps(base.clock, base.ids, base.models, base.tools, HostPorts(fs, FakeSearch(), exec, { FakeArtifacts() }), "test", base.zone,
                        memoryHome = GuestPath("/root"), networkProxy = proxy, networkApprovalTimeoutMs = 3000)
                    val hub = SessionHub(FileSessionStore(root.resolve("sessions"), { 1000 }), deps, scope)
                    val runtime = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
                    val console = ByteArrayOutputStream()
                    val ui = ConsoleUi(runtime, approval, BufferedReader(StringReader("")), PrintStream(console), hub).attach(scope)
                    withTimeout(15000) {
                        runtime.send(ClientMsgId("one"), listOf(TextPart("fetch")))
                        runtime.state.first { it.turn == null && it.lastTurn != null }
                    }
                    val result = runtime.state.value.toolResults.getValue(CallId("c"))
                    assertEquals("$host approve=$approval", expected, !result.isError)
                    if (expected) assertTrue(result.displayText().contains("NETWORK_OK"))
                    val decision = runtime.state.value.networkDecisions.single()
                    assertEquals(if (expected) NetworkOutcome.Allow else NetworkOutcome.Deny, decision.outcome)
                    assertEquals(if (host == "ask.test") NetworkSource.User else NetworkSource.Rule, decision.source)
                    if (host == "ask.test") assertTrue(console.toString().contains("ask.test"))
                    println("$host --approve ${approval.name.lowercase()} => ${decision.outcome}; ${result.displayText()}")
                    ui.cancelAndJoin(); hub.close(runtime.sessionId)
                } finally { proxy.close(); scope.cancel() }
            }
        } finally { server.stop(0) }
    }
}
