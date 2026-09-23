package dev.min.code.core.claudecode

import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Running)))
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Starting)))
    }

    @Test
    fun `a missing interpreter is named and the last log line quoted`() {
        val note = hostedEarlyExitNote(
            service(
                LocalServiceStatus.Failed,
                exitCode = 127,
                stopReason = LocalServiceStopReason.Crash,
                logTail = "\nbash: line 1: python3: command not found\n\n",
            )
        )
        assertEquals(
            "托管的服务刚启动就退出了（exit 127，命令不存在）：bash: line 1: python3: command not found。" +
                "模型收到的是「已托管」，还以为它在跑。",
            note,
        )
    }

    @Test
    fun `other exit codes are shown as is, without a log line when there is none`() {
        assertEquals(
            "托管的服务刚启动就退出了（exit 1）。模型收到的是「已托管」，还以为它在跑。",
            hostedEarlyExitNote(service(LocalServiceStatus.Failed, exitCode = 1, stopReason = LocalServiceStopReason.Crash)),
        )
    }

    @Test
    fun `a service the user stopped is not reported as a crash`() {
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Exited, 143, LocalServiceStopReason.UserStop)))
        assertNull(hostedEarlyExitNote(service(LocalServiceStatus.Exited, 143, LocalServiceStopReason.StopAll)))
    }
}
