package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一供应商动的是用户的 key 和地址，错一次就是「我明明改了、另一边还是旧的」，
 * 或者更糟：改过的东西被悄悄改回去。三条投影规则一条一条钉死。
 */
class ProviderUnifiedTest {
    private var seq = 0
    private fun newId(): String = "gen-${seq++}"

    private val unified = UnifiedProfile(
        id = "u1",
        label = "某某中转",
        token = "sk-new",
        claudeBaseUrl = "https://a.example.com",
        codexBaseUrl = "https://a.example.com/v1",
        note = "公司号",
    )

    @Test
    fun `derives one entry on each side when there is none yet`() {
        val (claude, codex) = projectUnified(listOf(unified), emptyList(), emptyList(), ::newId)
        assertEquals(1, claude.size)
        assertEquals(1, codex.size)
        assertEquals("u1", claude.single().unifiedId)
        assertEquals("sk-new", claude.single().token)
        assertEquals("https://a.example.com", claude.single().baseUrl)
        assertEquals("sk-new", codex.single().apiKey)
        assertEquals(CodexAuthMode.RELAY, codex.single().authMode)
        assertEquals("公司号", codex.single().note)
    }

    @Test
    fun `overwrites the derived entry but keeps its id and position`() {
        val existing = listOf(
            ApiProfile(id = "keep", label = "自己的一条"),
            ApiProfile(id = "derived", label = "旧名", token = "sk-old", baseUrl = "https://old", unifiedId = "u1"),
        )
        val (claude, _) = projectUnified(listOf(unified), existing, emptyList(), ::newId)
        assertEquals(listOf("keep", "derived"), claude.map { it.id })
        assertEquals("sk-new", claude[1].token)
        assertEquals("https://a.example.com", claude[1].baseUrl)
        // 自己的那条一个字段都不该被碰
        assertEquals(existing[0], claude[0])
    }

    /** 地址换了，上一次的明文放行不能跟着继承——那等于替用户放行了一个他没见过的地址 */
    @Test
    fun `moving to a new address clears the insecure acknowledgement`() {
        val derived = ApiProfile(id = "d", baseUrl = "http://old", insecureAck = true, unifiedId = "u1")
        val (claude, _) = projectUnified(
            listOf(unified.copy(claudeBaseUrl = "http://new")),
            listOf(derived),
            emptyList(),
            ::newId,
        )
        assertFalse(claude.single().insecureAck)

        val same = ApiProfile(id = "d", baseUrl = "http://same", insecureAck = true, unifiedId = "u1")
        val (kept, _) = projectUnified(
            listOf(unified.copy(claudeBaseUrl = "http://same")),
            listOf(same),
            emptyList(),
            ::newId,
        )
        assertTrue(kept.single().insecureAck)
    }

    /** 取消勾选 = 不再联动，不是把这条配置扔了 */
    @Test
    fun `unchecking a side unbinds instead of deleting`() {
        val derived = ApiProfile(id = "d", token = "sk-new", baseUrl = "https://a.example.com", unifiedId = "u1")
        val (claude, _) = projectUnified(
            listOf(unified.copy(claudeBaseUrl = "")),
            listOf(derived),
            emptyList(),
            ::newId,
        )
        assertEquals(1, claude.size)
        assertEquals("", claude.single().unifiedId)
        assertEquals("sk-new", claude.single().token)
    }

    /** 统一条目没了（在别处被删），派生条目同样只解绑 —— 删不删是删除那一刻问出来的 */
    @Test
    fun `orphan derived entries are unbound not dropped`() {
        val derived = ApiProfile(id = "d", unifiedId = "gone")
        val (claude, _) = projectUnified(emptyList(), listOf(derived), emptyList(), ::newId)
        assertEquals(listOf(""), claude.map { it.unifiedId })
    }

    @Test
    fun `one side only derives one side`() {
        val (claude, codex) = projectUnified(
            listOf(unified.copy(codexBaseUrl = "")),
            emptyList(),
            emptyList(),
            ::newId,
        )
        assertEquals(1, claude.size)
        assertTrue(codex.isEmpty())
    }

    @Test
    fun `projection is idempotent`() {
        val (claude1, codex1) = projectUnified(listOf(unified), emptyList(), emptyList(), ::newId)
        val (claude2, codex2) = projectUnified(listOf(unified), claude1, codex1, ::newId)
        assertEquals(claude1, claude2)
        assertEquals(codex1, codex2)
    }

    @Test
    fun `duplicate labels keep counting up`() {
        assertEquals("甲 copy", duplicateLabel("甲", emptySet()))
        assertEquals("甲 copy 2", duplicateLabel("甲", setOf("甲 copy")))
        assertEquals("甲 copy 3", duplicateLabel("甲", setOf("甲 copy", "甲 copy 2")))
    }

    @Test
    fun `search matches name address and note`() {
        assertTrue(matchesProviderQuery("", "任何", "东西"))
        assertTrue(matchesProviderQuery("公司", "某某", "https://a", "公司号 12 月到期"))
        assertTrue(matchesProviderQuery("A.EXAMPLE", "某某", "https://a.example.com", ""))
        assertFalse(matchesProviderQuery("别的", "某某", "https://a.example.com", "公司号"))
    }

    /** token 写进哪个环境变量键，是「测得通却用不了」那一类问题的根 */
    @Test
    fun `auth header decides the env key`() {
        assertEquals("ANTHROPIC_AUTH_TOKEN", ApiProfile(id = "a").tokenEnvKey)
        assertEquals("ANTHROPIC_API_KEY", ApiProfile(id = "a", authHeader = AuthHeader.API_KEY).tokenEnvKey)

        val env = managedSettingsEnv(
            ApiProfile(id = "a", token = "sk-1", baseUrl = "https://x", authHeader = AuthHeader.API_KEY),
            includeSecrets = true,
        )
        assertEquals("sk-1", env["ANTHROPIC_API_KEY"])
        assertNull(env["ANTHROPIC_AUTH_TOKEN"])
    }

    /** 两个键都是保留的：只占一个的话，选了 Bearer 的人还能从自定义 env 里塞第二把钥匙进来 */
    @Test
    fun `both credential keys are reserved`() {
        val env = sanitizeEnv(mapOf("ANTHROPIC_API_KEY" to "sk-x", "ANTHROPIC_AUTH_TOKEN" to "sk-y", "OK" to "1"))
        assertEquals(mapOf("OK" to "1"), env)
    }
}
