package dev.min.agent.cli

import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus

/*
 * 终端上的任务进度：清单变了就打印一行，像 Claude Code 在状态区显示清单那样让人不用翻转录也知道做到哪了。
 * 只报创建、状态变化与删除（改标题、挂依赖这类小改动不值得一行）；纯函数，界面只管打印。
 */

/** 清单从 [before] 变成 [after] 时要打印的行，按编号顺序；没有值得说的变化返回空列表 */
internal fun taskProgressLines(before: List<TaskRecord>, after: List<TaskRecord>): List<String> {
    val old = before.associateBy { it.id }
    val done = after.count { it.status == TaskStatus.Completed }
    val tally = "[$done/${after.size}]"
    val lines = mutableListOf<String>()
    for (t in after) {
        val was = old[t.id]
        when {
            was == null -> lines += "▪ 任务 #${t.id} 新建：${t.subject}  $tally"
            was.status != t.status -> lines += when (t.status) {
                TaskStatus.InProgress -> "▪ 任务 #${t.id} 进行中：${t.activeForm ?: t.subject}  $tally"
                TaskStatus.Completed -> "▪ 任务 #${t.id} 完成：${t.subject}  $tally"
                TaskStatus.Pending -> "▪ 任务 #${t.id} 退回待处理：${t.subject}  $tally"
            }
        }
    }
    val kept = after.map { it.id }.toSet()
    // 删除的排在最后：它们已经不在 after 里，没有顺序可言
    before.filter { it.id !in kept }.forEach { lines += "▪ 任务 #${it.id} 删除：${it.subject}  $tally" }
    return lines
}
