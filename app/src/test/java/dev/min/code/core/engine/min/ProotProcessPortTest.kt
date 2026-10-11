package dev.min.code.core.engine.min

import dev.min.agent.host.ProcessReaper
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.workspace.ProcessTreeKill
import me.rerere.workspace.WorkspaceShellContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * JVM 里验证 proot 实现的全部逻辑：命令行与环境怎么拼、回收的次序、取消与回收的时序。
 * 真正进 proot 的往返只能在设备或模拟器上验（debug 探针 `op proc-probe`）。
 * 这里用「起真实 JDK 子进程的替身 host」代替 proot，/proc 与 android.os.Process 换成 ProcessHandle 版本。
 */
class ProotProcessPortTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spawned = ArrayList<LiveProcess>()

    @After
    fun tearDown() {
        runBlocking { spawned.forEach { it.shutdown() } }
        scope.cancel()
    }

    private fun base(): WorkspaceShellContext {
        val root = temp.newFolder()
        return WorkspaceShellContext(
            "root", "", "", File(root, "files").apply { mkdirs() }, File(root, "linux").apply { mkdirs() },
            File(root, "tmp"), root, 0, env = mapOf("ANTHROPIC_API_KEY" to "must-not-reach-the-child"),
        )
    }

    private fun jdkReaper(signals: ConcurrentLinkedQueue<Pair<Int, Int>>? = null) = ProcessReaper { process ->
        ProcProcessReaper(
            pidOf = ProcessTreeKill::pidOf,
            childrenReader = { { pid -> ProcessHandle.of(pid.toLong()).map { h -> h.children().map { it.pid().toInt() }.toList() }.orElse(emptyList()) } },
            isAlive = { pid -> ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false) },
            sendSignal = { pid, sig ->
                signals?.add(pid to sig)
                ProcessHandle.of(pid.toLong()).ifPresent { if (sig == ProcessTreeKill.SIGKILL) it.destroyForcibly() else it.destroy() }
            },
        ).capture(process)
    }

    /** 记录收到的 context，并改为起一个真实的 JDK 子进程（mode 决定行为）代替 proot */
    private class JdkHost(val mode: String, val signals: ConcurrentLinkedQueue<Pair<Int, Int>>, val reaper: ProcessReaper) : ProotLiveHost {
        lateinit var context: WorkspaceShellContext
        override fun launch(context: WorkspaceShellContext): Process {
            this.context = context
            return ProcessBuilder(ProotChild.command(mode)).start()
        }
        override fun reaperFor(): ProcessReaper = reaper
    }

    private fun port(mode: String, secrets: Set<String> = emptySet()): Pair<ProotProcessPort, JdkHost> {
        val signals = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val host = JdkHost(mode, signals, jdkReaper(signals))
        return ProotProcessPort(base(), host, secrets) to host
    }

    private fun spec(vararg argv: String, env: Map<String, String> = emptyMap()) =
        ProcessSpec(argv.toList(), GuestPath("/root/a b'c"), env, "srv")

    private class Sink(flow: Flow<ByteArray>, scope: CoroutineScope) {
        private val buf = ByteArrayOutputStream()
        val job = scope.launch { flow.collect { synchronized(buf) { buf.write(it) } } }
        fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
        fun text() = bytes().toString(Charsets.UTF_8)
    }

    private fun eventually(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    // ---------------------------------------------------------------- 命令行与环境

    @Test fun stdioServersAlwaysRequireOfflineIsolation() = runBlocking {
        val (port, host) = port("echo")
        val live = port.spawn(spec("/bin/true", env = mapOf("HTTP_PROXY" to "http://127.0.0.1:8888")))
        spawned += live
        assertTrue("stdio isolation is mandatory", host.context.isolation != null)
        assertEquals(dev.min.agent.spi.GuestNetwork.Offline, host.context.isolation!!.network)
    }

    @Test
    fun `argv 逐项单引号转义后交给 guest 的 bash 跑、末尾原样交出退出码，且 cwd 用 guest 路径`() = runBlocking {
        val (port, host) = port("echo")
        spawned += port.spawn(spec("npx", "-y", "@scope/srv", "a b", "it's", "\$HOME", "中文"))
        assertEquals("", host.context.cwd)
        // 不能是 exec：被 SIGKILL 杀死的被跟踪进程 proot 会报 0，要留一个 bash 父进程把 128+N 交出来
        assertTrue(host.context.command, host.context.command.startsWith("cd -- '/root/a b'\"'\"'c' && {\n'npx' '-y' '@scope/srv' 'a b' 'it'\"'\"'s' '\$HOME' '中文'\nexit \$?\n}"))
        assertTrue(host.context.killOnExit)
        assertEquals(null, host.context.stdin)
    }

    @Test
    fun `只带 spec 的 env 基础上下文里的 env 一概不继承`() = runBlocking {
        val (port, host) = port("echo")
        spawned += port.spawn(spec("x", env = mapOf("MY_FLAG" to "1")))
        assertEquals(mapOf("MY_FLAG" to "1"), host.context.env)
        assertFalse(host.context.env.containsKey("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `凭证名即使写在 spec env 里也被剔除`() = runBlocking {
        val (port, host) = port("echo", secrets = setOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY"))
        spawned += port.spawn(spec("x", env = mapOf("ANTHROPIC_API_KEY" to "sk-1", "openai_api_key" to "sk-2", "OK" to "yes")))
        assertEquals(mapOf("OK" to "yes"), host.context.env)
    }

    @Test
    fun `proot 拼出的命令行是 env -i 加固定变量 不会带出宿主环境`() {
        // 用真实的 ProotShellRunner 拼命令行：base 里有 key，经 agentCommandContext + 我们的 env 之后，argv 里只能有 spec 的变量
        val root = temp.newFolder()
        val lib = File(root, "lib").apply { mkdirs() }
        val runner = me.rerere.workspace.ProotShellRunner(lib)
        val ctx = agentCommandContext(base(), GuestPath("/root"), "exec 'x'").copy(env = mapOf("MY_FLAG" to "1"))
        val argv = runner.buildCommand(ctx)
        val envAt = argv.indexOf("-i")
        assertTrue(argv.joinToString(" "), argv[envAt - 1] == "/usr/bin/env")
        assertTrue(argv.contains("MY_FLAG=1"))
        assertFalse(argv.joinToString(" "), argv.any { it.contains("ANTHROPIC") || it.contains("must-not-reach") })
    }

    @Test
    fun `非法环境变量名给带标签的明确错误`() = runBlocking {
        val failing = object : ProotLiveHost {
            override fun launch(context: WorkspaceShellContext): Process = throw IllegalArgumentException("Invalid env key: a-b")
            override fun reaperFor(): ProcessReaper = jdkReaper()
        }
        try {
            ProotProcessPort(base(), failing).spawn(spec("x", env = mapOf("a-b" to "1")))
            fail()
        } catch (e: ProcessSpawnException) {
            assertTrue(e.message, e.message!!.contains("srv") && e.message!!.contains("Invalid env key"))
        }
    }

    @Test
    fun `proot 不可用时 spawn 抛带原因的 ProcessSpawnException`() = runBlocking {
        val unavailable = object : ProotLiveHost {
            override fun launch(context: WorkspaceShellContext): Process = throw ProcessSpawnException("Rootfs is not installed")
            override fun reaperFor(): ProcessReaper = jdkReaper()
        }
        try {
            ProotProcessPort(base(), unavailable).spawn(spec("x"))
            fail()
        } catch (e: ProcessSpawnException) {
            assertEquals("Rootfs is not installed", e.message)
        }
    }

    // ---------------------------------------------------------------- 往返与回收（真实 JDK 子进程）

    @Test
    fun `回显 含中文 大于 64KB 一字节不差`() = runBlocking {
        val (port, _) = port("echo")
        val p = port.spawn(spec("cat")).also { spawned += it }
        val out = Sink(p.stdout, scope)
        val payload = ByteArray(400_000) { ((it * 7) and 0xFF).toByte() } + "你好\n".toByteArray()
        val writer = scope.launch { p.write(payload); p.closeStdin() }
        withTimeout(60_000) { writer.join() }
        assertEquals(0, withTimeout(30_000) { p.exit.await() })
        out.job.join()
        assertTrue(payload.contentEquals(out.bytes()))
    }

    @Test
    fun `shutdown 回收整棵树 孙进程也不留 后代先于根`() = runBlocking {
        val (port, host) = port("stubborn")
        val p = port.spawn(spec("srv")).also { spawned += it }
        val out = Sink(p.stdout, scope)
        assertTrue(eventually { out.text().contains("grandchild=") })
        val grandchild = out.text().lines().first { it.startsWith("grandchild=") }.removePrefix("grandchild=").trim().toLong()
        assertTrue(isAlive(grandchild))
        p.shutdown()
        assertTrue(p.exit.isCompleted)
        assertTrue(eventually(10_000) { !isAlive(grandchild) })
        // 每一档（TERM / KILL）里根都是最后一个，孙进程在它之前（中间可能还有 Windows 的 conhost 之类的陪跑进程）
        for (sig in listOf(ProcessTreeKill.SIGTERM, ProcessTreeKill.SIGKILL)) {
            val pids = host.signals.filter { it.second == sig }.map { it.first.toLong() }
            if (pids.isEmpty()) continue
            assertTrue("孙进程应在 $sig 档里、且早于根：$pids", grandchild in pids && pids.indexOf(grandchild) < pids.lastIndex)
        }
        assertTrue("至少有一档信号：${host.signals}", host.signals.isNotEmpty())
    }

    @Test
    fun `调用 spawn 的协程被取消 整棵树被回收`() = runBlocking {
        val (port, _) = port("stubborn")
        var live: LiveProcess? = null
        val owner = scope.launch {
            live = port.spawn(spec("srv")).also { spawned += it }
            awaitCancellation()
        }
        assertTrue(eventually { live != null })
        val out = Sink(live!!.stdout, scope)
        assertTrue(eventually { out.text().contains("grandchild=") })
        val grandchild = out.text().lines().first { it.startsWith("grandchild=") }.removePrefix("grandchild=").trim().toLong()
        owner.cancelAndJoin()
        assertTrue(eventually { live!!.exit.isCompleted && !isAlive(grandchild) })
    }

    // ---------------------------------------------------------------- 回收次序（不起真实进程）

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    @Test
    fun `TERM 一档 KILL 一档 后代自底向上 proot 宿主进程永远最后`() {
        // 10(proot) -> 11 -> 12 ; 11 -> 13。TERM 被忽略，KILL 才死
        val children = mapOf(10 to listOf(11), 11 to listOf(12, 13))
        val alive = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().apply { addAll(listOf(10, 11, 12, 13)) }
        val sent = ArrayList<Pair<Int, Int>>()
        val reaper = ProcProcessReaper(
            pidOf = { 10 },
            childrenReader = { { pid -> children[pid].orEmpty() } },
            isAlive = { it in alive },
            sendSignal = { pid, sig -> sent += pid to sig; if (sig == ProcessTreeKill.SIGKILL) alive -= pid },
        )
        val tree = reaper.capture(FakeProcess())
        tree.refresh()
        tree.terminate()
        assertTrue(tree.anyAlive())
        tree.kill()
        assertFalse(tree.anyAlive())
        val terms = sent.filter { it.second == ProcessTreeKill.SIGTERM }.map { it.first }
        val kills = sent.filter { it.second == ProcessTreeKill.SIGKILL }.map { it.first }
        assertEquals(listOf(12, 13, 11, 10), terms)
        assertEquals(listOf(12, 13, 11, 10), kills)
        assertTrue("所有 TERM 都在 KILL 之前", sent.indexOfLast { it.second == ProcessTreeKill.SIGTERM } < sent.indexOfFirst { it.second == ProcessTreeKill.SIGKILL })
    }

    @Test
    fun `根死后快照里的后代仍会被杀 新出现的孩子下一轮并入`() {
        val children = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>(mapOf(10 to listOf(11)))
        val alive = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().apply { addAll(listOf(10, 11)) }
        val sent = ArrayList<Pair<Int, Int>>()
        val tree = ProcProcessReaper(
            pidOf = { 10 },
            childrenReader = { { pid -> children[pid].orEmpty() } },
            isAlive = { it in alive },
            sendSignal = { pid, sig -> sent += pid to sig; if (sig == ProcessTreeKill.SIGKILL) alive -= pid },
        ).capture(FakeProcess())
        tree.refresh()
        alive -= 10 // 根自己先退了，孩子成了孤儿
        children.clear()
        alive += 12 // 11 退出前刚 fork 的孩子，快照里没有
        children[11] = listOf(12)
        tree.refresh()
        tree.kill()
        assertEquals(setOf(11, 12), sent.filter { it.second == ProcessTreeKill.SIGKILL }.map { it.first }.toSet())
        assertFalse(tree.anyAlive())
    }
}
