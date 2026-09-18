package me.rerere.workspace

import java.io.File

/**
 * 停止 proot 长驻服务时的 guest 进程树清理（H3 修复的核心）。
 *
 * 背景：proot 的 tracee（guest 里 fork 出的 bash / node 等）是宿主机上的真实进程，
 * 在宿主 `/proc` 里可见，App 对自己的后代进程有 kill 权限。旧的停止路径只 destroy
 * 宿主 proot（先 SIGTERM 后 SIGKILL）：proot 被 SIGKILL 时没有机会清扫 tracee，
 * guest 进程脱 ptrace 变成 PPID=1 的孤儿死壳 —— 端口仍 LISTEN，但事件循环已卡死
 * 不再响应，还满转 CPU。`--kill-on-exit` 救不了这个场景：该 flag 的语义是"guest
 * 首进程退出时由 proot 事件循环清扫残余"，proot 自己被 SIGKILL 时根本不会执行。
 *
 * 因此停止顺序必须是：**趁宿主 proot 还活着，先自底向上杀光它的后代，再毁宿主**。
 * 倒过来的话，宿主一死，后代立刻被 reparent 到 init，从 root 出发的树就再也走不到
 * 了（这正是复现实验里孤儿死壳的形态）。
 *
 * 进程组杀不可用：`ProcessBuilder` 不 setsid，proot 及其 tracee 与 App 同处一个
 * 进程组，杀组等于杀 App 自己 —— 已读代码确认 proot 侧也没有 setsid 调用。
 *
 * ## 教训一：不要再碰 `/proc/<pid>/task/<tid>/children`
 *
 * 这个文件由内核编译选项 `CONFIG_PROC_CHILDREN` 提供，**Android 通用内核不开它**。
 * API 35 模拟器上 `ls /proc/1/task/1/` 的条目里根本没有 `children`。本修复的第一版
 * 就栽在这里：读取恒返回空表 → 杀树是纯空操作（真机 logcat 里 `tree=0`），而单测因为
 * 自己在临时目录里造了 `children` 文件，22 个用例全绿 —— 测的是一个真机上不存在的
 * 内核接口。所以现在改成遍历 `/proc` 下的数字目录、读每个 `/proc/<pid>/stat` 的 ppid
 * 字段，自己拼 pid→ppid 全表再反推后代（[readParentTable]）：ppid 是 `/proc` 从来就有的
 * 东西，不依赖任何内核选项。代价是一次全表扫描，但停止服务是罕见操作，换得起。
 *
 * ## 教训二：宿主 proot 不能指望 `Process.destroy()` / `destroyForcibly()`
 *
 * Android libcore 的 `UNIXProcess`（`ProcessBuilder.start()` 实际返回的类）停在 OpenJDK 8
 * 之前的形状：`destroy()` 走 native `destroyProcess(int pid)`，函数体就一行
 * `kill(pid, SIGTERM)`，**没有 force 参数**；`destroyForcibly()` 它压根没有 override，
 * 落到 `java.lang.Process` 的默认实现 `{ destroy(); return this; }` —— 也就是再发一次
 * SIGTERM。而 proot 扛得住 SIGTERM（实测点 Stop 8 秒后宿主仍 `S (sleeping)`，
 * `waitFor()` 一直不返回）。于是"先 TERM 等一秒再 forcibly"的升级路径在 Android 上
 * 根本没有 KILL 那一步，宿主永远停不掉。所以 [killHost] 自己对宿主 pid 发信号，
 * `destroy()` 只留着关三条管道、以及连 pid 都取不到时的兜底。
 *
 * 所有 IO 均可注入：纯逻辑（解析、排序、杀树时序）在 Windows 上单测，
 * 默认实现（/proc 读取、android.os 信号）只在真机上执行。
 */
object ProcessTreeKill {
    const val SIGTERM = 15
    const val SIGKILL = 9

    /** TERM 后留给进程退出并被 proot 回收的宽限，之后再 KILL 幸存者 */
    const val TERM_GRACE_MS = 400L

    data class Result(
        /** 快照时树里的全部后代，自底向上序（叶子在前） */
        val tree: List<Int>,
        /** 确实发出了 SIGTERM 的 pid */
        val terminated: List<Int>,
        /** TERM 宽限后仍存活、补了 SIGKILL 的 pid */
        val killed: List<Int>,
        /** 发信号前就（或发信号瞬间）已消失的 pid */
        val goneBeforeSignal: List<Int>,
    )

    /** `Process[pid=1234, exitValue=...]` 这种 toString 里的 pid */
    fun parsePidFromToString(text: String): Int? =
        Regex("""\bpid=(\d+)""").find(text)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * 取 [process] 的宿主 pid —— 杀树的入口参数。
     *
     * 不能写 `process.pid()`：那是 OpenJDK 9 的 API，**不在 Android 公开 SDK 里**
     * （SDK 平台目录下 data 里的 api-versions.xml，`java/lang/Process` 只有 destroy /
     * destroyForcibly / isAlive / waitFor，没有 pid），AGP 9 按 minSdk 过滤核心库后
     * 直接报 `Unresolved reference 'pid'`，编译都过不去。
     *
     * 三条退路，从最不碰非 SDK 接口的开始：
     * 1. `toString()`：Android 14（API 34）起核心库换成 OpenJDK 17，ProcessImpl 的
     *    toString 形如 `Process[pid=1234, exitValue=...]` —— 纯字符串，不碰隐藏 API；
     * 2. 反射 `pid()`：同样只有新运行时才有，且是非 SDK 接口，可能被拦；
     * 3. 沿类继承链反射 `pid` 字段（Android 的 ProcessImpl 长期带这个字段）。
     *
     * 全失败返回 null。调用方据此跳过杀树，但**必须照常 destroy 宿主** —— 拿不到 pid
     * 只是退回旧行为，不能连停都停不掉。
     */
    fun pidOf(process: Process): Int? {
        runCatching { parsePidFromToString(process.toString()) }.getOrNull()?.let { return it }
        runCatching {
            when (val v = process.javaClass.getMethod("pid").invoke(process)) {
                is Long -> v.toInt()
                is Int -> v
                else -> null
            }
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        var cls: Class<*>? = process.javaClass
        while (cls != null) {
            val owner = cls
            val fromField = runCatching {
                val field = owner.getDeclaredField("pid")
                field.isAccessible = true
                when (val v = field.get(process)) {
                    is Int -> v
                    is Long -> v.toInt()
                    else -> null
                }
            }.getOrNull()?.takeIf { it > 0 }
            if (fromField != null) return fromField
            cls = owner.superclass
        }
        return null
    }

    /**
     * `/proc/<pid>/stat` 里 comm 之后的字段表：state、ppid、pgrp…
     *
     * comm 由进程自己决定，可以含空格甚至右括号（`(weird) name)`），所以必须从
     * **最后一个** `)` 切，不能按空白硬拆前三个字段。切不出来给空表。
     */
    private fun statFieldsAfterComm(statContent: String): List<String> {
        val afterComm = statContent.substringAfterLast(')', "").trim()
        if (afterComm.isEmpty()) return emptyList()
        return afterComm.split(Regex("\\s+"))
    }

    /**
     * 解析 `/proc/<pid>/stat` 的 state 字段（R/S/Z/…）——comm 之后的第 1 个字段。
     * 解析不出来返回 null。
     */
    fun procStatState(statContent: String): String? =
        statFieldsAfterComm(statContent).firstOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 解析 `/proc/<pid>/stat` 的 ppid —— comm 之后的第 2 个字段（第 1 个是 state）。
     * 这是整个杀树唯一的父子关系来源，理由见类注释"教训一"。
     * 解析不出来返回 null；ppid 允许为 0（init 的父是 0），只拒绝负数和非数字。
     */
    fun procStatPpid(statContent: String): Int? =
        statFieldsAfterComm(statContent).getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }

    /**
     * 自底向上（叶子优先）列出 [rootPid] 的全部后代；root 本身不在结果里。
     * 纯函数：[childrenOf] 给不出子表（进程已死 / 读不到）时当空表处理。
     * 防御环路：拼接 /proc 快照期间进程随时进退，理论上可能出现自指。
     */
    fun descendantsBottomUp(rootPid: Int, childrenOf: (Int) -> List<Int>): List<Int> {
        val order = LinkedHashSet<Int>()
        val visiting = HashSet<Int>()
        fun walk(pid: Int) {
            if (!visiting.add(pid)) return
            for (child in runCatching { childrenOf(pid) }.getOrDefault(emptyList())) {
                if (child == pid || child == rootPid || child in order) continue
                walk(child)
                order += child
            }
        }
        walk(rootPid)
        return order.toList()
    }

    /**
     * 杀 [rootPid]（宿主 proot）的整个后代树：先自底向上 SIGTERM，宽限 [termGraceMs]，
     * 再对幸存者补 SIGKILL。[rootPid] 自己不在此列，随后交给 [killHost]。
     * **必须在动宿主 proot 之前调用**，理由见类注释。
     *
     * 先杀叶子再杀父：若先杀 guest 首进程（bash），proot 会随之退出并把残余 tracee
     * 就地孤儿化 —— 自底向上则每一步父进程都还在，树不会中途断掉。
     * 单个 pid 的信号失败（ESRCH：发信号间隙死了；EPERM：不该发生但防 ROM 差异）
     * 不中断整棵树的清理。
     */
    fun killTree(
        rootPid: Int,
        childrenOf: (Int) -> List<Int> = procChildrenReader(),
        isAlive: (Int) -> Boolean = procAlive(),
        sendSignal: (pid: Int, signal: Int) -> Unit = ::platformSendSignal,
        termGraceMs: Long = TERM_GRACE_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val tree = descendantsBottomUp(rootPid, childrenOf)
        val terminated = ArrayList<Int>(tree.size)
        val gone = ArrayList<Int>()
        for (pid in tree) {
            if (!runCatching { isAlive(pid) }.getOrDefault(false)) {
                gone += pid
                continue
            }
            runCatching { sendSignal(pid, SIGTERM) }
                .onSuccess { terminated += pid }
                .onFailure { gone += pid }
        }
        if (terminated.isNotEmpty()) {
            runCatching { sleep(termGraceMs) }
        }
        val killed = ArrayList<Int>()
        for (pid in terminated) {
            if (runCatching { isAlive(pid) }.getOrDefault(false)) {
                runCatching { sendSignal(pid, SIGKILL) }.onSuccess { killed += pid }
            }
        }
        return Result(
            tree = tree,
            terminated = terminated,
            killed = killed,
            goneBeforeSignal = gone,
        )
    }

    /** [killHost] 走到的最后一步，只用于日志诊断 */
    enum class HostOutcome {
        /** 发信号前就已经不在了（被后代拖死 / 上一次 stop 已经收掉） */
        AlreadyGone,

        /** 吃 SIGTERM 就退了 */
        ExitedOnTerm,

        /** 扛过 SIGTERM，补 SIGKILL 后消失 —— proot 的正常形态 */
        Killed,

        /** 连 SIGKILL 之后都还在：不该发生，出现就说明 pid 认错了或权限不对 */
        Survived,
    }

    /**
     * 杀宿主 proot 本身：SIGTERM → 宽限 → SIGKILL → 复核。
     *
     * 为什么不交给 `Process.destroy()` / `destroyForcibly()`：见类注释"教训二"——
     * 在 Android 上这两个方法都只发 SIGTERM，而 proot 扛得住 SIGTERM。
     * **必须在 [killTree] 之后调用**：宿主先死的话后代会被 reparent 到 init，再也找不到。
     */
    fun killHost(
        hostPid: Int,
        isAlive: (Int) -> Boolean = procAlive(),
        sendSignal: (pid: Int, signal: Int) -> Unit = ::platformSendSignal,
        termGraceMs: Long = TERM_GRACE_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): HostOutcome {
        fun alive() = runCatching { isAlive(hostPid) }.getOrDefault(false)
        if (!alive()) return HostOutcome.AlreadyGone
        // TERM 失败（ESRCH）等同于"它自己刚没了"，不必再往下升级
        val termed = runCatching { sendSignal(hostPid, SIGTERM) }.isSuccess
        if (!termed) return if (alive()) HostOutcome.Survived else HostOutcome.AlreadyGone
        runCatching { sleep(termGraceMs) }
        if (!alive()) return HostOutcome.ExitedOnTerm
        if (!runCatching { sendSignal(hostPid, SIGKILL) }.isSuccess) {
            return if (alive()) HostOutcome.Survived else HostOutcome.Killed
        }
        // SIGKILL 是异步的：内核收下之后进程还要走完退出、变僵尸、被 App 的 reaper
        // 线程 waitpid 掉，所以复核前必须再等一个宽限，否则稳定误报 Survived。
        runCatching { sleep(termGraceMs) }
        return if (alive()) HostOutcome.Survived else HostOutcome.Killed
    }

    /**
     * 扫一遍 `/proc` 的数字目录，拼出 pid→ppid 全表。
     *
     * 别的 uid 的进程读 stat 会 EACCES —— 直接跳过就好：App 只可能杀自己 uid 下的
     * 后代，读不到的那些本来也不在树里。目录在扫描途中消失同理（进程正常退出）。
     */
    fun readParentTable(procRoot: File = File("/proc")): Map<Int, Int> {
        val entries = procRoot.listFiles() ?: return emptyMap()
        val table = HashMap<Int, Int>(entries.size.coerceAtMost(1024))
        for (entry in entries) {
            val pid = entry.name.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
            val ppid = procStatPpid(stat) ?: continue
            table[pid] = ppid
        }
        return table
    }

    /** 把 pid→ppid 反过来索引成 ppid→子表；子表按 pid 升序，结果可预期 */
    fun childrenIndex(parentOf: Map<Int, Int>): Map<Int, List<Int>> {
        val index = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parentOf) {
            if (pid == ppid) continue // 自指：快照拼接期间的脏读，别喂给 DFS
            index.getOrPut(ppid) { ArrayList() } += pid
        }
        return index.mapValues { (_, kids) -> kids.sorted() }
    }

    /**
     * 基于 `/proc` 的直接子表读取器。
     *
     * **一次性快照**：构造时扫全表，返回的闭包只查内存里的索引。杀树期间新 fork 出来的
     * 进程因此不在树里 —— 可以接受：自底向上 TERM 的过程中父进程正在死，没有人再生孩子；
     * 换成每问一次就扫一遍 `/proc`（几百个目录）反而把停止拖慢一个数量级。
     */
    fun procChildrenReader(procRoot: File = File("/proc")): (Int) -> List<Int> {
        val index = childrenIndex(readParentTable(procRoot))
        return { pid -> index[pid].orEmpty() }
    }

    /**
     * 基于 `/proc` 的存活判断，僵尸（Z）当死：被杀的 tracee 在 proot 回收前短暂呈
     * 僵尸态，此时再补信号无意义（kill(2) 对僵尸成功返回但什么也不做），如当作
     * 幸存者会把 KILL 名单记脏。
     */
    fun procAlive(procRoot: File = File("/proc")): (Int) -> Boolean = { pid ->
        val dir = File(procRoot, pid.toString())
        if (!dir.exists()) {
            false
        } else {
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
            val state = stat?.let(::procStatState)
            // stat 读不到时退化为"目录在即活"
            state == null || (state != "Z" && state != "X")
        }
    }

    private fun platformSendSignal(pid: Int, signal: Int) {
        android.os.Process.sendSignal(pid, signal)
    }
}
