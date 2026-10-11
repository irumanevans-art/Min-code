package dev.min.agent.cli

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.tools.SendFeedbackTool
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** 草稿队列里的一行：[file] 是诊断包 zip，[atMs] 取自文件名里的排队时间，[title] 取自包里 draft.md 的标题行 */
internal data class FeedbackDraftRow(val file: Path, val atMs: Long, val title: String)

/**
 * 本机的反馈草稿队列（CC tools-reference「SendFeedback tool behavior」：队列里放 10 份、排第 11 份时删最旧的，
 * 留着不管 30 天过期）。SendFeedback 工具与 `/feedback <文字>` 都往这里排，每份是一个去过凭证的诊断包 zip——
 * Min 没有发送通道，所以这里只有「排、看、丢」，没有「发」：导出的 zip 由用户自己拿去发。
 * 时间取自 [clock]（测试注入），文件名 `draft-<毫秒>.zip`。
 */
internal class FeedbackDrafts(
    private val dir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keep: Int = 10,
    private val maxAgeDays: Int = 30,
) {
    /** 排进一份草稿（已经是成品 zip）。超出上限就删最旧的；返回写出的文件 */
    fun queue(zip: ByteArray): Path {
        Files.createDirectories(dir)
        var at = clock()
        var target = dir.resolve("draft-$at.zip")
        // 同一毫秒排了两份：往后挪一毫秒，文件名保持唯一、顺序保持排队顺序
        while (Files.exists(target)) { at += 1; target = dir.resolve("draft-$at.zip") }
        Files.write(target, zip)
        entries().drop(keep).forEach { Files.deleteIfExists(it.file) }
        return target
    }

    /** 队列，最新的在前。过期的（超过 [maxAgeDays] 天）顺手删掉 */
    fun list(): List<FeedbackDraftRow> {
        val cutoff = clock() - maxAgeDays * 24L * 3_600_000
        val all = entries()
        all.filter { it.atMs < cutoff }.forEach { Files.deleteIfExists(it.file) }
        return all.filter { it.atMs >= cutoff }
    }

    fun discard(row: FeedbackDraftRow) {
        Files.deleteIfExists(row.file)
    }

    /** draft.md 的全文（标题 + 正文） */
    fun read(row: FeedbackDraftRow): String =
        ZipFile(row.file.toFile()).use { zip -> zip.getEntry("draft.md")?.let { zip.getInputStream(it).readBytes().decodeToString() }.orEmpty() }

    private fun entries(): List<FeedbackDraftRow> {
        if (!Files.isDirectory(dir)) return emptyList()
        val rows = Files.list(dir).use { s -> s.toList() }.mapNotNull { f ->
            val at = NAME.matchEntire(f.fileName.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            FeedbackDraftRow(f, at, titleOf(f))
        }
        return rows.sortedByDescending { it.atMs }
    }

    private fun titleOf(file: Path): String =
        try {
            ZipFile(file.toFile()).use { zip ->
                zip.getEntry("draft.md")?.let { zip.getInputStream(it).readBytes().decodeToString().lineSequence().firstOrNull()?.removePrefix("# ")?.trim() }
            } ?: "（没有标题）"
        } catch (_: java.io.IOException) {
            "（打不开的草稿）"
        }

    private companion object {
        val NAME = Regex("draft-(\\d+)\\.zip")
    }
}

/**
 * `/feedback [文字]`（CC commands 页）：
 * - 无参 = 打开草稿队列（CC 在有 Claude 起草的草稿时的行为；Min 的 SendFeedback 草稿也在这里）；
 * - `show N` 看第 N 份、`discard N|all` 丢弃——Min 没有发送，所以没有 send；
 * - 其余文字 = 自己写一份报告，排进同一个队列（CC：带文字直接开反馈对话框）。
 * 「show / discard」只在后面跟着合法的序号时才是子命令，否则整句是报告（「show 的结果不对」不会被吞掉）。
 */
internal fun feedbackCommand(drafts: FeedbackDrafts, arg: String, build: (FeedbackDraft) -> ByteArray, out: PrintStream) {
    val text = arg.trim()
    val rows = drafts.list()
    val tokens = text.split(Regex("\\s+"))
    when {
        text.isEmpty() -> out.println(queueListing(rows))
        tokens.size == 2 && tokens[0] == "show" && tokens[1].toIntOrNull() != null -> {
            val n = tokens[1].toInt()
            val row = rows.getOrNull(n - 1)
            if (row == null) out.println("没有第 $n 份草稿。") else out.println(drafts.read(row).trimEnd())
        }
        tokens.size == 2 && tokens[0] == "discard" && (tokens[1] == "all" || tokens[1].toIntOrNull() != null) -> {
            if (tokens[1] == "all") {
                rows.forEach(drafts::discard)
                out.println("已丢弃 ${rows.size} 份草稿。")
            } else {
                val n = tokens[1].toInt()
                val row = rows.getOrNull(n - 1)
                if (row == null) out.println("没有第 $n 份草稿。") else {
                    drafts.discard(row)
                    out.println("已丢弃第 $n 份草稿：${row.title}")
                }
            }
        }
        else -> {
            val title = text.lineSequence().first().trim().take(SendFeedbackTool.MAX_TITLE)
            val body = text.take(SendFeedbackTool.MAX_BODY)
            val file = drafts.queue(build(FeedbackDraft(title, body)))
            out.println("已排进反馈草稿队列（未发送）：$title\n导出包在 ${file.toAbsolutePath()}，要反馈给 Anthropic 请自己拿去发；/feedback 看队列。")
        }
    }
}

private fun queueListing(rows: List<FeedbackDraftRow>): String =
    if (rows.isEmpty()) "反馈草稿队列是空的。/feedback <文字> 写一份；模型用 SendFeedback 起草的草稿也会出现在这里。"
    else buildString {
        append("反馈草稿队列（${rows.size} 份，最新的在前，全部未发送）：")
        rows.forEachIndexed { i, row -> append("\n  ${i + 1}. ${row.title}") }
        append("\n/feedback show <序号> 查看，/feedback discard <序号|all> 丢弃；Min 不替你发送，导出的 zip 在 ${rows.first().file.parent.toAbsolutePath()}。")
    }
