package me.rerere.workspace

import java.io.File

/**
 * 让 Android 上的 proot 容器更像一台普通 Linux。
 *
 * ## 为什么需要它
 *
 * Android 对普通 App（SELinux 的 untrusted_app）禁读一批 /proc 条目：`/proc/loadavg`、
 * `/proc/stat`、`/proc/uptime`、`/proc/version`、`/proc/vmstat`、`/proc/sys/kernel/cap_last_cap`、
 * `/proc/sys/fs/inotify/max_user_watches` 在 App 进程里全是 Permission denied（真机实测）。
 * 而 proot 是把宿主的 /proc 整个绑进容器的，于是容器里 `nproc`、`uptime`、`top`、Node 的
 * `os.loadavg()`、各种文件 watcher（要读 inotify 上限）都会撞上 EACCES 或直接崩。
 *
 * Termux 的 proot-distro 的解法是：宿主读不到的条目，用一个假文件绑上去。这里照做。
 * 内容不求真实，只求格式正确、数字合理——没有程序会拿手机上的 loadavg 做决策。
 *
 * ## /dev/shm
 *
 * Android 没有 /dev/shm（它用 ashmem/memfd），宿主 /dev 绑进来后容器里也就没有。
 * POSIX 共享内存（Python multiprocessing 的 sem_open、Chromium）全靠它。
 * glibc 的 shm_open/sem_open 只是在 /dev/shm 下 open + mmap，不要求 tmpfs，普通目录顶上即可。
 */
object ProotCompat {
    /** 相对 /proc 的路径 → 生成内容 */
    private val FAKE_PROC: Map<String, () -> String> = linkedMapOf(
        "loadavg" to { "0.12 0.08 0.05 1/64 1024\n" },
        "stat" to ::fakeStat,
        "uptime" to { "1234.56 4321.00\n" },
        "version" to { "Linux version ${kernelRelease()} (min@android) (gcc version 13.2.0) #1 SMP PREEMPT\n" },
        "vmstat" to ::fakeVmstat,
        "sys/kernel/cap_last_cap" to { "40\n" },
        "sys/fs/inotify/max_user_watches" to { "524288\n" },
        "sys/fs/inotify/max_user_instances" to { "1024\n" },
        "sys/fs/inotify/max_queued_events" to { "16384\n" },
    )

    /**
     * 返回要追加的 `-b host:guest` 绑定。假文件放在 [tempDir]/proc 下，每次启动按需生成
     * （tmp 目录可能被清理，所以不缓存"已生成"状态）。
     */
    fun procBinds(tempDir: File): List<Pair<File, String>> {
        val fakeRoot = File(tempDir, "proc")
        return FAKE_PROC.mapNotNull { (rel, content) ->
            val host = File("/proc/$rel")
            if (hostReadable(host)) return@mapNotNull null
            val fake = File(fakeRoot, rel)
            runCatching {
                fake.parentFile?.mkdirs()
                if (!fake.isFile || fake.length() == 0L) fake.writeText(content())
            }.getOrNull() ?: return@mapNotNull null
            fake to "/proc/$rel"
        }
    }

    /** /dev/shm 的替身目录 */
    fun shmBind(tempDir: File): Pair<File, String>? {
        val dir = File(tempDir, "shm")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir to "/dev/shm"
    }

    /** 所有额外绑定，给 proot 命令行拼 `-b` 用 */
    fun extraBinds(tempDir: File): List<Pair<File, String>> =
        listOfNotNull(shmBind(tempDir)) + procBinds(tempDir)

    /**
     * `canRead()` 走的是 access(2)，SELinux 同样会在那里拒绝，所以它是可靠的。
     * 但保险起见再真读一下：某些内核对 access 放行、read 才拒。
     */
    private fun hostReadable(file: File): Boolean = runCatching {
        file.canRead() && file.inputStream().use { it.read(ByteArray(1)) >= 0 }
    }.getOrDefault(false)

    private fun kernelRelease(): String = runCatching {
        File("/proc/sys/kernel/osrelease").readText().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getProperty("os.version")?.takeIf { it.isNotBlank() }
        ?: "6.1.0"

    /** /proc/stat 的 cpu 行数要和真实核数一致，nproc 之类靠它数 CPU */
    private fun fakeStat(): String {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val boot = System.currentTimeMillis() / 1000 - 1234
        return buildString {
            appendLine("cpu  1000 0 500 100000 100 0 50 0 0 0")
            repeat(cores) { i ->
                appendLine("cpu$i ${1000 / cores} 0 ${500 / cores} ${100000 / cores} ${100 / cores} 0 ${50 / cores} 0 0 0")
            }
            appendLine("intr 0")
            appendLine("ctxt 0")
            appendLine("btime $boot")
            appendLine("processes 1")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 0 0 0 0 0 0 0 0 0 0 0")
        }
    }

    private fun fakeVmstat(): String = """
        nr_free_pages 300000
        nr_zone_inactive_anon 0
        nr_zone_active_anon 0
        nr_zone_inactive_file 0
        nr_zone_active_file 0
        nr_zone_unevictable 0
        nr_zone_write_pending 0
        nr_mlock 0
        nr_free_cma 0
        nr_inactive_anon 0
        nr_active_anon 0
        nr_inactive_file 0
        nr_active_file 0
        nr_unevictable 0
        nr_slab_reclaimable 0
        nr_slab_unreclaimable 0
        nr_anon_pages 0
        nr_mapped 0
        nr_file_pages 0
        nr_dirty 0
        nr_writeback 0
        nr_shmem 0
        pgpgin 0
        pgpgout 0
        pswpin 0
        pswpout 0
        pgfault 0
        pgmajfault 0
    """.trimIndent() + "\n"
}
