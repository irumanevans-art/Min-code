package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 桌面 CLI 交给内核的环境变量读取口：自己读模型 key 的那个变量不能漏给 http hook 的头插值 */
class HookEnvReaderTest {
    private val process = mapOf("ANTHROPIC_API_KEY" to "sk-live", "HOOK_TOKEN" to "t0k")

    @Test
    fun `the model key variable reads as unset, others pass through`() {
        val read = hookEnvReader(setOf("ANTHROPIC_API_KEY"), process::get)
        assertNull(read("ANTHROPIC_API_KEY"))
        assertEquals("t0k", read("HOOK_TOKEN"))
        assertNull(read("NOT_SET"))
    }

    @Test
    fun `with nothing hidden everything passes`() {
        assertEquals("sk-live", hookEnvReader(emptySet(), process::get)("ANTHROPIC_API_KEY"))
    }
}
