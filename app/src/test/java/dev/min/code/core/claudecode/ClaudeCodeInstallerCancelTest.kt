package dev.min.code.core.claudecode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 取消安装必须一路抛出去：不能被「换下一个镜像再试」吞掉 */
class ClaudeCodeInstallerCancelTest {

    @Test
    fun `cancellation during a download is not retried on the next mirror`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            val dest = File(h.dir, "pkg.tgz")
            val states = mutableListOf<ClaudeCodeInstaller.InstallState>()
            try {
                h.installer.fetchVerified(
                    // 两个源 × 每源多次重试：吞掉取消的话 onState 会被调用很多次
                    sources = listOf("https://primary.invalid/a", "https://mirror.invalid/a"),
                    dest = dest,
                    expected = ClaudeCodeInstaller.Digest("SHA-256", "00"),
                    what = "测试包",
                    onState = { state ->
                        states += state
                        throw CancellationException("user cancelled")
                    },
                )
                fail("应当抛出 CancellationException")
            } catch (e: CancellationException) {
                assertEquals("user cancelled", e.message)
            }
            // 只有第一次尝试开头的那条 Downloading，没有进入重试
            assertEquals(1, states.size)
            assertTrue(states.single() is ClaudeCodeInstaller.InstallState.Downloading)
        }
    }
}
