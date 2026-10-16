package dev.min.code.core.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 情感特供的记忆：存在 App 私有目录（不在 rootfs、不在任何会话里），所有引擎、所有会话共用一份。
 * 写入只有两条路：后台提取（[CompanionKeeper]，独立于会话的一次模型调用）和用户在设置页手改。
 * 会话里的模型只读到渲染出来的只读副本（[renderCompanionBlock]），从不直接写。
 */

@Serializable
enum class CompanionKind {
    /** 情绪与支持：压力来源、什么话有用、什么话别说、在意的事 */
    @SerialName("feeling") FEELING,

    /** 生活细节：家人朋友宠物、作息、重要日子、喜好、正在经历的事 */
    @SerialName("life") LIFE,
}

@Serializable
data class CompanionNote(
    val id: String,
    val kind: CompanionKind,
    val text: String,
    /** 毫秒时间戳，提取或手改时刷新 */
    val updatedAt: Long,
)

@Serializable
data class CompanionMemory(
    val notes: List<CompanionNote> = emptyList(),
    /** 下一条的序号；id 是 `n<序号>`，删了不复用，免得模型拿旧 id 改到别的条目上 */
    val nextId: Int = 1,
)

/** 模型提取结果：增、改、删。由 [parseCompanionOps] 从模型回复里解析，[applyCompanionOps] 合并进记忆。 */
data class CompanionOps(
    val add: List<Pair<CompanionKind, String>> = emptyList(),
    val update: Map<String, String> = emptyMap(),
    val remove: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
}

object CompanionLimits {
    /**
     * 条数上限。超了丢最久没更新的。100 条 × 约 60 字 ≈ 6000 字，正好装进 [BLOCK_CHARS]，
     * 再多注入时也要截断，存着没意义。
     */
    const val MAX_NOTES = 100

    /** 单条字数上限：一条记一件事，太长说明模型把对话整段抄进来了 */
    const val MAX_NOTE_CHARS = 200

    /**
     * 注入块里记忆正文的字数上限。Min 内核对单个记忆文件限 20k 字（`MemoryLimits`），
     * 用户自己的 CLAUDE.md 和身份卡也在同一个文件里，给它们留一半多。
     */
    const val BLOCK_CHARS = 8_000
}

/** 合并一次提取结果。未知 id 的改 / 删静默忽略（模型偶尔会编 id），空白、重复的新增跳过。 */
fun applyCompanionOps(memory: CompanionMemory, ops: CompanionOps, now: Long): CompanionMemory {
    if (ops.isEmpty) return memory
    var nextId = memory.nextId
    val kept = memory.notes
        .filterNot { it.id in ops.remove }
        .map { note ->
            val text = ops.update[note.id]?.let(::cleanNoteText)
            if (text.isNullOrEmpty() || text == note.text) note else note.copy(text = text, updatedAt = now)
        }
    val seen = kept.mapTo(HashSet()) { normalized(it.text) }
    val added = ops.add.mapNotNull { (kind, raw) ->
        val text = cleanNoteText(raw)
        if (text.isEmpty() || !seen.add(normalized(text))) null
        else CompanionNote("n${nextId++}", kind, text, now)
    }
    val all = kept + added
    val trimmed = if (all.size <= CompanionLimits.MAX_NOTES) all
    else all.sortedByDescending { it.updatedAt }.take(CompanionLimits.MAX_NOTES).let { keep ->
        val ids = keep.mapTo(HashSet()) { it.id }
        all.filter { it.id in ids }
    }
    return CompanionMemory(trimmed, nextId)
}

internal fun cleanNoteText(raw: String): String =
    raw.replace(Regex("\\s+"), " ").trim().take(CompanionLimits.MAX_NOTE_CHARS)

private fun normalized(text: String) = text.lowercase().replace(Regex("[\\s\\p{Punct}，。！？、；：]"), "")
