package dev.min.agent.cli

import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.TurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/fork 提示词` 发给复制出来的那个会话的第一句话（CC：Pass a prompt and the copy starts working on it immediately）。
 * [isolate] 为真时追加一句「先建自己的 worktree」：CC 对分身的口径是「除非它就地改（已在 worktree 里、不是 git 仓库），
 * 否则让它在改代码之前建自己的 worktree」——原会话还在改同一个目录，两边改同一份文件会互相踩。
 */
internal fun forkPrompt(prompt: String, isolate: Boolean): String =
    if (!isolate) prompt
    else prompt + "\n\n（你是从另一个会话分出来的副本，原会话还在改同一个工作目录：改代码之前先用 EnterWorktree 建一个你自己的 worktree，免得两边改同一份文件。）"

/**
 * `/subtask <任务>`（CC：派一个继承完整对话的分身在后台做，做完结果回到本会话）。Min 的分身（subagent_type=fork）只能由
 * 回合里的 Agent 工具调用派生——它要从发起调用的那条助手消息切继承的对话——所以这里请本会话的模型去派，而不是宿主越过回合直接建：
 * 代价是多一个很短的回合，好处是分身的前缀、记账、审批都走原来那条已经测过的路。
 */
internal fun subtaskPrompt(task: String): String =
    "用户用 /subtask 派了一个侧任务。请立刻调用 Agent 工具把它交给分身：subagent_type 写 \"fork\"（继承当前完整对话），" +
        "run_in_background 写 true，prompt 写下面的任务。不要自己做这个任务；派出之后只用一句话告诉用户已经派出，然后等用户的下一句话——" +
        "分身做完会把结果报告给你，到时再转述。\n\n任务：$task"

/** [cwd] 是不是在一个 git 检出里（往上找 `.git`，目录或 worktree 里的文件都算） */
internal fun inGitCheckout(cwd: Path): Boolean {
    var dir: Path? = cwd.toAbsolutePath()
    while (dir != null) {
        if (Files.exists(dir.resolve(".git"))) return true
        dir = dir.parent
    }
    return false
}

/**
 * 后台分身的旁白：它在本进程里跑，没有界面接它——审批照 `--approve` 答（yes / no），要人答（ask）就打一行提示让用户 `/resume` 过去；
 * 回合结束时打一行结果。不接管 stdin：用户那边的输入不能被分身抢走。
 */
internal class ForkWatch(private val approve: ApprovalAnswer, private val out: PrintStream) {
    fun watch(scope: CoroutineScope, copy: SessionRuntime): Job = scope.launch {
        val answered = mutableSetOf<String>()
        var running = false
        copy.state.collect { s ->
            val turn = s.turn
            if (turn != null) running = true
            turn?.calls?.values.orEmpty().forEach { call ->
                val phase = call.phase as? CallPhase.AwaitingApproval ?: return@forEach
                if (!answered.add(phase.approvalId.value)) return@forEach
                when (approve) {
                    ApprovalAnswer.Yes -> copy.answerApproval(phase.approvalId, ApprovalChoice.Once)
                    ApprovalAnswer.No -> copy.answerApproval(phase.approvalId, ApprovalChoice.Deny("非交互模式下没有人审批，已按 --approve no 拒绝", fromHost = true))
                    ApprovalAnswer.Ask -> {
                        out.println("\n分身 ${copy.sessionId.value} 在等你批准 ${call.tool}：/resume ${copy.sessionId.value} 切过去处理")
                        out.flush()
                    }
                }
            }
            if (turn == null && running) {
                running = false
                val outcome = s.lastTurn?.outcome
                val text = when (outcome) {
                    is TurnOutcome.Failed -> "失败：${outcome.message}"
                    is TurnOutcome.Interrupted -> "被中断"
                    else -> (s.history.lastOrNull() as? HistoryEntry.Assistant)?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }
                        ?.replace(Regex("\\s+"), " ")?.trim()?.take(100).orEmpty()
                }
                out.println("\n分身 ${copy.sessionId.value} 做完了：$text（/resume ${copy.sessionId.value} 切过去看）")
                out.flush()
            }
        }
    }
}
