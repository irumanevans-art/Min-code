package dev.min.code.core.claudecode

import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务表和进程同生共死：进程没了，底栏不能还挂着一个停不掉的「1 shell」。
 * 夹具借 plain_reply 的握手，后台任务的帧由测试直接吐。
 */
class ClaudeCodeManagerTasksTest {

    private fun shellStarted(id: String) =
        """{"type":"system","subtype":"task_started","task_id":"$id","task_type":"local_bash",
           "tool_use_id":"toolu_$id","description":"tick","is_backgrounded":true}""".replace("\n", "")

    private suspend fun ManagerHarness.startWithShell() {
        startAndHandshake()
        process.emit(shellStarted("b1"))
        awaitState("后台 shell 进表") { s -> s.tasks.any { it.id == "b1" && it.isRunning } }
    }

    @Test
    fun `the CLI dying takes its background shells with it`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startWithShell()
            h.process.crash(1)
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed }
            assertTrue(s.tasks.isEmpty())
        }
    }

    @Test
    fun `stopping the session clears the task table`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startWithShell()
            h.manager.stopSession()
            val s = h.awaitState("停止") { it.status == SessionStatus.Closed && !it.stopping }
            assertTrue(s.tasks.isEmpty())
        }
    }

    /** 换档是换一个进程续会话：新进程不认识旧进程的 task_id，旧的 shell 也已经随旧进程退了 */
    @Test
    fun `relaunching for a new effort drops the old process's tasks`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startWithShell()
            h.manager.applyEffort("high")
            val s = h.awaitState("重启完成") {
                h.launches.size == 2 && it.status == SessionStatus.Running && !it.applyingEffort
            }
            assertTrue(s.tasks.isEmpty())
        }
    }

    /** 整表里没有它了 = CLI 说它不活了；没等到 task_notification 也不能留着在跑 */
    @Test
    fun `an empty live set ends a background shell`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startWithShell()
            h.process.emit("""{"type":"system","subtype":"background_tasks_changed","tasks":[],"uuid":"u","session_id":"s"}""")
            val s = h.awaitState("对账") { s -> s.tasks.none { it.isRunning } }
            assertTrue(s.tasks.single().endedAt != null)
        }
    }
}
