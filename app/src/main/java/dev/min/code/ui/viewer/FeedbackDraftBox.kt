package dev.min.code.ui.viewer

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.tools.FeedbackBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 一条待用户决定的草稿。[id] 是它在磁盘上的文件名（不含 .json），改与丢弃都按它找，不按内容比较（两条内容相同的也是两条） */
data class PendingDraft(val id: String, val draft: FeedbackDraft)

/**
 * 诊断包草稿的队列。绑了目录（[attach]）时，每次增删改同步落盘：一条草稿一个 JSON 文件，重开 App 还在；
 * 没绑目录时只在内存里（启动之前与测试）。
 *
 * 写不进去时 [add] / [replace] 返回 false，列表不变——不能让界面显示「已保存」，重开却没了。
 * 草稿**写入时**就去凭证（[add] / [replace] 的 knownSecrets：App 设置里存的各家 key、搜索 key、遥测令牌、MCP 令牌逐字去掉，
 * 其余靠形状兜底，见 FeedbackBundle.redact），标题、正文、附带的会话日志都一样：磁盘上的草稿文件里不留凭证。导出时还会再去一遍并扫描成品。
 */
class FeedbackDraftBox {
    private val _drafts = MutableStateFlow<List<PendingDraft>>(emptyList())
    val drafts: StateFlow<List<PendingDraft>> = _drafts.asStateFlow()
    private var store: Store? = null

    /** 绑定目录，并把已经落盘的草稿读进来（App 启动时一次） */
    fun attach(dir: File) = synchronized(this) {
        val s = Store(dir)
        store = s
        _drafts.value = s.loadAll()
    }

    fun add(raw: FeedbackDraft, knownSecrets: Set<String> = emptySet()): Boolean = synchronized(this) {
        val draft = clean(raw, knownSecrets)
        val id = "%013d-%s".format(System.currentTimeMillis(), UUID.randomUUID())
        if (store?.save(id, draft) == false) return false
        _drafts.value = _drafts.value + PendingDraft(id, draft)
        true
    }

    /** 用户改了草稿：覆盖同一个文件。找不到这条就什么也不做，返回 false */
    fun replace(id: String, raw: FeedbackDraft, knownSecrets: Set<String> = emptySet()): Boolean = synchronized(this) {
        val new = clean(raw, knownSecrets)
        val i = _drafts.value.indexOfFirst { it.id == id }
        if (i < 0 || store?.save(id, new) == false) return false
        _drafts.value = _drafts.value.toMutableList().also { it[i] = PendingDraft(id, new) }
        true
    }

    /** 丢弃：先删文件，删不掉就留着（否则重开又会冒出来），返回 false */
    fun discard(id: String): Boolean = synchronized(this) {
        if (store?.delete(id) == false) return false
        _drafts.value = _drafts.value.filterNot { it.id == id }
        true
    }

    /** 标题、正文、日志各过一遍去凭证；`redact` 幂等，已经去过的再去不变 */
    private fun clean(d: FeedbackDraft, secrets: Set<String>) = FeedbackDraft(
        title = FeedbackBundle.redact(d.title, secrets),
        body = FeedbackBundle.redact(d.body, secrets),
        sessionLog = d.sessionLog?.let { FeedbackBundle.redact(it, secrets) },
    )

    /** 一条草稿一个 JSON 文件。文件名以毫秒时间戳开头，按名字排序就是放入的先后 */
    private class Store(private val dir: File) {
        @Serializable private data class Saved(val title: String, val body: String, val sessionLog: String? = null)

        fun save(id: String, draft: FeedbackDraft): Boolean = try {
            dir.mkdirs()
            val target = File(dir, "$id.json")
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(JSON.encodeToString(Saved.serializer(), Saved(draft.title, draft.body, draft.sessionLog)))
            // 先写临时文件再换名：写到一半被杀，旧文件仍完整
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            false
        }

        fun delete(id: String): Boolean {
            val f = File(dir, "$id.json")
            return !f.exists() || f.delete()
        }

        /** 坏文件（写坏了、被改过）跳过，不拖垮别的草稿 */
        fun loadAll(): List<PendingDraft> = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".json") }
            .sortedBy { it.name }
            .mapNotNull { f ->
                try {
                    val s = JSON.decodeFromString(Saved.serializer(), f.readText())
                    PendingDraft(f.name.removeSuffix(".json"), FeedbackDraft(s.title, s.body, s.sessionLog))
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
