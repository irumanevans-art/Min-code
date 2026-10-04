package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Test

/** `ps -W` 表的解析与「从已知成员找回孤儿」的纯逻辑，不起进程 */
class MsysProcessesTest {
    private val table = """
              PID    PPID    PGID     WINPID   TTY         UID    STIME COMMAND
          4194308       0       0          4  ?              0   Sep 30 System
          4205412       0       0      11108  ?              0 02:30:00 C:\Program Files\Git\usr\bin\bash.exe
              100       1     100       2000  ?         197609 02:30:00 /usr/bin/sh
              101     100     100       2001  ?         197609 02:30:01 /usr/bin/sh
              102     101     100       2002  ?         197609 02:30:01 /usr/bin/sleep
              103     999     100       2003  ?         197609 02:30:01 /usr/bin/sleep
              200       1     200       3000  ?         197609 02:30:01 /usr/bin/unrelated
              201     200     200       3001  ?         197609 02:30:01 /usr/bin/unrelated
    """.trimIndent()

    private fun descendants(known: List<Long>, seeds: MutableSet<Long> = HashSet()) =
        MsysProcesses.descendants(MsysProcesses.parse(table), known, seeds).toSet()

    @Test
    fun `解析 ps 表 跳过表头 命令列可以含空格`() {
        val rows = MsysProcesses.parse(table)
        assertEquals(8, rows.size)
        assertEquals(2002L, rows.first { it.pid == 102L }.winpid)
        assertEquals(100L, rows.first { it.pid == 103L }.pgid)
    }

    @Test
    fun `从已知成员的 winpid 顺着 PPID 找到后代 不含无关进程`() {
        assertEquals(setOf(2001L, 2002L, 2003L), descendants(listOf(2000L)))
    }

    @Test
    fun `爸爸已死的孤儿靠同一个进程组认回`() {
        // 103 的 PPID 999 不在表里（爸爸死了），但与根同属进程组 100
        assertEquals(setOf(2000L, 2001L, 2002L, 2003L), descendants(emptyList(), mutableSetOf(100L)))
    }

    @Test
    fun `种子来自启动脚本记下的 msys pid 时 根自己的 winpid 也交出来`() {
        // exec 之后同一个 msys pid 背后换了 Windows 进程：当前的 winpid 必须交出来，由调用方去重
        assertEquals(setOf(2000L, 2001L, 2002L, 2003L), descendants(listOf(11108L), mutableSetOf(100L)))
    }

    @Test
    fun `原生 Windows 进程和 init 不会被当成种子 不会把全表拖进来`() {
        assertEquals(emptySet<Long>(), descendants(listOf(4L, 11108L)))
        assertEquals(emptySet<Long>(), descendants(emptyList(), mutableSetOf(0L, 1L)))
    }
}
