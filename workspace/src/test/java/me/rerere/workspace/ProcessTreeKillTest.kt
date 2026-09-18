package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 只测纯逻辑：/proc 用临时目录顶替，信号用记录器顶替，不碰 `android.os.Process`
 * （Windows 上跑不了）。重点是杀树的**时序**——它是 H3 修复能不能生效的全部。
 */
class ProcessTreeKillTest {
    private fun tempDir(): File =
        Files.createTempDirectory("process-tree-kill").toFile().apply { deleteOnExit() }

    /**
     * 在假 /proc 下造一个进程：只写 `<pid>/stat`。
     *
     * **绝对不要在这里造 `task/<tid>/children`** —— 那个文件需要内核开
     * `CONFIG_PROC_CHILDREN`，Android 上不存在。上一版单测正是靠自己造出来的
     * children 文件全绿，而真机上杀树一个进程都没找到（理由见 ProcessTreeKill 类注释）。
     * 字段顺序照真实格式：`pid (comm) state ppid pgrp session ...`
     */
    private fun fakeProc(
        procRoot: File,
        pid: Int,
        ppid: Int,
        state: String = "S",
        comm: String = "node",
    ) {
        val dir = File(procRoot, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText("$pid ($comm) $state $ppid $pid $pid 0 -1 4194560 0 0\n")
    }

    /** 真机上实测到的托管服务形态：宿主 proot → bash → npm → sh → node */
    private fun fakeServiceTree(procRoot: File) {
        fakeProc(procRoot, 3577, ppid = 1, comm = "dev.min.code") // app 自己，不该进树
        fakeProc(procRoot, 3826, ppid = 3577, comm = "libproot_exec.so")
        fakeProc(procRoot, 3832, ppid = 3826, comm = "bash")
        fakeProc(procRoot, 3835, ppid = 3832, comm = "npm run dev") // comm 带空格
        fakeProc(procRoot, 3847, ppid = 3835, comm = "sh")
        fakeProc(procRoot, 3848, ppid = 3847, comm = "node")
    }

    // ---- procStatState / procStatPpid ----

    @Test
    fun `procStatState 取最后一个右括号之后的字段`() {
        assertEquals("S", ProcessTreeKill.procStatState("1234 (node) S 1 1234 1234 0 -1"))
        // comm 里带空格和右括号：按第一个 ')' 切会读成 "name)"，必须按最后一个切
        assertEquals("Z", ProcessTreeKill.procStatState("77 ((weird) name)) Z 1 77 77"))
        assertEquals("R", ProcessTreeKill.procStatState("5 (a b c) R 1"))
    }

    @Test
    fun `procStatState 解析不出来给 null`() {
        assertEquals(null, ProcessTreeKill.procStatState(""))
        assertEquals(null, ProcessTreeKill.procStatState("1234 (node"))
        assertEquals(null, ProcessTreeKill.procStatState("1234 (node)   "))
    }

    @Test
    fun `procStatPpid 取 comm 之后的第二个字段`() {
        // 真机采样：cat /proc/3848/stat -> "3848 (node) S 3847 398 0 0 -1 ..."
        assertEquals(3847, ProcessTreeKill.procStatPpid("3848 (node) S 3847 398 0 0 -1 4194560"))
        // comm 含空格：按空白硬拆会把 "run" 当 state、"dev)" 当 ppid，必须从最后一个 ')' 切
        assertEquals(3832, ProcessTreeKill.procStatPpid("3835 (npm run dev) S 3832 398 0"))
        // comm 含右括号：按第一个 ')' 切会读成 " name)) Z ..."，ppid 解析成 null
        assertEquals(77, ProcessTreeKill.procStatPpid("55 ((weird) name)) Z 77 55 55"))
        // init 的父是 0，是合法值而不是解析失败
        assertEquals(0, ProcessTreeKill.procStatPpid("1 (init) S 0 1 1 0 -1"))
    }

    @Test
    fun `procStatPpid 解析不出来给 null`() {
        assertEquals(null, ProcessTreeKill.procStatPpid(""))
        assertEquals(null, ProcessTreeKill.procStatPpid("1234 (node"))
        assertEquals(null, ProcessTreeKill.procStatPpid("1234 (node) S")) // 截断在 state 上
        assertEquals(null, ProcessTreeKill.procStatPpid("1234 (node) S xx"))
    }

    // ---- parsePidFromToString / pidOf ----

    /** 只用来喂 [ProcessTreeKill.pidOf] 的壳，真实行为一概不实现 */
    private open class FakeProcess(private val text: String) : Process() {
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun exitValue(): Int = throw UnsupportedOperationException()
        override fun destroy() = Unit
        override fun toString(): String = text
    }

    /** toString 不报 pid、只有实现类字段的老运行时形状 */
    private class FieldOnlyProcess(@JvmField val pid: Int) :
        FakeProcess("java.lang.ProcessImpl@1a2b3c")

    @Test
    fun `parsePidFromToString 认 OpenJDK 的 toString 形状`() {
        assertEquals(1234, ProcessTreeKill.parsePidFromToString("Process[pid=1234, exitValue=\"not exited\"]"))
        assertEquals(7, ProcessTreeKill.parsePidFromToString("Process[pid=7, exitValue=0]"))
    }

    @Test
    fun `parsePidFromToString 认不出来给 null`() {
        assertEquals(null, ProcessTreeKill.parsePidFromToString("java.lang.ProcessImpl@1a2b3c"))
        assertEquals(null, ProcessTreeKill.parsePidFromToString(""))
        assertEquals(null, ProcessTreeKill.parsePidFromToString("Process[pid=0]"))
        // 不能被 "ppid=" 之类的尾巴带跑
        assertEquals(null, ProcessTreeKill.parsePidFromToString("stat ppid=99"))
    }

    @Test
    fun `pidOf 优先吃 toString`() {
        assertEquals(4242, ProcessTreeKill.pidOf(FakeProcess("Process[pid=4242, exitValue=\"not exited\"]")))
    }

    @Test
    fun `pidOf 在 toString 无 pid 时退到实现类字段`() {
        // 真机上 API 34 以下就是这条路：toString 是默认实现，只剩 ProcessImpl.pid 字段
        assertEquals(777, ProcessTreeKill.pidOf(FieldOnlyProcess(777)))
    }

    @Test
    fun `pidOf 三条路都走不通时给 null`() {
        assertEquals(null, ProcessTreeKill.pidOf(FakeProcess("java.lang.ProcessImpl@1a2b3c")))
    }

    // ---- descendantsBottomUp ----

    @Test
    fun `descendantsBottomUp 叶子在前且不含 root`() {
        val tree = mapOf(
            100 to listOf(200, 300),
            200 to listOf(400),
            400 to listOf(500),
        )
        val order = ProcessTreeKill.descendantsBottomUp(100) { tree[it].orEmpty() }
        assertFalse("root 不能出现在后代表里", order.contains(100))
        assertEquals(listOf(500, 400, 200, 300), order)
        // 每个父进程都必须排在自己孩子后面 —— 自底向上的定义
        assertTrue(order.indexOf(500) < order.indexOf(400))
        assertTrue(order.indexOf(400) < order.indexOf(200))
    }

    @Test
    fun `descendantsBottomUp 防自指与环路`() {
        // 自指：/proc 快照期间理论上可能读到把自己当孩子
        val selfRef = ProcessTreeKill.descendantsBottomUp(1) { pid ->
            when (pid) {
                1 -> listOf(1, 2)
                2 -> listOf(2)
                else -> emptyList()
            }
        }
        assertEquals(listOf(2), selfRef)

        // 孩子反指 root
        val backToRoot = ProcessTreeKill.descendantsBottomUp(1) { pid ->
            if (pid == 1) listOf(2) else listOf(1)
        }
        assertEquals(listOf(2), backToRoot)

        // 真环 2 -> 3 -> 2：必须终止且不重复
        val cycle = ProcessTreeKill.descendantsBottomUp(1) { pid ->
            when (pid) {
                1 -> listOf(2)
                2 -> listOf(3)
                3 -> listOf(2)
                else -> emptyList()
            }
        }
        assertEquals(setOf(2, 3), cycle.toSet())
        assertEquals(cycle.size, cycle.toSet().size)
    }

    @Test
    fun `descendantsBottomUp 读子表抛异常时当空表`() {
        val order = ProcessTreeKill.descendantsBottomUp(1) { pid ->
            when (pid) {
                1 -> listOf(2, 3)
                2 -> throw IllegalStateException("进程已退出")
                else -> emptyList()
            }
        }
        assertEquals(listOf(2, 3), order)
    }

    // ---- killTree ----

    /** 记录 (pid, signal) 发生顺序的信号器 */
    private class SignalLog {
        val sent = mutableListOf<Pair<Int, Int>>()
        fun pidsOf(signal: Int) = sent.filter { it.second == signal }.map { it.first }
    }

    @Test
    fun `killTree 先自底向上 TERM 再对幸存者补 KILL`() {
        val tree = mapOf(100 to listOf(200, 300), 200 to listOf(400))
        val log = SignalLog()
        val slept = mutableListOf<Long>()
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { tree[it].orEmpty() },
            isAlive = { true }, // 谁都不肯死 → 全部要补 KILL
            sendSignal = { pid, sig -> log.sent += pid to sig },
            termGraceMs = 7L,
            sleep = { slept += it },
        )
        assertEquals(listOf(400, 200, 300), result.tree)
        assertEquals(listOf(400, 200, 300), result.terminated)
        assertEquals(listOf(400, 200, 300), result.killed)
        // 全部 TERM 必须发在任何一个 KILL 之前，且各自保持叶子优先
        assertEquals(listOf(400, 200, 300), log.pidsOf(ProcessTreeKill.SIGTERM))
        assertEquals(listOf(400, 200, 300), log.pidsOf(ProcessTreeKill.SIGKILL))
        assertEquals(
            List(3) { ProcessTreeKill.SIGTERM } + List(3) { ProcessTreeKill.SIGKILL },
            log.sent.map { it.second },
        )
        // 有人收了 TERM 才值得等宽限
        assertEquals(listOf(7L), slept)
    }

    @Test
    fun `killTree 对 TERM 后已退出的进程不补 KILL`() {
        val tree = mapOf(100 to listOf(200, 300))
        val log = SignalLog()
        val alive = mutableSetOf(200, 300)
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { tree[it].orEmpty() },
            isAlive = { it in alive },
            sendSignal = { pid, sig ->
                log.sent += pid to sig
                if (pid == 200 && sig == ProcessTreeKill.SIGTERM) alive -= 200 // 乖乖退出
            },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(listOf(200, 300), result.terminated)
        assertEquals(listOf(300), result.killed)
        assertEquals(listOf(300), log.pidsOf(ProcessTreeKill.SIGKILL))
    }

    @Test
    fun `killTree 对发信号前就没了的进程不发信号`() {
        val tree = mapOf(100 to listOf(200, 300))
        val log = SignalLog()
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { tree[it].orEmpty() },
            isAlive = { it != 200 },
            sendSignal = { pid, sig -> log.sent += pid to sig },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(listOf(200), result.goneBeforeSignal)
        assertEquals(listOf(300), result.terminated)
        assertFalse(log.sent.any { it.first == 200 })
    }

    @Test
    fun `killTree 单个 pid 信号抛异常不中断整棵树`() {
        val tree = mapOf(100 to listOf(200, 300), 200 to listOf(400))
        val log = SignalLog()
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { tree[it].orEmpty() },
            isAlive = { true },
            sendSignal = { pid, sig ->
                // 发信号间隙进程死了（ESRCH）/ ROM 差异（EPERM）
                if (pid == 200) throw RuntimeException("ESRCH")
                log.sent += pid to sig
            },
            termGraceMs = 0L,
            sleep = {},
        )
        // 200 失败，400 / 300 照常走完 TERM + KILL
        assertEquals(listOf(400, 300), result.terminated)
        assertEquals(listOf(200), result.goneBeforeSignal)
        assertEquals(listOf(400, 300), result.killed)
        assertEquals(listOf(400, 300), log.pidsOf(ProcessTreeKill.SIGTERM))
        assertEquals(listOf(400, 300), log.pidsOf(ProcessTreeKill.SIGKILL))
    }

    @Test
    fun `killTree 存活判断抛异常时当已死`() {
        val log = SignalLog()
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { if (it == 100) listOf(200) else emptyList() },
            isAlive = { throw RuntimeException("/proc 读挂了") },
            sendSignal = { pid, sig -> log.sent += pid to sig },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(listOf(200), result.goneBeforeSignal)
        assertTrue(log.sent.isEmpty())
    }

    @Test
    fun `killTree 空树不发信号也不等宽限`() {
        val log = SignalLog()
        val slept = mutableListOf<Long>()
        val result = ProcessTreeKill.killTree(
            rootPid = 100,
            childrenOf = { emptyList() },
            isAlive = { true },
            sendSignal = { pid, sig -> log.sent += pid to sig },
            termGraceMs = 400L,
            sleep = { slept += it },
        )
        assertEquals(emptyList<Int>(), result.tree)
        assertTrue(log.sent.isEmpty())
        assertTrue("没人收 TERM 就不该白等 400ms", slept.isEmpty())
    }

    // ---- killHost ----

    @Test
    fun `killHost 扛过 TERM 的宿主要补 KILL`() {
        // proot 的真实形态：SIGTERM 不死，SIGKILL 秒死
        val log = SignalLog()
        var alive = true
        val outcome = ProcessTreeKill.killHost(
            hostPid = 3826,
            isAlive = { alive },
            sendSignal = { pid, sig ->
                log.sent += pid to sig
                if (sig == ProcessTreeKill.SIGKILL) alive = false
            },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(ProcessTreeKill.HostOutcome.Killed, outcome)
        assertEquals(
            listOf(3826 to ProcessTreeKill.SIGTERM, 3826 to ProcessTreeKill.SIGKILL),
            log.sent,
        )
    }

    @Test
    fun `killHost 吃 TERM 就退的不补 KILL`() {
        val log = SignalLog()
        var alive = true
        val outcome = ProcessTreeKill.killHost(
            hostPid = 100,
            isAlive = { alive },
            sendSignal = { pid, sig ->
                log.sent += pid to sig
                if (sig == ProcessTreeKill.SIGTERM) alive = false
            },
            termGraceMs = 5L,
            sleep = {},
        )
        assertEquals(ProcessTreeKill.HostOutcome.ExitedOnTerm, outcome)
        assertTrue(log.pidsOf(ProcessTreeKill.SIGKILL).isEmpty())
    }

    @Test
    fun `killHost 发信号前已消失就什么都不发`() {
        val log = SignalLog()
        val outcome = ProcessTreeKill.killHost(
            hostPid = 100,
            isAlive = { false },
            sendSignal = { pid, sig -> log.sent += pid to sig },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(ProcessTreeKill.HostOutcome.AlreadyGone, outcome)
        assertTrue(log.sent.isEmpty())
    }

    @Test
    fun `killHost 复核前必须等宽限`() {
        // SIGKILL 是异步的：内核收下之后进程还要退出 + 被 reaper 收尸。
        // 少等这一次宽限就会稳定误报 Survived。
        val slept = mutableListOf<Long>()
        var alive = true
        val outcome = ProcessTreeKill.killHost(
            hostPid = 100,
            isAlive = { alive },
            sendSignal = { _, sig -> if (sig == ProcessTreeKill.SIGKILL) alive = false },
            termGraceMs = 400L,
            sleep = { slept += it },
        )
        assertEquals(ProcessTreeKill.HostOutcome.Killed, outcome)
        assertEquals("TERM 后一次、KILL 后一次", listOf(400L, 400L), slept)
    }

    @Test
    fun `killHost KILL 之后还活着要如实报 Survived`() {
        val outcome = ProcessTreeKill.killHost(
            hostPid = 100,
            isAlive = { true }, // 死活不肯走
            sendSignal = { _, _ -> },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(ProcessTreeKill.HostOutcome.Survived, outcome)
    }

    @Test
    fun `killHost 信号抛异常时按存活状态定性`() {
        // ESRCH：进入时还活着，发信号那一刻正好没了 → 不是失败
        var alive = true
        assertEquals(
            ProcessTreeKill.HostOutcome.AlreadyGone,
            ProcessTreeKill.killHost(
                hostPid = 100,
                isAlive = { alive },
                sendSignal = { _, _ ->
                    alive = false
                    throw RuntimeException("ESRCH")
                },
                termGraceMs = 0L,
                sleep = {},
            ),
        )
        // EPERM：信号发不出去而进程还在 —— 不能报成功
        assertEquals(
            ProcessTreeKill.HostOutcome.Survived,
            ProcessTreeKill.killHost(
                hostPid = 100,
                isAlive = { true },
                sendSignal = { _, _ -> throw RuntimeException("EPERM") },
                termGraceMs = 0L,
                sleep = {},
            ),
        )
    }

    // ---- procAlive / readParentTable / procChildrenReader（用假 /proc 目录，不碰真 /proc）----

    @Test
    fun `procAlive 僵尸不算活`() {
        val proc = tempDir()
        fakeProc(proc, 111, ppid = 1, state = "S")
        fakeProc(proc, 222, ppid = 1, state = "Z") // 被杀后等 proot 回收的死壳
        fakeProc(proc, 333, ppid = 1, state = "X")
        val alive = ProcessTreeKill.procAlive(proc)
        assertTrue(alive(111))
        assertFalse("僵尸补 KILL 没有意义，不能算幸存者", alive(222))
        assertFalse(alive(333))
        assertFalse("目录都没了就是死了", alive(999))
    }

    @Test
    fun `procAlive 在 stat 读不到时退化为目录在即活`() {
        val proc = tempDir()
        File(proc, "444").mkdirs() // 只有目录，没有 stat
        assertTrue(ProcessTreeKill.procAlive(proc)(444))
    }

    @Test
    fun `readParentTable 只收数字目录并解析 ppid`() {
        val proc = tempDir()
        fakeServiceTree(proc)
        // /proc 下大量非进程条目：cpuinfo、net、self、thread-self…
        File(proc, "cpuinfo").writeText("processor : 0\n")
        File(proc, "self").mkdirs()
        File(proc, "net").mkdirs()
        val table = ProcessTreeKill.readParentTable(proc)
        assertEquals(
            mapOf(3577 to 1, 3826 to 3577, 3832 to 3826, 3835 to 3832, 3847 to 3835, 3848 to 3847),
            table,
        )
    }

    @Test
    fun `readParentTable 跳过读不到 stat 的进程`() {
        // 真机上别的 uid 的 /proc/<pid>/stat 是 Permission denied；
        // 这里用"目录在但 stat 不在"走同一条 runCatching 分支
        val proc = tempDir()
        fakeProc(proc, 10, ppid = 1)
        File(proc, "11").mkdirs()
        File(proc, "12").apply { mkdirs() }.let { File(it, "stat").writeText("garbage\n") }
        assertEquals(mapOf(10 to 1), ProcessTreeKill.readParentTable(proc))
    }

    @Test
    fun `readParentTable 目录不存在时给空表`() {
        assertEquals(emptyMap<Int, Int>(), ProcessTreeKill.readParentTable(File(tempDir(), "nope")))
    }

    @Test
    fun `childrenIndex 反向索引按 pid 升序且丢掉自指`() {
        val index = ProcessTreeKill.childrenIndex(
            mapOf(30 to 10, 20 to 10, 40 to 20, 10 to 10),
        )
        assertEquals(listOf(20, 30), index[10])
        assertEquals(listOf(40), index[20])
        assertEquals(null, index[40])
    }

    @Test
    fun `procChildrenReader 从 stat 的 ppid 反推直接子表`() {
        val proc = tempDir()
        fakeServiceTree(proc)
        val children = ProcessTreeKill.procChildrenReader(proc)
        assertEquals(listOf(3832), children(3826))
        assertEquals(listOf(3848), children(3847))
        assertEquals(emptyList<Int>(), children(3848)) // 叶子
        assertEquals(emptyList<Int>(), children(999999)) // 不存在的 pid 不抛
    }

    @Test
    fun `descendantsBottomUp 接真实 procChildrenReader 能走通真机形态的树`() {
        val proc = tempDir()
        fakeServiceTree(proc)
        val order = ProcessTreeKill.descendantsBottomUp(3826, ProcessTreeKill.procChildrenReader(proc))
        // 叶子 node 在最前，bash 在最后；宿主和 app 都不在表里
        assertEquals(listOf(3848, 3847, 3835, 3832), order)
        assertFalse("宿主自己不能进后代表", order.contains(3826))
        assertFalse("App 是宿主的父，不能被当成后代", order.contains(3577))
    }

    @Test
    fun `descendantsBottomUp 接 procChildrenReader 能走多分支`() {
        val proc = tempDir()
        fakeProc(proc, 10, ppid = 1)
        fakeProc(proc, 20, ppid = 10)
        fakeProc(proc, 30, ppid = 10)
        fakeProc(proc, 40, ppid = 20)
        fakeProc(proc, 50, ppid = 1) // 同一层的无关进程，不能被卷进来
        val order = ProcessTreeKill.descendantsBottomUp(10, ProcessTreeKill.procChildrenReader(proc))
        assertEquals(listOf(40, 20, 30), order)
    }

    @Test
    fun `killTree 接假 proc 能把整棵服务树按叶子优先杀干净`() {
        // 端到端：发现层 + 存活判断都走真实的 /proc 实现，只换 procRoot 和信号器
        val proc = tempDir()
        fakeServiceTree(proc)
        val log = SignalLog()
        val result = ProcessTreeKill.killTree(
            rootPid = 3826,
            childrenOf = ProcessTreeKill.procChildrenReader(proc),
            isAlive = ProcessTreeKill.procAlive(proc),
            sendSignal = { pid, sig ->
                log.sent += pid to sig
                // 收 TERM 就退：删掉假 /proc 里的目录
                if (sig == ProcessTreeKill.SIGTERM) File(proc, pid.toString()).deleteRecursively()
            },
            termGraceMs = 0L,
            sleep = {},
        )
        assertEquals(listOf(3848, 3847, 3835, 3832), result.tree)
        assertEquals(listOf(3848, 3847, 3835, 3832), result.terminated)
        assertEquals(emptyList<Int>(), result.killed)
        assertTrue("宿主必须留到 killHost 那步", File(proc, "3826").isDirectory)
    }
}
