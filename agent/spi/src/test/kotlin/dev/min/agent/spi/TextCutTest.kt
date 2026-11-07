package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextCutTest {
    private val emoji = "😀" // 😀：一个高代理 + 一个低代理

    @Test
    fun `head cut that lands inside a pair backs off one char`() {
        val s = "ab$emoji" + "cd"
        assertEquals("ab", s.safeTake(3))
        assertEquals("ab$emoji", s.safeTake(4))
        assertEquals("ab", s.safeTake(2))
        assertEquals(2, s.safeCutEnd(3))
    }

    @Test
    fun `tail cut that lands inside a pair advances one char`() {
        val s = "ab$emoji" + "cd"
        assertEquals("cd", s.safeTakeLast(3))
        assertEquals("$emoji" + "cd", s.safeTakeLast(4))
        assertEquals(4, s.safeCutStart(3))
    }

    @Test
    fun `emoji at both edges of the string`() {
        val s = emoji + "x" + emoji
        assertEquals("", s.safeTake(1))
        assertEquals(emoji, s.safeTake(2))
        assertEquals("", s.safeTakeLast(1))
        assertEquals(emoji, s.safeTakeLast(2))
        assertEquals("x$emoji", s.safeTakeLast(3))
    }

    @Test
    fun `bmp only text behaves like take and takeLast`() {
        val s = "中文 text，没有代理对"
        for (n in 0..s.length + 2) {
            assertEquals(s.take(n), s.safeTake(n))
            assertEquals(s.takeLast(n), s.safeTakeLast(n))
        }
    }

    @Test
    fun `empty string and limits zero and one`() {
        assertEquals("", "".safeTake(0))
        assertEquals("", "".safeTake(1))
        assertEquals("", "".safeTakeLast(0))
        assertEquals("", "".safeTakeLast(1))
        assertEquals("", emoji.safeTake(0))
        assertEquals("", emoji.safeTakeLast(0))
        assertEquals("", emoji.safeTake(1))
        assertEquals("", emoji.safeTakeLast(1))
        assertEquals("a", "ab".safeTake(1))
        assertEquals("b", "ab".safeTakeLast(1))
    }

    @Test
    fun `lone surrogates already in the text are left alone`() {
        // 原文自带的孤立代理不是我们劈出来的，截点两边不成对就不挪
        val s = "a\uD83Db\uDE00c"
        assertEquals("a\uD83D", s.safeTake(2))
        assertEquals("\uDE00c", s.safeTakeLast(2))
    }

    @Test
    fun `index helpers clamp out of range values`() {
        assertEquals(0, "abc".safeCutEnd(-5))
        assertEquals(3, "abc".safeCutEnd(10))
        assertEquals(0, "abc".safeCutStart(-5))
        assertEquals(3, "abc".safeCutStart(10))
    }

    @Test
    fun `negative counts are rejected like the stdlib`() {
        assertThrows(IllegalArgumentException::class.java) { "abc".safeTake(-1) }
        assertThrows(IllegalArgumentException::class.java) { "abc".safeTakeLast(-1) }
    }
}
