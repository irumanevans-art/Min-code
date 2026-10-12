package dev.min.code.core.service

import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 的派生（B9：Codex 由固定 id 改成按会话 key 派生，与 CC / Min 同款）。
 * 钉两件事：各引擎各类通知的区间两两不重叠；同引擎不同会话（抽样）不撞、同一会话稳定。
 */
class NotificationIdsTest {

    private fun refs(engine: EngineKind, count: Int = 120) = (0 until count).map { SessionRef(engine, "sess-$it-${engine.name.lowercase()}") }

    private val span = 0x7FFFF + 1

    /** 六段区间（引擎 × 审批 / 完成）；再加上别处占用的固定段，一起验证互不相交 */
    private val ranges = listOf(
        "cc-interaction" to CC_INTERACTION_ID_BASE,
        "cc-done" to CC_DONE_ID_BASE,
        "min-interaction" to MIN_INTERACTION_ID_BASE,
        "min-done" to MIN_DONE_ID_BASE,
        "codex-interaction" to CODEX_INTERACTION_ID_BASE,
        "codex-done" to CODEX_DONE_ID_BASE,
        // SessionActionReceiver 的「操作已过期」轻提示 / 定时任务通知（9_000_000 起，同款 19 位）
        "stale-and-cron" to 9_000_000,
    )

    @Test
    fun `the id ranges of every engine and kind are pairwise disjoint`() {
        for (i in ranges.indices) for (j in i + 1 until ranges.size) {
            val (nameA, a) = ranges[i]
            val (nameB, b) = ranges[j]
            assertTrue("$nameA 与 $nameB 区间重叠", a + span <= b || b + span <= a)
        }
    }

    @Test
    fun `codex ids sit in their own ranges and not in the retired fixed ids`() {
        for (ref in refs(EngineKind.Codex)) {
            val approval = interactionNotificationId(ref)
            val done = sessionDoneNotificationId(ref)
            assertTrue(approval in CODEX_INTERACTION_ID_BASE until CODEX_INTERACTION_ID_BASE + span)
            assertTrue(done in CODEX_DONE_ID_BASE until CODEX_DONE_ID_BASE + span)
            assertNotEquals(5_000_001, approval)
            assertNotEquals(5_000_002, done)
        }
    }

    @Test
    fun `approval and done ids never collide across Codex, Min and CC for sampled keys`() {
        val all = EngineKind.entries.flatMap { refs(it) }
        val approvals = all.associateWith { interactionNotificationId(it) }
        val dones = all.associateWith { sessionDoneNotificationId(it) }
        // 审批 id 之间、完成 id 之间、审批与完成之间都不撞（同引擎同 key 的审批 / 完成各在各的段里）
        val ids = approvals.values + dones.values
        assertEquals("抽样 key 上有 id 撞车", ids.size, ids.toSet().size)
    }

    @Test
    fun `same key under different engines gets different ids`() {
        // storageKey 带引擎前缀（CC 沿用裸 id），同一个字符串在三个引擎下也各占各的区间
        val key = "0198f3c2-1111-7000-8000-000000000001"
        val ids = EngineKind.entries.map { interactionNotificationId(SessionRef(it, key)) }
        assertEquals(ids.size, ids.toSet().size)
        val dones = EngineKind.entries.map { sessionDoneNotificationId(SessionRef(it, key)) }
        assertEquals(dones.size, dones.toSet().size)
    }

    @Test
    fun `a session keeps its id and two codex threads do not share one`() {
        val a = SessionRef(EngineKind.Codex, "th-aaa")
        val b = SessionRef(EngineKind.Codex, "th-bbb")
        assertEquals(interactionNotificationId(a), interactionNotificationId(SessionRef(EngineKind.Codex, "th-aaa")))
        assertNotEquals(interactionNotificationId(a), interactionNotificationId(b))
        assertNotEquals(sessionDoneNotificationId(a), sessionDoneNotificationId(b))
    }

    /** 沿用的派生规则不许悄悄变：CC 按裸 id，Min / Codex 按带前缀的 storageKey（升级前后同一会话的通知才能互相覆盖 / 撤销） */
    @Test
    fun `cc ids derive from the bare id and min and codex ids from the storage key`() {
        val cc = SessionRef(EngineKind.ClaudeCode, "abc")
        val min = SessionRef(EngineKind.MinAgent, "s_1")
        val codex = SessionRef(EngineKind.Codex, "th-1")
        assertEquals(CC_INTERACTION_ID_BASE + (stableKeyInt("abc") and 0x7FFFF), interactionNotificationId(cc))
        assertEquals(CC_DONE_ID_BASE + (stableKeyInt("abc") and 0x7FFFF), sessionDoneNotificationId(cc))
        assertEquals(MIN_INTERACTION_ID_BASE + (stableKeyInt(min.storageKey) and 0x7FFFF), interactionNotificationId(min))
        assertEquals(MIN_DONE_ID_BASE + (stableKeyInt(min.storageKey) and 0x7FFFF), sessionDoneNotificationId(min))
        assertEquals(CODEX_INTERACTION_ID_BASE + (stableKeyInt(codex.storageKey) and 0x7FFFF), interactionNotificationId(codex))
        assertEquals(CODEX_DONE_ID_BASE + (stableKeyInt(codex.storageKey) and 0x7FFFF), sessionDoneNotificationId(codex))
    }
}
