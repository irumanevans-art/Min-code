package dev.min.code.ui.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令的判据。拦错一条就是把用户想说的话吞掉，漏拦一条就是白花一轮的钱，
 * 两个方向都得钉住。
 */
class CodexSlashTest {

    @Test
    fun `a bare local command is recognised`() {
        assertEquals(CodexSlash.MODEL, codexSlashTarget("/model"))
        assertEquals(CodexSlash.EFFORT, codexSlashTarget("/effort"))
        assertEquals(CodexSlash.NEW, codexSlashTarget("/new"))
        assertEquals(CodexSlash.PERMISSIONS, codexSlashTarget("/permissions"))
        assertEquals(CodexSlash.APPROVALS, codexSlashTarget("/approvals"))
        assertEquals(CodexSlash.CD, codexSlashTarget("/cd"))
    }

    /** 每条打开设置的命令都落到它说的那一栏；/new 不是设置 */
    @Test
    fun `settings commands open their own section`() {
        assertEquals(CodexSettingsSection.MODEL, CodexSlash.MODEL.toSection())
        assertEquals(CodexSettingsSection.EFFORT, CodexSlash.EFFORT.toSection())
        assertEquals(CodexSettingsSection.PERMISSIONS, CodexSlash.PERMISSIONS.toSection())
        assertEquals(CodexSettingsSection.PERMISSIONS, CodexSlash.APPROVALS.toSection())
        assertEquals(CodexSettingsSection.CWD, CodexSlash.CD.toSection())
        assertNull(CodexSlash.NEW.toSection())
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(CodexSlash.MODEL, codexSlashTarget("  /MODEL  "))
    }

    /** 带参数的不认：Min 的模型在面板里挑，认一半会让人以为另一半也认 */
    @Test
    fun `a command with an argument is not taken over`() {
        assertNull(codexSlashTarget("/model gpt-5.1-codex-max"))
    }

    /** 正文里的路径不是命令 —— 拦住它等于把用户想说的话吞了 */
    @Test
    fun `prose that merely contains a slash is left alone`() {
        assertNull(codexSlashTarget("看看 /workspace/a.kt"))
        assertNull(codexSlashTarget("/workspace/a.kt 改一下"))
        assertTrue(codexSlashMatches("看看 /workspace/a.kt").isEmpty())
    }

    @Test
    fun `unknown commands go through as plain text`() {
        // codex TUI 有 /compact，Min 这边没有对应动作 —— 不认，原样发下去
        assertNull(codexSlashTarget("/compact"))
        assertTrue(codexSlashMatches("/compact").isEmpty())
    }

    @Test
    fun `a lone slash offers everything`() {
        assertEquals(CodexSlash.entries.toList(), codexSlashMatches("/"))
    }

    @Test
    fun `a prefix narrows the candidates`() {
        assertEquals(listOf(CodexSlash.MODEL), codexSlashMatches("/mo"))
        assertEquals(listOf(CodexSlash.NEW), codexSlashMatches("/n"))
    }

    @Test
    fun `an empty draft offers nothing`() {
        assertTrue(codexSlashMatches("").isEmpty())
        assertNull(codexSlashTarget(""))
        assertNull(codexSlashTarget("/"))
    }
}
