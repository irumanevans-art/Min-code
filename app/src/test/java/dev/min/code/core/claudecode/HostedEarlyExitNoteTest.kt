package dev.min.code.core.claudecode

import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedEarlyExitNoteTest {

    private fun service(
        status: LocalServiceStatus,
        exitCode: Int? = null,
        stopReason: LocalServiceStopReason? = null,
        logTail: String = "",
    ) = LocalService(
        id = "s1",
        label = "http.server :8799",
        command = "python3 -m http.server 8799",
        cwdGuest = "/workspace",
        port = 8799,
        status = status,
        startedAtEpochMs = 0,
        exitCode = exitCode,
        stopReason = stopReason,
        logTail = logTail,
    )

    @Test
    fun `a service that is up says nothing`() {
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Running), toldModel = true))
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Starting), toldModel = true))
    }

    @Test
    fun `a missing interpreter is named and the last log line quoted`() {
        val note = hostedEarlyExitNote(
            service(
                LocalServiceStatus.Failed,
                exitCode = 127,
                stopReason = LocalServiceStopReason.Crash,
                logTail = "\nbash: line 1: python3: command not found\n\n",
            ),
            toldModel = true,
        )
        assertEquals(
            "托管的服务刚启动就退出了（exit 127，命令不存在）：bash: line 1: python3: command not found。" +
                "已把原因告诉模型。",
            note,
        )
    }

    @Test
    fun `other exit codes are shown as is, without a log line when there is none`() {
        assertEquals(
            "托管的服务刚启动就退出了（exit 1）。模型还不知道它没起来。",
            hostedEarlyExitNote(
                service(LocalServiceStatus.Failed, exitCode = 1, stopReason = LocalServiceStopReason.Crash),
                toldModel = false,
            ),
        )
    }

    @Test
    fun `a service the user stopped is not reported as a crash`() {
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Exited, 143, LocalServiceStopReason.UserStop), toldModel = true))
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Exited, 143, LocalServiceStopReason.StopAll), toldModel = true))
    }

    // ---------------------------------------------------------------- 回给模型的 deny

    @Test
    fun `a running service is reported as hosted with its preview`() {
        val msg = hostedDenyMessage(HostOutcome.Up, 8799)
        assertTrue(msg.startsWith("Min hosted this as a background service in the process table · preview http://127.0.0.1:8799."))
        assertTrue("Do not re-run the same server command" in msg)
    }

    @Test
    fun `a dead service hands the model the exit code and the log tail`() {
        val log = (1..12).joinToString("\n") { "line $it" } + "\nbash: line 1: python3: command not found\n"
        val msg = hostedDenyMessage(
            HostOutcome.Died(service(LocalServiceStatus.Failed, 127, LocalServiceStopReason.Crash, logTail = log)),
            8799,
        )
        assertTrue(msg, "exited right away (exit 127: command not found). The service is NOT running." in msg)
        // 只带最后 8 行
        assertTrue(msg, "line 6\nline 7" in msg && "line 5\n" !in msg)
        assertTrue(msg, msg.contains("python3: command not found\nFix the cause"))
        assertFalse(msg, "preview" in msg)
    }

    @Test
    fun `a dead service that printed nothing says so`() {
        val msg = hostedDenyMessage(HostOutcome.Died(service(LocalServiceStatus.Failed, 1, LocalServiceStopReason.Crash)), null)
        assertTrue(msg, "(exit 1). The service is NOT running. It printed nothing. Fix the cause" in msg)
    }

    @Test
    fun `a service that never started carries the reason`() {
        val msg = hostedDenyMessage(HostOutcome.NotStarted("port 8799 busy"), 8799)
        assertEquals(
            "Min could not start this as a background service: port 8799 busy. The service is NOT running. " +
                "Fix the cause, then run it again.",
            msg,
        )
    }
}
