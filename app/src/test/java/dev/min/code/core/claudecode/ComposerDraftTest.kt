package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ComposerDraftTest {

    @Test
    fun `空白字且没有附件就是空`() {
        assertTrue(ComposerDraft().isEmpty)
        assertTrue(ComposerDraft(text = "   ").isEmpty)
        assertFalse(ComposerDraft(text = "hello").isEmpty)
        assertFalse(ComposerDraft(attachments = listOf(DraftAttachment("a", "/workspace/a"))).isEmpty)
        assertFalse(ComposerDraft(images = listOf(DraftImage("p", "image/jpeg", "QQ=="))).isEmpty)
    }

    @Test
    fun `没有顺延就恢复已有草稿`() {
        val existing = ComposerDraft(text = "还没发出去")
        val decision = decideComposerOpen(carry = false, existing = existing)
        assertEquals(ComposerOpen.Ok(existing), decision)
    }

    @Test
    fun `顺延到空会话就承接`() {
        val decision = decideComposerOpen(carry = true, existing = ComposerDraft.Empty)
        assertEquals(ComposerOpen.Ok(ComposerDraft.Empty), decision)
    }

    @Test
    fun `顺延到已有草稿的会话就冲突`() {
        val existing = ComposerDraft(text = "这边已经写了")
        assertEquals(ComposerOpen.Conflict, decideComposerOpen(carry = true, existing = existing))
    }

    @Test
    fun `索引来回编解码`() {
        val index = DraftIndex(
            lastActive = "abc",
            carry = true,
            drafts = mapOf(
                "abc" to StoredDraft(text = "hi", pasteSeq = 2, pastes = mapOf("1" to "long")),
            ),
        )
        val round = decodeDraftIndex(encodeDraftIndex(index))
        assertEquals("abc", round.lastActive)
        assertTrue(round.carry)
        assertEquals("hi", round.drafts["abc"]?.text)
        assertEquals("long", round.drafts["abc"]?.pastes?.get("1"))
    }

    @Test
    fun `坏 JSON 退回空索引`() {
        val empty = decodeDraftIndex("{not json")
        assertFalse(empty.carry)
        assertTrue(empty.drafts.isEmpty())
    }
}

class ComposerDraftStoreTest {

    private fun store(): Pair<ComposerDraftStore, File> {
        val dir = createTempDirectory("drafts").toFile()
        return ComposerDraftStore(dir) to dir
    }

    @Test
    fun `空草稿不占记录`() {
        val (store, _) = store()
        store.save("s1", ComposerDraft(text = "x"))
        store.save("s1", ComposerDraft.Empty)
        assertTrue(store.load("s1").isEmpty)
    }

    @Test
    fun `字和附件能活过一次重新打开`() {
        val (store, _) = store()
        val draft = ComposerDraft(
            text = "看这个文件",
            attachments = listOf(DraftAttachment("a.txt", "/workspace/a.txt")),
            pastes = mapOf("1" to "很长一段"),
            pasteSeq = 1,
        )
        store.save("s1", draft)
        val loaded = store.load("s1")
        assertEquals("看这个文件", loaded.text)
        assertEquals("/workspace/a.txt", loaded.attachments.single().path)
        assertEquals("很长一段", loaded.pasteMap[1])
        assertEquals(1, loaded.pasteSeq)
    }

    @Test
    fun `图片按字节落盘再读回`() {
        val (store, _) = store()
        val payload = "aGVsbG8=" // hello
        store.save("s1", ComposerDraft(images = listOf(DraftImage("拍照", "image/jpeg", payload))))
        val loaded = store.load("s1")
        assertEquals("拍照", loaded.images.single().name)
        assertEquals("image/jpeg", loaded.images.single().mediaType)
        assertEquals(payload, loaded.images.single().base64)
    }

    /**
     * 和生产一样：先 [ComposerDraftStore.beginOpen] 判定，打开成功之后才 [ComposerDraftStore.consumeCarry]。
     * 判定本身不消耗指针，避免并发满了把空框顺延吞掉。
     */
    private fun ComposerDraftStore.openLikeVm(id: String): ComposerOpen {
        val decision = beginOpen(id)
        if (decision is ComposerOpen.Ok) consumeCarry(id)
        return decision
    }

    @Test
    fun `退出时空框会让下一个已有草稿的会话冲突`() {
        val (store, _) = store()
        store.save("full", ComposerDraft(text = "旧草稿"))
        store.snapshotOnStop("empty", ComposerDraft.Empty)
        assertEquals(ComposerOpen.Conflict, store.openLikeVm("full"))
        // 冲突不消耗顺延：再开一个空的仍然承接
        val second = store.openLikeVm("other")
        assertEquals(ComposerOpen.Ok(ComposerDraft.Empty), second)
        // 顺延已经消耗，现在可以打开那个有草稿的
        val after = store.openLikeVm("full") as ComposerOpen.Ok
        assertEquals("旧草稿", after.draft.text)
    }

    @Test
    fun `退出时有内容就钉在该会话上`() {
        val (store, _) = store()
        store.snapshotOnStop("s1", ComposerDraft(text = "钉住"))
        val opened = store.openLikeVm("s1") as ComposerOpen.Ok
        assertEquals("钉住", opened.draft.text)
        // 不是顺延，打开别的会话互不影响
        val other = store.openLikeVm("s2") as ComposerOpen.Ok
        assertTrue(other.draft.isEmpty)
    }

    @Test
    fun `删会话把草稿一并忘掉`() {
        val (store, _) = store()
        store.save("s1", ComposerDraft(text = "gone"))
        store.forget("s1")
        assertTrue(store.load("s1").isEmpty)
    }

    @Test
    fun `进程进后台时按最近会话判定顺延`() {
        val (store, _) = store()
        store.save("full", ComposerDraft(text = "旧草稿"))
        store.rememberActive("empty")
        store.markProcessStopped()
        assertEquals(ComposerOpen.Conflict, store.openLikeVm("full"))
    }

    @Test
    fun `判定成功但不消耗时顺延指针还在`() {
        val (store, _) = store()
        store.snapshotOnStop("empty", ComposerDraft.Empty)
        assertEquals(ComposerOpen.Ok(ComposerDraft.Empty), store.beginOpen("other"))
        assertTrue(store.hasCarry())
        // 模拟注册表因并发上限拒绝打开：指针必须还能用
        assertEquals(ComposerOpen.Ok(ComposerDraft.Empty), store.beginOpen("another"))
        store.consumeCarry("another")
        assertFalse(store.hasCarry())
    }

    @Test
    fun `最近会话自己有草稿则进后台不顺延`() {
        val (store, _) = store()
        store.save("s1", ComposerDraft(text = "钉住"))
        store.rememberActive("s1")
        store.markProcessStopped()
        val opened = store.openLikeVm("s2") as ComposerOpen.Ok
        assertTrue(opened.draft.isEmpty)
        val same = store.openLikeVm("s1") as ComposerOpen.Ok
        assertEquals("钉住", same.draft.text)
    }
}
