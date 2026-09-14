package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCodeModelCommandTest {
    @Test
    fun `model command argument is handled locally`() {
        assertEquals("fable", modelSlashArgument("/model fable"))
        assertEquals("claude-fable-5-1[1m]", modelSlashArgument("/models claude-fable-5-1[1m]"))
    }

    @Test
    fun `bare model command and whitespace arguments remain panel or normal input`() {
        assertNull(modelSlashArgument("/model"))
        assertNull(modelSlashArgument("/model fable latest"))
        assertNull(modelSlashArgument("please /model fable"))
    }

    @Test
    fun `friendly fable version resolves to relay model id`() {
        val models = listOf(
            dev.min.code.core.claudecode.ClaudeCodeManager.RelayModel("claude-fable-5-1"),
            dev.min.code.core.claudecode.ClaudeCodeManager.RelayModel("claude-opus-5"),
        )
        assertEquals("claude-fable-5-1", resolveSlashModel("fable5.1", models))
        assertEquals("claude-fable-5-1[1m]", resolveSlashModel("fable5.1[1m]", models))
        assertEquals("fable", resolveSlashModel("fable", models))
        assertEquals("claude-fable-5-1", resolveSlashModel("fable5.1", emptyList()))
    }
}
