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

    /** 解析 `/proc/<pid>/task/<tid>/children`：空白分隔的子 PID，坏词容忍跳过 */
    fun parseChildren(content: String): List<Int> =
        content.trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }

    /**
     * 解析 `/proc/<pid>/stat` 的 state 字段（R/S/Z/…）。
     * comm 可能含空格和括号（如 `(weird) name)`），必须取**最后一个** `)` 之后的
     * 第一个字段；解析不出来返回 null。
     */
    fun procStatState(statContent: String): String? {
        val afterComm = statContent.substringAfterLast(')', "")
        return afterComm.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
    }

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
     * 再对幸存者补 SIGKILL。**必须在 destroy 宿主 proot 之前调用**，理由见类注释。
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

    /**
     * 从 `/proc` 聚合某进程的直接子表：fork 可能来自进程内任意线程，每个线程的
     * children 只挂自己 fork 的孩子，必须遍历所有 task。进程退出时 task 目录随时
     * 消失，单条读取失败不影响整体。
     */
    fun procChildrenReader(procRoot: File = File("/proc")): (Int) -> List<Int> = { pid ->
        val out = LinkedHashSet<Int>()
        val tasks = File(procRoot, "$pid/task").listFiles()
        if (tasks != null) {
            for (task in tasks) {
                runCatching {
                    val children = File(task, "children")
                    if (children.isFile) out += parseChildren(children.readText())
                }
            }
        }
        out.toList()
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
