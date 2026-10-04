package dev.min.agent.cli

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.RewindOutcome
import dev.min.agent.kernel.RewindPreview
import dev.min.agent.kernel.RewindScope
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.TextPart
import java.io.BufferedReader
import java.io.PrintStream

/** CC 的 `/rewind` 与它的两个别名 */
internal val REWIND_COMMANDS = setOf("/rewind", "/checkpoint", "/undo")

/**
 * 交互式回退（检查点与rewind设计 §4.1）：列出本会话能回到的消息，选一条、选范围，看清会动什么再确认。
 * 回退对话后把原消息打出来——终端没法回填输入框，复制重发即可。
 */
internal suspend fun rewindCommand(runtime: SessionRuntime, stdin: BufferedReader, out: PrintStream = System.err) {
    val points = runtime.state.value.rewindPoints
    if (points.isEmpty()) {
        out.println("还没有能回退的消息。")
        return
    }
    val history = runtime.state.value.history
    points.forEachIndexed { i, point ->
        val text = (history.firstOrNull { it.messageId == point.messageId } as? HistoryEntry.User)
            ?.parts?.filterIsInstance<TextPart>()?.joinToString(" ") { it.text }.orEmpty()
        out.println("  ${i + 1}. ${text.replace('\n', ' ').take(PREVIEW_CHARS)}")
    }
    out.print("回到第几条消息之前（回车取消）：")
    val index = stdin.readLine()?.trim()?.toIntOrNull()?.minus(1)
    if (index == null || index !in points.indices) return
    val preview = runtime.previewRewind(points[index].messageId) ?: return
    describe(preview, out)

    val options = buildList {
        if (preview.canRestoreCode) add("代码和对话" to RewindScope.Both)
        add("只回退对话" to RewindScope.Conversation)
        if (preview.canRestoreCode) add("只恢复代码" to RewindScope.Code)
    }
    options.forEachIndexed { i, (label, _) -> out.println("  ${i + 1}. $label") }
    out.print("选一项（回车取消）：")
    val choice = stdin.readLine()?.trim()?.toIntOrNull()?.minus(1)
    if (choice == null || choice !in options.indices) return

    when (val outcome = runtime.rewind(preview.messageId, options[choice].second)) {
        is RewindOutcome.Refused -> out.println("没有回退：${outcome.reason}")
        is RewindOutcome.Done -> {
            outcome.restored?.let { r ->
                out.println("恢复了 ${r.restored.size} 个文件，删除了 ${r.deleted.size} 个。")
                r.skipped.forEach { out.println("  跳过 ${it.path.value}：${it.reason}") }
                if (r.preRestore != null) out.println("（想撤销这次恢复：/rewind-undo ${r.restoreId}）")
            }
            if (outcome.rewound != null) {
                val text = outcome.prompt.filterIsInstance<TextPart>().joinToString("\n") { it.text }
                out.println("对话已回到这条消息之前。原消息：\n$text")
            }
        }
    }
}

/** `/rewind-undo <id>`：撤销一次恢复代码 */
internal suspend fun rewindUndoCommand(runtime: SessionRuntime, restoreId: String, out: PrintStream = System.err) {
    when (val outcome = runtime.undoRestore(restoreId)) {
        is RewindOutcome.Refused -> out.println("没有撤销：${outcome.reason}")
        is RewindOutcome.Done -> out.println("已撤销那次恢复，${outcome.restored?.restored?.size ?: 0} 个文件回到了恢复之前的样子。")
    }
}

private fun describe(preview: RewindPreview, out: PrintStream) {
    if (!preview.canRestoreCode) {
        out.println(if (preview.checkpoint == null) "这条消息没有检查点，只能回退对话。" else "这一点之后没有文件改动。")
    } else {
        val files = (preview.changes.map { it.path } + preview.editRestores.map { it.path }).distinct()
        out.println("恢复代码会动 ${files.size} 个文件：")
        files.take(LIST_MAX).forEach { out.println("  ${it.value}") }
        if (files.size > LIST_MAX) out.println("  …等 ${files.size} 个")
    }
    preview.checkpoint?.reason?.let { out.println("这个检查点没有整树快照（$it），命令改的文件撤不回。") }
    if (preview.unrestorable.isNotEmpty()) out.println("没有原文备份、撤不回：${preview.unrestorable.joinToString("、") { it.value }}")
    if (preview.external.isNotEmpty()) out.println("最近一轮之后被编辑工具以外改过（命令或你自己）：${preview.external.joinToString("、") { it.value }}")
    if (preview.runningChildren.isNotEmpty()) out.println("回退对话会停掉 ${preview.runningChildren.size} 个还在跑的子 agent。")
}

private const val PREVIEW_CHARS = 60
private const val LIST_MAX = 20
