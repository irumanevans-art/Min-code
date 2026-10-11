package dev.min.agent.tools

import dev.min.agent.spi.FeedbackDraft
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackBundleTest {
    /** 假 key / 假令牌：只用于断言它不出现在导出的 zip 里，不是真凭证 */
    private val fakeKey = "sk-ant-api03-FAKEKEY0123456789abcdef"
    private val fakeToken = "ghp_FAKETOKEN0123456789"

    private fun entries(zip: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().decodeToString()
            }
        }
        return out
    }

    @Test
    fun `the zip holds the draft and the version, and the session log only when given`() {
        val draft = FeedbackDraft("rg 超时", "工具连续失败三次")
        val without = entries(FeedbackBundle.build(draft, null, "min 1.2.3", env = emptyMap(), settings = emptyMap()))
        assertEquals(setOf("draft.md", "version.txt", "config.txt"), without.keys)
        assertTrue(without.getValue("draft.md").contains("rg 超时"))
        assertTrue(without.getValue("version.txt").contains("min 1.2.3"))

        val with = entries(FeedbackBundle.build(draft, "第一行\n第二行", "min 1.2.3", env = emptyMap(), settings = emptyMap()))
        assertEquals(setOf("draft.md", "version.txt", "config.txt", "session-log.txt"), with.keys)
        assertTrue(with.getValue("session-log.txt").contains("第二行"))
    }

    @Test
    fun `no key or token from the environment or the settings reaches the exported zip`() {
        // 环境与设置里都带上假 key：导出的每个条目都不能含它们的值
        val env = mapOf("ANTHROPIC_API_KEY" to fakeKey, "HOME" to "/data/home", "INTERNAL_PASSWORD" to "plainpw-XYZ123")
        val settings = mapOf("github.token" to fakeToken, "theme" to "dark")
        // 草稿正文与会话日志里模型或工具也可能把 key 写了出来
        val draft = FeedbackDraft("鉴权失败", "我把 $fakeKey 粘进了命令里，还有 $fakeToken")
        val log = "Authorization: Bearer $fakeKey\n$fakeToken"
        val zip = FeedbackBundle.build(draft, log, "min 1.2.3", env, settings)
        val all = entries(zip).values.joinToString("\n")
        assertFalse(all, all.contains("FAKEKEY"))
        assertFalse(all, all.contains("FAKETOKEN"))
        // 不带 sk-/gh_ 形状的凭证只能靠值替换去掉：这一条断言的就是值替换本身
        assertFalse(all, all.contains("plainpw-XYZ123"))
        assertTrue(all, all.contains("HOME=/data/home"))
        assertTrue(all, all.contains("theme=dark"))
        assertTrue(all, all.contains("ANTHROPIC_API_KEY=[已去除]"))
    }

    @Test
    fun `key-shaped strings are redacted even when they were not in the environment`() {
        val draft = FeedbackDraft("t", "随手写的 sk-ant-api03-UNKNOWNKEY9999999999 与 Bearer abc.def.ghi")
        val all = entries(FeedbackBundle.build(draft, null, "v", emptyMap(), emptyMap())).values.joinToString("\n")
        assertFalse(all, all.contains("UNKNOWNKEY"))
        assertFalse(all, all.contains("abc.def.ghi"))
    }

    @Test
    fun `the leak scan finds a secret that survived, so a bad export cannot be written`() {
        assertTrue(FeedbackBundle.leaks(mapOf("a.txt" to "xx $fakeKey yy"), setOf(fakeKey)))
        assertFalse(FeedbackBundle.leaks(mapOf("a.txt" to "clean"), setOf(fakeKey)))
    }
}
