package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 控制请求错误的解释：2.1.283 起按 `error_code` 判，老版本 CLI 按原句兜底。
 * 原句取自 js283 对应分支的字面量。
 */
class ClaudeCodeControlErrorsTest {

    private val bypassRefusal = "Cannot set permission mode to bypassPermissions because the session was not " +
        "launched with --dangerously-skip-permissions"
    private val opus1m = "Opus with 1M context is not available for your account. Learn more: " +
        "https://code.claude.com/docs/en/model-config#extended-context-with-1m"
    private val orgDisabled = "Model 'claude-fable-5-1' is not currently available for your account."

    @Test
    fun `the code wins over the words`() {
        assertEquals(ControlErrorCode.UNAVAILABLE_FOR_ACCOUNT, ControlErrorCode.of("anything", "unavailable_for_account"))
        // 新 code 原样透出来，调用方不认识就当没有
        assertEquals("not_offered", ControlErrorCode.of(opus1m, "not_offered"))
    }

    @Test
    fun `older CLIs without a code are recognised by their words`() {
        assertEquals(ControlErrorCode.BYPASS_NOT_LAUNCHED, ControlErrorCode.of(bypassRefusal, null))
        assertEquals(ControlErrorCode.UNAVAILABLE_FOR_ACCOUNT, ControlErrorCode.of(opus1m, null))
        assertEquals(ControlErrorCode.UNAVAILABLE_FOR_ACCOUNT, ControlErrorCode.of(orgDisabled, ""))
        assertNull(ControlErrorCode.of("boom", null))
    }

    @Test
    fun `known codes get a plain explanation with the CLI's words kept`() {
        val text = describeControlError(opus1m, "unavailable_for_account")
        assertEquals(
            "当前账号用不了这个模型（订阅或组织没开通，1M 上下文版本最常见）。换一个模型，或选不带 [1m] 的那一项（$opus1m）",
            text,
        )
        val bypass = describeControlError(bypassRefusal, "bypass_not_launched")
        assertEquals(
            "这个会话的 CLI 启动时没开放「跳过权限确认」，中途切不过去。重开这个会话后再切（$bypassRefusal）",
            bypass,
        )
    }

    @Test
    fun `unknown codes show the CLI's words as they are`() {
        assertEquals("boom", describeControlError("boom", "check_failed"))
        assertEquals("boom", describeControlError("boom", null))
    }
}
