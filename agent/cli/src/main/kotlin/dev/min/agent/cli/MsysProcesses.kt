package dev.min.agent.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Windows 上 Git Bash（msys）进程树的补充发现。
 *
 * 为什么需要：msys 用自己的 fork + exec 仿真，`sh -c "sleep 99 &"` 里的 sleep 在 Windows 眼里**没有父进程**
 * （`ProcessHandle.parent()` 为空，实测），所以 `ProcessHandle.descendants()` 找不到它，只按 Java 树回收会漏掉。
 * msys 自己的进程表（`ps -W`，列：PID PPID PGID WINPID …）记着真实的父子关系，用它从已知成员出发补全。
 *
 * 只在关停时调用，每次要起一个 bash 去跑 `ps`（约 0.1–0.3 秒）；ps 跑不起来就当没有补充，退回 Java 树。
 *
 * **根进程的 msys pid 由启动脚本自己写下来**（[pidFile]，脚本第一行 `echo $$`）。回收是「发现 + 杀」反复做
 * （见 `JvmLiveProcess.sweepKill`），孩子靠 PPID 或 PGID 连回已记下的 msys pid，爸爸已经死了也认得回。
 * 但刚起的进程还没在 msys 里登记时，光靠「Windows pid → msys 行」认不出根：此时 fork 出来的孩子成了孤儿就再也找不回
 * （实测：spawn 中途取消，15 次里漏过一个 sleep）。有了脚本自己报的 pid，这个窗口就闭上了。
 */
internal class MsysProcesses(private val shell: String = "bash") {
    /** 每个进程树一份（有状态）：见过的 msys pid 记下来，爸爸死了之后仍能顺着它找到孩子 */
    fun newDiscoverer(pidFile: Path): (List<Long>) -> List<Long> {
        val seeds = HashSet<Long>()
        return { known ->
            readPidFile(pidFile)?.let { seeds += it }
            val rows = readTable()
            if (rows == null) emptyList() else descendants(rows, known, seeds)
        }
    }

    private fun readPidFile(file: Path): Long? = try {
        Files.readString(file).trim().toLongOrNull()
    } catch (_: java.io.IOException) {
        null
    }

    private fun readTable(): List<Row>? = try {
        val ps = ProcessBuilder(shell, "-c", "ps -W").redirectErrorStream(true).start()
        ps.outputStream.close()
        val text = ps.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!ps.waitFor(PS_TIMEOUT_S, TimeUnit.SECONDS)) {
            ps.destroyForcibly()
            null
        } else {
            parse(text)
        }
    } catch (_: java.io.IOException) {
        null
    }

    internal class Row(val pid: Long, val ppid: Long, val pgid: Long, val winpid: Long)

    internal companion object {
        private const val PS_TIMEOUT_S = 10L

        /** `ps -W` 里 msys 不认识的原生 Windows 进程，pid 被表示成 winpid + 0x400000，ppid 为 0：它们不是 msys 的父子链成员 */
        private const val NATIVE_PID_BASE = 0x400000L

        fun parse(text: String): List<Row> = text.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"), limit = 5)
            if (cols.size < 4) return@mapNotNull null
            val nums = cols.take(4).map { it.toLongOrNull() ?: return@mapNotNull null }
            Row(nums[0], nums[1], nums[2], nums[3])
        }.toList()

        /**
         * 从 [seeds]（以前见过的 msys pid）与 [known]（已知成员的 Windows pid）出发，找出 msys 进程表里所有后代的 Windows pid：
         * 一行只要 PPID 或 PGID 落在已收集的集合里就并入，反复到不再增长。PGID 这一条是为了爸爸已死、父子链断了的孤儿
         * （非交互 shell 里孩子和根同属一个进程组）。[seeds] 会被更新；返回值不含 [known] 自己。
         */
        fun descendants(rows: List<Row>, known: List<Long>, seeds: MutableSet<Long> = HashSet()): List<Long> {
            val real = rows.filter { it.pid < NATIVE_PID_BASE }
            val byWin = real.associateBy { it.winpid }
            for (pid in known) byWin[pid]?.let { seeds += it.pid }
            seeds.removeAll(setOf(0L, 1L))
            var grew = true
            while (grew) {
                grew = false
                for (row in real) {
                    if (row.pid in seeds) continue
                    val linked = (row.ppid in seeds && row.ppid > 1) || (row.pgid in seeds && row.pgid > 1)
                    if (linked) {
                        seeds += row.pid
                        grew = true
                    }
                }
            }
            // 凡是 pid 已在集合里的行都要交出它「当前」的 Windows pid：msys 的 exec 会换掉背后的 Windows 进程，
            // 同一个 msys pid 前后对应两个 winpid，只杀先前见过的那个会漏掉 exec 之后的真身（实测漏过 sleep）
            val out = LinkedHashSet<Long>()
            for (row in real) if (row.pid in seeds) out += row.winpid
            out.removeAll(known.toSet())
            return out.toList()
        }
    }
}
