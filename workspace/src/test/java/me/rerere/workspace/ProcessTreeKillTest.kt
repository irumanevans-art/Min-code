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

    /** 在假 /proc 下造一个进程：stat 的 state 字段 + 各线程的 children 表 */
    private fun fakeProc(
        procRoot: File,
        pid: Int,
        state: String = "S",
        comm: String = "node",
        childrenByTask: Map<Int, String> = emptyMap(),
    ) {
        val dir = File(procRoot, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText("$pid ($comm) $state 1 $pid $pid 0 -1 4194560 0 0\n")
        childrenByTask.forEach { (tid, content) ->
            val task = File(dir, "task/$tid").apply { mkdirs() }
            File(task, "children").writeText(content)
        }
    }

    // ---- parseChildren ----

    @Test
    fun `parseChildren 按空白拆分并容忍坏词`() {
        assertEquals(listOf(12, 34, 56), ProcessTreeKill.parseChildren("12 34 56"))
        // 内核实际写出来是 "12 34 56 "，还可能跨行；多空格/制表符都算分隔
        assertEquals(listOf(12, 34), ProcessTreeKill.parseChildren("  12\t\n 34 \n"))
        // 坏词跳过而不是整表作废：读到一半进程退出可能截断出残字
        assertEquals(listOf(7, 9), ProcessTreeKill.parseChildren("7 xx 9 -3 0"))
    }

    @Test
    fun `parseChildren 空串给空表`() {
        assertEquals(emptyList<Int>(), ProcessTreeKill.parseChildren(""))
        assertEquals(emptyList<Int>(), ProcessTreeKill.parseChildren("   \n\t "))
    }

    // ---- procStatState ----

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

    // ---- procAlive / procChildrenReader（用假 /proc 目录，不碰真 /proc）----

    @Test
    fun `procAlive 僵尸不算活`() {
        val proc = tempDir()
        fakeProc(proc, 111, state = "S")
        fakeProc(proc, 222, state = "Z") // 被杀后等 proot 回收的死壳
        fakeProc(proc, 333, state = "X")
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
    fun `procChildrenReader 聚合所有线程的 children 并去重`() {
        val proc = tempDir()
        // fork 可能来自任意线程：只读主线程的 children 会漏掉别的线程 fork 的孩子
        fakeProc(
            proc,
            500,
            childrenByTask = mapOf(500 to "601 602 ", 507 to "603 601\n"),
        )
        val children = ProcessTreeKill.procChildrenReader(proc)(500)
        assertEquals(listOf(601, 602, 603), children.sorted())
        assertEquals(children.size, children.toSet().size)
        // 进程不存在 / task 目录已消失 → 空表，不抛
        assertEquals(emptyList<Int>(), ProcessTreeKill.procChildrenReader(proc)(999))
    }

    @Test
    fun `descendantsBottomUp 接真实 procChildrenReader 能走通假 proc 树`() {
        val proc = tempDir()
        fakeProc(proc, 10, childrenByTask = mapOf(10 to "20 30"))
        fakeProc(proc, 20, childrenByTask = mapOf(20 to "40"))
        fakeProc(proc, 30)
        fakeProc(proc, 40)
        val order = ProcessTreeKill.descendantsBottomUp(10, ProcessTreeKill.procChildrenReader(proc))
        assertEquals(listOf(40, 20, 30), order)
    }
}
