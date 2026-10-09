package dev.min.agent.kernel

import org.junit.Assert.assertEquals
import org.junit.Test

class HookPromptTextTest {
    private val input = """{"tool_name":"Bash"}"""
    private val args = "\$ARGUMENTS"

    @Test fun `the placeholder becomes the event input`() {
        assertEquals("看看 $input 安全吗", hookPromptText("看看 $args 安全吗", input))
    }

    @Test fun `without the placeholder the input goes at the end`() {
        assertEquals("看看安全吗\n\n$input", hookPromptText("看看安全吗", input))
    }

    @Test fun `a backslash escapes the dollar sign, as in the CC docs`() {
        assertEquals("超过 \$1.00 就拦：$input", hookPromptText("超过 \\\$1.00 就拦：$args", input))
    }

    @Test fun `an escaped placeholder is literal text and does not count as used`() {
        assertEquals("字面的 $args\n\n$input", hookPromptText("字面的 \\$args", input))
    }

    @Test fun `a doubled backslash is not an escape`() {
        assertEquals("a\\\\$input", hookPromptText("a\\\\$args", input))
    }
}
