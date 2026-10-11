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
        val env = mapOf("ANTHROPIC_API_KEY" to fakeKey, "CLAUDE_CODE_USE_BEDROCK" to "1", "MIN_INTERNAL_PASSWORD" to "plainpw-XYZ123")
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
        assertTrue(all, all.contains("CLAUDE_CODE_USE_BEDROCK=1"))
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
    fun `only the agent's own environment variables go into the bundle, and credentials inside URLs are removed`() {
        // 桌面把整份 System.getenv() 交进来：数据库地址里的口令、Webhook 地址、本机用户名都不该进一份要发给别人的包
        val env = mapOf(
            "ANTHROPIC_BASE_URL" to "https://relay-user:Relay-S3cretValue@relay.example.com/v1",
            "DATABASE_URL" to "postgres://admin:Pg-S3cretValue@db:5432/app",
            "SLACK_WEBHOOK_URL" to "https://hooks.slack.com/services/T000/B000/WebhookS3cretValue",
            "USERNAME" to "alice-on-this-machine",
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS" to "32000",
        )
        val all = entries(FeedbackBundle.build(FeedbackDraft("t", "b"), null, "v", env, emptyMap())).values.joinToString("\n")
        for (leak in listOf("Relay-S3cretValue", "Pg-S3cretValue", "WebhookS3cretValue", "alice-on-this-machine")) assertFalse(leak + " in " + all, all.contains(leak))
        assertTrue(all, all.contains("CLAUDE_CODE_MAX_OUTPUT_TOKENS=32000"))
        assertTrue(all, all.contains("ANTHROPIC_BASE_URL=https://[已去除]@relay.example.com/v1"))
    }

    @Test
    fun `common credential shapes are removed from text even without a list of secrets (the phone has none)`() {
        // 手机端没有凭证名单（env 与 settings 都是空表），全靠形状：常见几家的 key、JWT、私钥块、认证头、口令赋值、带口令的地址
        val samples = listOf(
            "AKIAABCDEFGHIJKLMNOP",
            "AIzaSyA1234567890abcdefghijklmnopqrstuv",
            "xoxb-1234567890-abcdefghijkl",
            "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz",
            "glpat-abcdefghij0123456789",
            "hf_abcdefghijklmnopqrstuvwxyz0123456789",
            "sk_live_abcdefghijklmnop0123",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----",
            "Authorization: Basic dXNlcjpodW50ZXIyLXNlY3JldA==",
            "password: hunter2-secret",
            "MY_SERVICE_TOKEN=ZZ-plain-token-ZZ",
            "https://bob:Url-S3cret@example.com/x",
        )
        val body = samples.joinToString("\n")
        val all = entries(FeedbackBundle.build(FeedbackDraft("t", body), body, "v", emptyMap(), emptyMap())).values.joinToString("\n")
        val markers = listOf(
            "ABCDEFGHIJKLMNOP", "SyA1234567890", "1234567890-abcdefghijkl", "11ABCDEFG", "abcdefghij0123456789",
            "abcdefghijklmnopqrstuvwxyz0123456789", "abcdefghijklmnop0123", "dozjgNryP4J3", "b3BlbnNzaC1rZXktdjEAAAAA",
            "dXNlcjpodW50ZXIyLXNlY3JldA", "hunter2-secret", "ZZ-plain-token-ZZ", "Url-S3cret",
        )
        for (m in markers) assertFalse(m + " in " + all, all.contains(m))
    }

    @Test
    fun `the session log goes through the same scrub as the draft, and its fake keys never reach the zip`() {
        // 日志是最容易带凭证的一份：模型读过的文件、工具输出都在里面。环境里的值（不带形状）与假 key 都不能进 session-log.txt
        val env = mapOf("MIN_INTERNAL_PASSWORD" to "plainpw-LOGONLY9", "ANTHROPIC_API_KEY" to fakeKey)
        val log = "{\"text\":\"cat ~/.secrets 得到 plainpw-LOGONLY9\"}\n{\"text\":\"用了 $fakeKey\"}\n"
        val files = entries(FeedbackBundle.build(FeedbackDraft("t", "b"), log, "v", env, emptyMap()))
        val session = files.getValue("session-log.txt")
        assertFalse(session, session.contains("plainpw-LOGONLY9"))
        assertFalse(session, session.contains("FAKEKEY"))
        assertTrue(session, session.contains("cat ~/.secrets"))
        assertFalse(files.values.joinToString("\n"), files.values.any { it.contains("FAKEKEY") })
    }

    @Test
    fun `secrets the host knows are removed verbatim even when they have no recognisable shape`() {
        // 手机端把设置里存的 key、令牌作为「已知秘密」交进来：这些没有 sk- 之类的形状，只能靠逐字替换去掉
        val known = setOf("plain-provider-key-9d8c7b6a", "search-9f8e7d6c5b", "mcp-access-4a3b2c1d")
        val draft = FeedbackDraft("t", "我贴了 plain-provider-key-9d8c7b6a 与 search-9f8e7d6c5b")
        val log = "{\"text\":\"mcp-access-4a3b2c1d\"}\n"
        val files = entries(FeedbackBundle.build(draft, log, "v", emptyMap(), emptyMap(), knownSecrets = known))
        val all = files.values.joinToString("\n")
        for (s in known) assertFalse(s + " in " + all, all.contains(s))
        assertTrue(all, all.contains("我贴了"))
    }

    @Test
    fun `a known secret is also removed from the log in its JSON-escaped form`() {
        // 会话日志每行是 JSON：含引号 / 反斜杠的秘密写进去时是转义过的，逐字匹配对不上，所以转义后的写法也要去掉
        val secret = "pw\"quote\\slash-VALUE99"
        val escaped = "pw\\\"quote\\\\slash-VALUE99"
        val log = "{\"text\":\"$escaped\"}\n"
        val session = entries(FeedbackBundle.build(FeedbackDraft("t", "b"), log, "v", emptyMap(), emptyMap(), knownSecrets = setOf(secret))).getValue("session-log.txt")
        assertFalse(session, session.contains("VALUE99"))
    }

    @Test
    fun `redact is the same scrub the export uses, so a draft can be cleaned when it is written`() {
        val out = FeedbackBundle.redact("key plain-provider-key-9d8c7b6a and sk-ant-api03-ZZZZZZZZZZZZ", setOf("plain-provider-key-9d8c7b6a"))
        assertFalse(out, out.contains("plain-provider-key") || out.contains("ZZZZZZ"))
        // 太短的值不当凭证：否则正文里所有的 1 都会被抹掉
        assertEquals("a 1 b", FeedbackBundle.redact("a 1 b", setOf("1")))
        assertEquals(out, FeedbackBundle.redact(out, setOf("plain-provider-key-9d8c7b6a")))
    }

    @Test
    fun `the leak scan finds a secret that survived, so a bad export cannot be written`() {
        assertTrue(FeedbackBundle.leaks(mapOf("a.txt" to "xx $fakeKey yy"), setOf(fakeKey)))
        assertFalse(FeedbackBundle.leaks(mapOf("a.txt" to "clean"), setOf(fakeKey)))
    }
}
