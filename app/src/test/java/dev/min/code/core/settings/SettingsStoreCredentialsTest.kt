package dev.min.code.core.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 凭据表的写入护栏，对着真 [SettingsStore] + 内存 DataStore + 假加密器走一遍。
 *
 * 护栏要防的事故：密文打不开（Keystore 钥匙没了）或解开了读不懂（schema 变了 / 降级 / 手改）
 * → 读侧给空表（设置页不炸）→ 界面显示「还没配过」→ 用户随手新增一条 → 写侧照着空表整表覆盖
 * → 原来那串也许还能救回来的密文永远没了。`ApiProfileTest` 钉的是判定函数，这里钉的是
 * 「判定真的挡在每一个写入口前面」。
 */
class SettingsStoreCredentialsTest {
    private lateinit var fx: SettingsStoreFixture

    @Before
    fun setUp() {
        fx = SettingsStoreFixture()
    }

    private val garbage = "{this is not a profile list"

    /** 一张加密表的名字，以及每一个会改它的公开入口（调用时表里都没有这些 id，也一样该被拒） */
    private class Table(val name: String, val writes: List<Pair<String, suspend SettingsStore.() -> Unit>>)

    private fun w(name: String, write: suspend SettingsStore.() -> Unit) = name to write

    private val claude = Table(
        "claude",
        listOf(
            w("addProfile(strings)") { addProfile("n", "sk-n", "https://n.example") },
            w("addProfile(entry)") { addProfile(ApiProfile(id = "x", token = "sk-x")) },
            w("replaceProfile") { replaceProfile(ApiProfile(id = "a", token = "sk-a")) },
            w("updateProfile") { updateProfile("a", "l", "sk-a", "https://a.example") },
            w("deleteProfile") { deleteProfile("a") },
            w("setActiveProfile") { setActiveProfile("a", acknowledgeInsecure = true) },
            w("moveProfile") { moveProfile("a", 1) },
            w("reorderProfiles") { reorderProfiles(listOf("a")) },
            w("duplicateProfile") { duplicateProfile("a") },
            w("importProfiles") { importProfiles(listOf(ApiProfile(id = "i", token = "sk-i")), emptyList()) },
        ),
    )

    private val codex = Table(
        "codex",
        listOf(
            w("addCodexProfile") { addCodexProfile(CodexProfile(id = "x", apiKey = "k")) },
            w("updateCodexProfile") { updateCodexProfile(CodexProfile(id = "a", apiKey = "k")) },
            w("deleteCodexProfile") { deleteCodexProfile("a") },
            w("moveCodexProfile") { moveCodexProfile("a", 1) },
            w("reorderCodexProfiles") { reorderCodexProfiles(listOf("a")) },
            w("setCodexProfile") { setCodexProfile(CodexProfile(id = "a", apiKey = "k")) },
            w("setActiveCodexProfile") { setActiveCodexProfile("a", acknowledgeInsecure = true) },
            w("duplicateCodexProfile") { duplicateCodexProfile("a") },
            w("importProfiles") { importProfiles(emptyList(), listOf(CodexProfile(id = "i", apiKey = "k"))) },
        ),
    )

    private val unified = Table(
        "unified",
        listOf(
            w("saveUnifiedProfile(new)") {
                saveUnifiedProfile(UnifiedProfile(id = "", token = "sk", claudeBaseUrl = "https://u.example"))
            },
            w("saveUnifiedProfile(existing)") {
                saveUnifiedProfile(UnifiedProfile(id = "u", token = "sk", codexBaseUrl = "https://u.example/v1"))
            },
            w("deleteUnifiedProfile(derived)") { deleteUnifiedProfile("u", deleteDerived = true) },
            w("deleteUnifiedProfile(keep)") { deleteUnifiedProfile("u", deleteDerived = false) },
        ),
    )

    /** 试遍 [table] 的每一个写入口，盘上必须一个字节都没变，界面侧必须知道「这是打不开不是没配过」 */
    private suspend fun assertEveryWriteIsRefused(table: Table) {
        val before = fx.snapshot()
        for ((name, write) in table.writes) {
            fx.store.write()
            assertEquals("${table.name}.$name 动了盘", before, fx.snapshot())
        }
        assertTrue("${table.name} 打不开时界面该看到提示", fx.settings().credentialsUnreadable)
    }

    // -----------------------------------------------------------------------
    // 解开了但读不懂
    // -----------------------------------------------------------------------

    @Test
    fun `claude table that decrypts but does not parse is never overwritten`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt(garbage)
        fx.seed { it[StoreKeys.CLAUDE_TABLE] = raw; it[StoreKeys.CLAUDE_ACTIVE] = "kept" }

        assertEveryWriteIsRefused(claude)

        assertEquals(raw, fx.raw(StoreKeys.CLAUDE_TABLE))
        assertEquals("kept", fx.raw(StoreKeys.CLAUDE_ACTIVE))
        // 读侧为了设置页不炸给空表 —— 所以才需要上面那个提示
        assertEquals(emptyList<ApiProfile>(), fx.settings().profiles)
    }

    @Test
    fun `codex table that decrypts but does not parse is never overwritten`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt(garbage)
        fx.seed { it[StoreKeys.CODEX_TABLE] = raw; it[StoreKeys.CODEX_ACTIVE] = "kept" }

        assertEveryWriteIsRefused(codex)

        assertEquals(raw, fx.raw(StoreKeys.CODEX_TABLE))
        assertEquals("kept", fx.raw(StoreKeys.CODEX_ACTIVE))
        assertEquals(emptyList<CodexProfile>(), fx.settings().codexProfiles)
    }

    @Test
    fun `unified table that decrypts but does not parse is never overwritten`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt(garbage)
        fx.seed { it[StoreKeys.UNIFIED_TABLE] = raw }

        assertEveryWriteIsRefused(unified)

        assertEquals(raw, fx.raw(StoreKeys.UNIFIED_TABLE))
        // 统一表写不进去，它派生的两张表自然也不该冒出东西
        assertNull(fx.raw(StoreKeys.CLAUDE_TABLE))
        assertNull(fx.raw(StoreKeys.CODEX_TABLE))
    }

    /** 降级场景：新版本写了旧版不认识的枚举值，旧版读到的是「读不懂」而不是「空」 */
    @Test
    fun `codex table with an enum value from a newer version is protected`() = runBlocking<Unit> {
        val raw = fx.cipher.encrypt("""[{"id":"a","authMode":"FROM_THE_FUTURE"}]""")
        fx.seed { it[StoreKeys.CODEX_TABLE] = raw }

        assertEveryWriteIsRefused(codex)

        assertEquals(raw, fx.raw(StoreKeys.CODEX_TABLE))
    }

    /** 统一编辑要同时写三张表，任何一张读不懂都不能写，否则另外两张会被「半成品」覆盖 */
    @Test
    fun `an unreadable claude or codex table also blocks unified edits`() = runBlocking<Unit> {
        fx.seed { it[StoreKeys.CLAUDE_TABLE] = fx.cipher.encrypt(garbage) }
        assertEveryWriteIsRefused(unified)

        fx.seed {
            it.remove(StoreKeys.CLAUDE_TABLE)
            it[StoreKeys.CODEX_TABLE] = fx.cipher.encrypt(garbage)
        }
        assertEveryWriteIsRefused(unified)
    }

    // -----------------------------------------------------------------------
    // 钥匙没了
    // -----------------------------------------------------------------------

    @Test
    fun `a lost keystore blocks every table and the key coming back restores it`() = runBlocking<Unit> {
        // 三张表都先用健康的加密器写出真密文
        fx.store.saveUnifiedProfile(
            UnifiedProfile(id = "", label = "U", token = "sk-u", claudeBaseUrl = "https://u.example", codexBaseUrl = "https://u.example/v1"),
        )
        fx.store.addProfile("own", "sk-own", "https://own.example")
        fx.store.addCodexProfile(CodexProfile(id = "x", apiKey = "k", authMode = CodexAuthMode.RELAY))
        val healthy = fx.settings()
        assertEquals(2, healthy.profiles.size)
        assertEquals(2, healthy.codexProfiles.size)
        assertFalse(healthy.credentialsUnreadable)
        val before = fx.snapshot()

        fx.cipher.keystoreLost = true

        for (table in listOf(claude, codex, unified)) assertEveryWriteIsRefused(table)
        assertEquals(before, fx.snapshot())
        val lost = fx.settings()
        // 读成空表、但标出「打不开」—— 界面据此说「打不开」而不是「还没配过」
        assertEquals(emptyList<ApiProfile>(), lost.profiles)
        assertEquals(emptyList<CodexProfile>(), lost.codexProfiles)
        assertEquals(emptyList<UnifiedProfile>(), lost.unifiedProfiles)
        assertTrue(lost.credentialsUnreadable)

        // 钥匙回来（换回原设备 / 恢复 Keystore）：原配置原样读得出来，写入恢复
        fx.cipher.keystoreLost = false
        assertEquals(healthy.profiles, fx.settings().profiles)
        assertFalse(fx.settings().credentialsUnreadable)
        fx.store.addProfile("after", "sk-after", "https://after.example")
        assertEquals(3, fx.settings().profiles.size)
    }

    /** 没存过就是没存过：新用户的空盘不能被当成「打不开」拦住第一条配置 */
    @Test
    fun `an empty store is not unreadable and accepts the first write`() = runBlocking<Unit> {
        assertFalse(fx.settings().credentialsUnreadable)

        val id = fx.store.addProfile("first", "sk-first", "https://first.example")

        assertEquals(listOf(id), fx.settings().profiles.map { it.id })
    }

    // -----------------------------------------------------------------------
    // 用户明确放弃：discardUnreadableCredentials
    // -----------------------------------------------------------------------

    @Test
    fun `discard clears every unreadable table with its active key and writes work again`() = runBlocking<Unit> {
        fx.seed {
            it[StoreKeys.CLAUDE_TABLE] = fx.cipher.encrypt(garbage)
            it[StoreKeys.CLAUDE_ACTIVE] = "old-claude"
            it[StoreKeys.CODEX_TABLE] = fx.cipher.encrypt(garbage)
            it[StoreKeys.CODEX_ACTIVE] = "old-codex"
            it[StoreKeys.UNIFIED_TABLE] = fx.cipher.encrypt(garbage)
        }
        assertTrue(fx.settings().credentialsUnreadable)

        fx.store.discardUnreadableCredentials()

        for (key in listOf(
            StoreKeys.CLAUDE_TABLE, StoreKeys.CLAUDE_ACTIVE, StoreKeys.CODEX_TABLE,
            StoreKeys.CODEX_ACTIVE, StoreKeys.UNIFIED_TABLE,
        )) {
            assertNull("${key.name} 该被清掉", fx.raw(key))
        }
        assertFalse(fx.settings().credentialsUnreadable)

        val id = fx.store.addProfile("fresh", "sk-fresh", "https://fresh.example")
        val after = fx.settings()
        assertEquals(listOf(id), after.profiles.map { it.id })
        assertEquals(id, after.activeProfileId)
    }

    /**
     * 回归：统一表以前漏在「清除」之外 —— 两张旧表清了，统一表还是打不开，
     * 之后每次统一编辑都被静默拒写，而提示条已经不见了。
     */
    @Test
    fun `discard also clears an unreadable unified table so unified edits work again`() = runBlocking<Unit> {
        fx.seed { it[StoreKeys.UNIFIED_TABLE] = fx.cipher.encrypt(garbage) }

        fx.store.discardUnreadableCredentials()
        val id = fx.store.saveUnifiedProfile(UnifiedProfile(id = "", token = "sk", claudeBaseUrl = "https://u.example"))

        assertEquals(listOf(id), fx.settings().unifiedProfiles.map { it.id })
    }

    /** 清的是「打不开」的那几张，健康的表不陪葬 */
    @Test
    fun `discard leaves a readable table alone`() = runBlocking<Unit> {
        val codexId = fx.store.addCodexProfile(CodexProfile(id = "x", label = "keep", apiKey = "k"))
        fx.seed { it[StoreKeys.CLAUDE_TABLE] = fx.cipher.encrypt(garbage) }

        fx.store.discardUnreadableCredentials()

        assertNull(fx.raw(StoreKeys.CLAUDE_TABLE))
        assertEquals(listOf(codexId), fx.settings().codexProfiles.map { it.id })
        assertEquals(codexId, fx.settings().activeCodexProfileId)
    }

    @Test
    fun `discard on a healthy store changes nothing`() = runBlocking<Unit> {
        fx.store.addProfile("a", "sk-a", "https://a.example")
        fx.store.addCodexProfile(CodexProfile(id = "x", apiKey = "k"))
        val before = fx.snapshot()

        fx.store.discardUnreadableCredentials()

        assertEquals(before, fx.snapshot())
    }

    // -----------------------------------------------------------------------
    // 正常路径：增 / 改 / 删 / 切换
    // -----------------------------------------------------------------------

    @Test
    fun `claude profiles round trip through add update set active and delete`() = runBlocking<Unit> {
        val a = fx.store.addProfile("  A  ", "  sk-a  ", "https://a.example/v1/")
        val b = fx.store.addProfile("B", "sk-b", "https://b.example")

        var s = fx.settings()
        assertEquals(listOf(a, b), s.profiles.map { it.id })
        // 入库时去空白、剥 /v1；新增即切过去
        assertEquals(ApiProfile(id = a, label = "A", token = "sk-a", baseUrl = "https://a.example"), s.profiles[0])
        assertEquals(b, s.activeProfileId)
        // 盘上是密文，不是明文 JSON
        assertTrue(fx.raw(StoreKeys.CLAUDE_TABLE)!!.startsWith(CIPHER_PREFIX))

        fx.store.setActiveProfile(a)
        assertEquals(a, fx.settings().activeProfileId)

        // 改一条：位置不动，字段整条更新；地址改了 insecureAck 以传入值为准
        fx.store.updateProfile(a, "A2", "sk-a2", "http://1.2.3.4:80", insecureAck = true)
        s = fx.settings()
        assertEquals(listOf(a, b), s.profiles.map { it.id })
        assertEquals(ApiProfile(id = a, label = "A2", token = "sk-a2", baseUrl = "http://1.2.3.4:80", insecureAck = true), s.profiles[0])

        // 删掉当前生效的：active 落到剩下的第一条
        fx.store.deleteProfile(a)
        s = fx.settings()
        assertEquals(listOf(b), s.profiles.map { it.id })
        assertEquals(b, s.activeProfileId)

        // 删光：表键与 active 键一起删，不留一块空密文
        fx.store.deleteProfile(b)
        assertNull(fx.raw(StoreKeys.CLAUDE_TABLE))
        assertNull(fx.raw(StoreKeys.CLAUDE_ACTIVE))
        assertEquals(emptyList<ApiProfile>(), fx.settings().profiles)
    }

    @Test
    fun `claude ordering operations keep every entry`() = runBlocking<Unit> {
        val a = fx.store.addProfile("A", "sk-a", "https://a.example")
        val b = fx.store.addProfile("B", "sk-b", "https://b.example")
        val c = fx.store.addProfile("C", "sk-c", "https://c.example")

        fx.store.moveProfile(c, -1)
        assertEquals(listOf(a, c, b), fx.settings().profiles.map { it.id })

        // 没提到的留在末尾而不是被删掉
        fx.store.reorderProfiles(listOf(b))
        assertEquals(listOf(b, a, c), fx.settings().profiles.map { it.id })

        // 复制：插在原条目下面，名字加 copy，不继承统一条目绑定
        val copy = fx.store.duplicateProfile(b)!!
        assertEquals(listOf(b, copy, a, c), fx.settings().profiles.map { it.id })
        assertEquals("B copy", fx.settings().profiles[1].label)

        assertNull(fx.store.duplicateProfile("missing"))
    }

    @Test
    fun `adding without activating and importing never move the active profile`() = runBlocking<Unit> {
        val a = fx.store.addProfile("A", "sk-a", "https://a.example")

        val b = fx.store.addProfile(ApiProfile(id = "ignored", token = "sk-b", baseUrl = "https://b.example/v1"), activate = false)
        fx.store.importProfiles(listOf(ApiProfile(id = "from-file", token = "sk-i", baseUrl = "https://i.example")), emptyList())

        val s = fx.settings()
        assertEquals(a, s.activeProfileId)
        assertEquals(3, s.profiles.size)
        // id 现生成：文件里那份是别的设备的
        assertNotEquals("ignored", b)
        assertTrue(s.profiles.none { it.id == "from-file" })
        assertEquals("https://b.example", s.profiles.first { it.id == b }.baseUrl)
    }

    @Test
    fun `codex profiles round trip through add update move set active and delete`() = runBlocking<Unit> {
        val a = fx.store.addCodexProfile(
            CodexProfile(id = "ignored", label = "A", baseUrl = "  https://a.example/v1/  ", apiKey = "k-a", authMode = CodexAuthMode.RELAY),
        )
        val b = fx.store.addCodexProfile(CodexProfile(id = "ignored", label = "B", apiKey = "k-b"), activate = false)

        var s = fx.settings()
        assertEquals(listOf(a, b), s.codexProfiles.map { it.id })
        assertEquals(a, s.activeCodexProfileId)
        // Codex 侧保留 /v1，只去空白与末尾斜杠
        assertEquals("https://a.example/v1", s.codexProfiles[0].baseUrl)

        // 编辑不把条目挪到末尾
        fx.store.updateCodexProfile(s.codexProfiles[0].copy(label = "A2"))
        assertEquals(listOf("A2", "B"), fx.settings().codexProfiles.map { it.label })

        fx.store.moveCodexProfile(b, -1)
        assertEquals(listOf(b, a), fx.settings().codexProfiles.map { it.id })

        fx.store.setActiveCodexProfile(b)
        assertEquals(b, fx.settings().activeCodexProfileId)

        fx.store.deleteCodexProfile(b)
        s = fx.settings()
        assertEquals(listOf(a), s.codexProfiles.map { it.id })
        assertEquals(a, s.activeCodexProfileId)

        fx.store.deleteCodexProfile(a)
        assertNull(fx.raw(StoreKeys.CODEX_TABLE))
        assertNull(fx.raw(StoreKeys.CODEX_ACTIVE))
    }

    @Test
    fun `set codex profile updates in place or appends then activates`() = runBlocking<Unit> {
        val a = fx.store.addCodexProfile(CodexProfile(id = "x", label = "A", apiKey = "k"))
        fx.store.addCodexProfile(CodexProfile(id = "x", label = "B", apiKey = "k"))

        fx.store.setCodexProfile(CodexProfile(id = a, label = "A-saved", apiKey = "k2"))
        fx.store.setCodexProfile(CodexProfile(id = "brand-new", label = "N", apiKey = "k3"))

        val s = fx.settings()
        assertEquals(listOf("A-saved", "B", "N"), s.codexProfiles.map { it.label })
        assertEquals("brand-new", s.activeCodexProfileId)
    }

    @Test
    fun `unified profile derives both tables in one edit and deleting can keep or drop them`() = runBlocking<Unit> {
        val id = fx.store.saveUnifiedProfile(
            UnifiedProfile(id = "", label = "U", token = "sk-u", claudeBaseUrl = "https://u.example/v1", codexBaseUrl = "https://u.example/v1/"),
        )

        var s = fx.settings()
        assertEquals(listOf(id), s.unifiedProfiles.map { it.id })
        val derivedClaude = s.profiles.single()
        val derivedCodex = s.codexProfiles.single()
        assertEquals(id, derivedClaude.unifiedId)
        assertEquals("sk-u", derivedClaude.token)
        assertEquals("https://u.example", derivedClaude.baseUrl)
        assertEquals(id, derivedCodex.unifiedId)
        assertEquals("https://u.example/v1", derivedCodex.baseUrl)
        // 派生条目也要有选中项，否则刚建完统一供应商两边都「没配过」
        assertEquals(derivedClaude.id, s.activeProfileId)
        assertEquals(derivedCodex.id, s.activeCodexProfileId)

        // 再存同一个 id = 覆盖，不是再派生一份
        fx.store.saveUnifiedProfile(UnifiedProfile(id = id, label = "U", token = "sk-u2", claudeBaseUrl = "https://u.example", codexBaseUrl = "https://u.example/v1"))
        s = fx.settings()
        assertEquals(1, s.unifiedProfiles.size)
        assertEquals("sk-u2", s.profiles.single().token)
        assertEquals(derivedClaude.id, s.profiles.single().id)

        // 删统一条目但留派生条目：派生条目解绑
        fx.store.deleteUnifiedProfile(id, deleteDerived = false)
        s = fx.settings()
        assertEquals(emptyList<UnifiedProfile>(), s.unifiedProfiles)
        assertEquals("", s.profiles.single().unifiedId)
        assertEquals("", s.codexProfiles.single().unifiedId)
        assertNull(fx.raw(StoreKeys.UNIFIED_TABLE))
    }

    @Test
    fun `deleting a unified profile with its derived entries empties all three tables`() = runBlocking<Unit> {
        val id = fx.store.saveUnifiedProfile(
            UnifiedProfile(id = "", token = "sk-u", claudeBaseUrl = "https://u.example", codexBaseUrl = "https://u.example/v1"),
        )

        fx.store.deleteUnifiedProfile(id, deleteDerived = true)

        for (key in listOf(
            StoreKeys.UNIFIED_TABLE, StoreKeys.CLAUDE_TABLE, StoreKeys.CLAUDE_ACTIVE,
            StoreKeys.CODEX_TABLE, StoreKeys.CODEX_ACTIVE,
        )) {
            assertNull("${key.name} 该被删掉", fx.raw(key))
        }
    }
}
