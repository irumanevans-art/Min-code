package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpScope
import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.ext.mcp.McpTransportKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** headersHelper（MCP设计 §3）：每条连接只跑一次、经长寿进程端口（不留日志文件）、失败退回静态头且不缓存 */
class McpHeadersHelperTest {
    private class FakePort(private val stdout: () -> String, private val exitCode: Int = 0, private val hang: Boolean = false) : ProcessPort {
        val spawns = AtomicInteger()
        val shutdowns = AtomicInteger()
        val specs = CopyOnWriteArrayList<ProcessSpec>()
        override suspend fun spawn(spec: ProcessSpec): LiveProcess {
            spawns.incrementAndGet()
            specs += spec
            val out = stdout()
            return object : LiveProcess {
                override val stdout: Flow<ByteArray> = if (hang) flow { awaitCancellation() } else flowOf(out.toByteArray())
                override val stderr: Flow<ByteArray> = emptyFlow()
                override suspend fun write(bytes: ByteArray) = Unit
                override suspend fun closeStdin() = Unit
                override val exit: Deferred<Int> = CompletableDeferred(exitCode)
                override suspend fun shutdown() {
                    shutdowns.incrementAndGet()
                }
            }
        }
    }

    private fun spec(headers: Map<String, String> = mapOf("X-Static" to "s", "Authorization" to "old")) = McpServerSpec.Remote(
        "api", McpScope.User, null, false, McpTransportKind.Http, "https://example.test/mcp", headers, "print-token", null,
    )

    @Test
    fun `the helper runs once per connection and its output overrides the static headers`() = runBlocking {
        val port = FakePort({ """{"Authorization":"Bearer fresh","X-Extra":"e"}""" })
        val helper = HeadersHelper(port, spec(), GuestPath("/workspace"))
        repeat(5) { helper.headers() }
        assertEquals("每个请求都来取头，helper 只该跑一次", 1, port.spawns.get())
        assertEquals(1, port.shutdowns.get())
        val headers = helper.headers().toMap()
        assertEquals("Bearer fresh", headers["Authorization"])
        assertEquals("s", headers["X-Static"])
        assertEquals("e", headers["X-Extra"])
        val spawned = port.specs.single()
        assertEquals(listOf("bash", "-c", "print-token"), spawned.argv)
        assertEquals("api", spawned.env["CLAUDE_CODE_MCP_SERVER_NAME"])
        assertEquals("https://example.test/mcp", spawned.env["CLAUDE_CODE_MCP_SERVER_URL"])
    }

    @Test
    fun `a failing helper falls back to the static headers and is tried again next time`() = runBlocking {
        val failing = FakePort({ "" }, exitCode = 1)
        val helper = HeadersHelper(failing, spec(), GuestPath("/workspace"))
        assertEquals(mapOf("X-Static" to "s", "Authorization" to "old"), helper.headers().toMap())
        helper.headers()
        assertEquals("失败不缓存：下一个请求再试", 2, failing.spawns.get())

        val garbage = HeadersHelper(FakePort({ "not json" }), spec(), GuestPath("/workspace"))
        assertEquals("old", garbage.headers().toMap()["Authorization"])
    }

    @Test
    fun `a helper that never finishes is cut off at the timeout and its process is shut down`() = runBlocking {
        val hanging = FakePort({ "" }, hang = true)
        val helper = HeadersHelper(hanging, spec(), GuestPath("/workspace"), timeoutMs = 100)
        assertEquals("old", helper.headers().toMap()["Authorization"])
        assertEquals(1, hanging.shutdowns.get())
    }

    @Test
    fun `without a helper the static headers are used and nothing is spawned`() = runBlocking {
        val port = FakePort({ "" })
        val helper = HeadersHelper(port, spec().copy(headersHelper = null), GuestPath("/workspace"))
        assertEquals("s", helper.headers().toMap()["X-Static"])
        assertEquals(0, port.spawns.get())
    }
}
