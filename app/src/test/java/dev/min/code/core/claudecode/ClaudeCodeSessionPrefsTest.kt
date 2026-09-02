package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话配置的编解码。
 *
 * 这一层的存在理由只有一个：**杀掉 App 再进同一个会话，模型不能变**。
 * 注册表在那时会新建一个 manager，它的 `SessionOptions()` 全是默认值 ——
 * `model = null` 会让 CLI 用自己的默认模型，于是 `opus[1m]`（1M 上下文）
 * 悄悄变成 200k，而界面上没有任何提示。
 */
class ClaudeCodeSessionPrefsTest {

    private val opusOneM = ClaudeCodeManager.SessionOptions(
        model = "opus[1m]",
        effort = "high",
        skipPermissions = true,
    )

    @Test
    fun `options round trip`() {
        val decoded = decodeSessionOptions(encodeSessionOptions(opusOneM))
        assertEquals("opus[1m]", decoded.model)
        assertEquals("high", decoded.effort)
        assertTrue(decoded.skipPermissions)
        assertFalse(decoded.ultracode)
    }

    /**
     * `model = null` 的语义是"交给 CLI 决定"，和"名字是空串的模型"完全不同。
     * 编成空串的话，重开会话会把 `--model ''` 传下去。
     */
    @Test
    fun `a null model is encoded as an absent key`() {
        val encoded = encodeSessionOptions(ClaudeCodeManager.SessionOptions(model = null))
        assertFalse(encoded.containsKey(KEY_MODEL))
        assertNull(decodeSessionOptions(encoded).model)
    }

    /** 空串也当没有 —— UI 上清空输入框和从没填过应该是一回事 */
    @Test
    fun `a blank model is treated as absent`() {
        assertFalse(encodeSessionOptions(opusOneM.copy(model = "  ")).containsKey(KEY_MODEL))
        assertFalse(encodeSessionOptions(opusOneM.copy(effort = "")).containsKey(KEY_EFFORT))
    }

    /**
     * 进程绑定的字段绝不能持久化：存下来的话，下次续会话会拿着上辈子的 id
     * 去 `--resume`，CLI 直接报 "No conversation found" 然后退出。
     */
    @Test
    fun `process bound ids are never encoded`() {
        val encoded = encodeSessionOptions(
            opusOneM.copy(resumeSessionId = "old", newSessionId = "older")
        )
        assertFalse(encoded.values.any { it == "old" || it == "older" })

        val decoded = decodeSessionOptions(encoded)
        assertNull(decoded.resumeSessionId)
        assertNull(decoded.newSessionId)
    }

    /** 非法 TTL 要挡掉：CLI 对它是静默忽略，传错了这边一点感知都没有 */
    @Test
    fun `an invalid cache ttl falls back to the default`() {
        val decoded = decodeSessionOptions(
            encodeSessionOptions(opusOneM.copy(promptCacheTtl = "99h"))
        )
        assertEquals(ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL, decoded.promptCacheTtl)
    }

    @Test
    fun `a valid cache ttl survives`() {
        val decoded = decodeSessionOptions(encodeSessionOptions(opusOneM.copy(promptCacheTtl = "5m")))
        assertEquals("5m", decoded.promptCacheTtl)
    }

    /** 空 map（从没存过）要给出厂默认，而不是抛或者给一堆 null 字段 */
    @Test
    fun `an empty map decodes to factory defaults`() {
        val decoded = decodeSessionOptions(emptyMap())
        assertNull(decoded.model)
        assertNull(decoded.effort)
        assertFalse(decoded.skipPermissions)
        assertEquals(ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL, decoded.promptCacheTtl)
    }

    /**
     * 写入时必须遍历**全部**已知键，缺席的要真的删掉。
     * 只写存在的键的话，把模型从 opus 改回"跟随默认"时残留值会让下次又传 opus。
     */
    @Test
    fun `every known key is covered so stale values can be cleared`() {
        val encoded = encodeSessionOptions(opusOneM)
        assertTrue(SESSION_OPTION_KEYS.containsAll(encoded.keys))
        assertTrue(KEY_MODEL in SESSION_OPTION_KEYS)
        assertTrue(KEY_PRESENT in SESSION_OPTION_KEYS)
    }

    @Test
    fun `ultracode is preserved`() {
        val decoded = decodeSessionOptions(
            encodeSessionOptions(ClaudeCodeManager.SessionOptions(ultracode = true))
        )
        assertTrue(decoded.ultracode)
    }
}
