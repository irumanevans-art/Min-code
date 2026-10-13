package dev.min.code.core.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 升级迁移与「设置流」读侧的特征测试：两件事都藏在 [SettingsStore] 里、没有设备就测不到，
 * 拆它们之前先把现状钉住。
 */
class SettingsStoreMigrationTest {
    private lateinit var fx: SettingsStoreFixture

    @Before
    fun setUp() {
        fx = SettingsStoreFixture()
    }

    private suspend fun markerSet(): Boolean =
        fx.dataStore.data.first()[StoreKeys.INSECURE_ACK_MARKER] == true

    // -----------------------------------------------------------------------
    // 1.1.9 及更早的单 token 格式
    // -----------------------------------------------------------------------

    @Test
    fun `a plaintext legacy token becomes one acknowledged profile and the old keys are deleted`() = runBlocking<Unit> {
        fx.seed {
            it[StoreKeys.LEGACY_TOKEN] = "sk-old"
            it[StoreKeys.LEGACY_BASE_URL] = "http://1.2.3.4:8080"
        }

        val s = fx.store.current()

        val p = s.profiles.single()
        assertEquals("sk-old", p.token)
        assertEquals("http://1.2.3.4:8080", p.baseUrl)
        // 老地址是「既有配置」，明文风险直接记成已确认
        assertTrue(p.insecureAck)
        assertEquals(p.id, s.activeProfileId)
        // 迁完删老键：留着的话用户删光配置后老 token 会诈尸
        assertNull(fx.raw(StoreKeys.LEGACY_TOKEN))
        assertNull(fx.raw(StoreKeys.LEGACY_BASE_URL))
        assertTrue(fx.raw(StoreKeys.CLAUDE_TABLE)!!.startsWith(CIPHER_PREFIX))
    }

    @Test
    fun `an encrypted legacy token migrates and a missing base url means the official one`() = runBlocking<Unit> {
        fx.seed { it[StoreKeys.LEGACY_TOKEN] = fx.cipher.encrypt("sk-old") }

        val p = fx.store.current().profiles.single()

        assertEquals("sk-old", p.token)
        assertEquals(AppSettings.DEFAULT_BASE_URL, p.baseUrl)
    }

    /** 解不开的老 token 当空处理但**不删**：密钥回来之后这条还能迁，删掉就没了 */
    @Test
    fun `a legacy token that cannot be decrypted is kept for later and migrates once the key returns`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt("sk-old")
        fx.seed { it[StoreKeys.LEGACY_TOKEN] = raw }
        fx.cipher.keystoreLost = true

        val lost = fx.store.current()
        assertEquals(emptyList<ApiProfile>(), lost.profiles)
        assertEquals(raw, fx.raw(StoreKeys.LEGACY_TOKEN))
        assertNull(fx.raw(StoreKeys.CLAUDE_TABLE))

        fx.cipher.keystoreLost = false
        assertEquals("sk-old", fx.store.current().profiles.single().token)
        assertNull(fx.raw(StoreKeys.LEGACY_TOKEN))
    }

    @Test
    fun `a legacy token never replaces an existing profile table`() = runBlocking<Unit> {
        val id = fx.store.addProfile("keep", "sk-keep", "https://keep.example")
        fx.seed { it[StoreKeys.LEGACY_TOKEN] = "sk-old" }

        val s = fx.store.current()

        assertEquals(listOf(id), s.profiles.map { it.id })
        assertEquals("sk-keep", s.profiles.single().token)
    }

    /** 迁移是在 current() 里写的，UI 可能先订阅到流：流读侧自己兜一条，id 固定为 legacy、不写盘 */
    @Test
    fun `the settings flow shows a legacy token before any migration has written`() = runBlocking<Unit> {
        fx.seed { it[StoreKeys.LEGACY_TOKEN] = "sk-old" }

        val p = fx.settings().profiles.single()

        assertEquals("legacy", p.id)
        assertEquals("sk-old", p.token)
        assertTrue(p.insecureAck)
        assertEquals("sk-old", fx.raw(StoreKeys.LEGACY_TOKEN))
        assertNull(fx.raw(StoreKeys.CLAUDE_TABLE))
    }

    // -----------------------------------------------------------------------
    // 明文风险确认的一次性迁移
    // -----------------------------------------------------------------------

    private suspend fun seedProfileWithoutAck() {
        val json = encodeProfilesJson(listOf(ApiProfile(id = "a", token = "sk", baseUrl = "http://1.2.3.4:80")))
        fx.seed { it[StoreKeys.CLAUDE_TABLE] = fx.cipher.encrypt(json) }
    }

    @Test
    fun `profiles that predate the marker are all acknowledged and the marker is written`() = runBlocking<Unit> {
        seedProfileWithoutAck()

        val p = fx.store.current().profiles.single()

        assertTrue(p.insecureAck)
        assertFalse(p.needsInsecureConfirm)
        assertTrue(markerSet())
    }

    @Test
    fun `the settings flow already treats unmarked profiles as acknowledged without writing`() = runBlocking<Unit> {
        seedProfileWithoutAck()
        val raw = fx.raw(StoreKeys.CLAUDE_TABLE)

        assertTrue(fx.settings().profiles.single().insecureAck)

        assertEquals(raw, fx.raw(StoreKeys.CLAUDE_TABLE))
        assertFalse(markerSet())
    }

    /** 全新安装也要落标记，否则第一条新配置会被当成老配置放行 */
    @Test
    fun `a fresh install writes the marker so the first new plaintext profile still asks`() = runBlocking<Unit> {
        fx.store.current()
        assertTrue(markerSet())

        fx.store.addProfile("new", "sk-new", "http://1.2.3.4:80")

        val p = fx.settings().profiles.single()
        assertFalse(p.insecureAck)
        assertTrue(p.needsInsecureConfirm)
    }

    @Test
    fun `after the marker is set later profiles keep the value they were saved with`() = runBlocking<Unit> {
        seedProfileWithoutAck()
        fx.store.current()
        fx.store.addProfile("later", "sk-later", "http://5.6.7.8:80")

        fx.store.current()

        val later = fx.settings().profiles.first { it.label == "later" }
        assertFalse(later.insecureAck)
    }

    /** 解不开的表读成空：迁移不能拿空表去「确认」后整表写回，那会抹掉原密文 */
    @Test
    fun `the ack migration does not rewrite a table it cannot read`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt("{broken")
        fx.seed { it[StoreKeys.CLAUDE_TABLE] = raw }

        fx.store.current()

        assertEquals(raw, fx.raw(StoreKeys.CLAUDE_TABLE))
        assertTrue(markerSet())
    }

    // -----------------------------------------------------------------------
    // 设置流的枚举读取：存了不认识的值就回落默认，不能让整个流炸掉
    // -----------------------------------------------------------------------

    @Test
    fun `an empty store reads as the documented defaults`() = runBlocking<Unit> {
        val s = fx.settings()

        assertEquals(ClaudeAuthMode.PROVIDER, s.claudeAuth)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(AppLanguage.SYSTEM, s.appLanguage)
        assertEquals(SkinStyle.SEA, s.skin)
    }

    @Test
    fun `stored enum names are read back`() = runBlocking<Unit> {
        fx.store.setClaudeAuth(ClaudeAuthMode.SUBSCRIPTION)
        fx.store.setThemeMode(ThemeMode.DARK)
        fx.store.setAppLanguage(AppLanguage.ZH)
        fx.store.setSkin(SkinStyle.ANTHROPIC)

        val s = fx.settings()

        assertEquals(ClaudeAuthMode.SUBSCRIPTION, s.claudeAuth)
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(AppLanguage.ZH, s.appLanguage)
        assertEquals(SkinStyle.ANTHROPIC, s.skin)
    }

    /** 降级后读到新版本写的枚举名 / 手改坏了的值：回落默认，其余设置照常读 */
    @Test
    fun `an unknown stored enum value falls back to its default`() = runBlocking<Unit> {
        fx.store.setUseNpmMirror(true)
        fx.seed {
            it[stringPreferencesKey("claude_auth")] = "FROM_THE_FUTURE"
            it[stringPreferencesKey("theme")] = "sepia"
            it[stringPreferencesKey("app_language")] = ""
            it[stringPreferencesKey("skin")] = "theme_9"
        }

        val s = fx.settings()

        assertEquals(ClaudeAuthMode.PROVIDER, s.claudeAuth)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(AppLanguage.SYSTEM, s.appLanguage)
        assertEquals(SkinStyle.SEA, s.skin)
        assertTrue(s.useNpmMirror)
    }
}
