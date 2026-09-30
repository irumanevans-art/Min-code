package dev.min.agent.tools

import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class BashInput(
    val command: String,
    val timeoutMs: Long,
    val description: String?,
    val background: Boolean,
)

/**
 * 执行 shell 命令（规格 §7）。
 *
 * traits 用默认（写、不可并发、Unknown）：命令能做任何事，不能假设只读、也不能假设重放无害——
 * 中断后内核靠 ToolEffectPending 里的句柄重新挂上，而不是重跑。
 */
object BashTool : Tool<BashInput> {
    override val name = "Bash"

    const val DEFAULT_TIMEOUT_MS = 120_000L
    const val MAX_TIMEOUT_MS = 600_000L

    /** 前台最多等这么久，还没跑完就转后台交出句柄（Codex 的做法）：模型不会被一条慢命令整个卡住 */
    const val FOREGROUND_BUDGET_MS = 10_000L

    private const val SUMMARY_CHARS = 80

    override val inputSchema: JsonObject = schemaObject(
        listOf("command"),
        "command" to prop("string", "要执行的 shell 命令"),
        "timeout" to prop("integer", "超时毫秒数，默认 $DEFAULT_TIMEOUT_MS，最大 $MAX_TIMEOUT_MS（更大的值按最大算）"),
        "description" to prop("string", "用一句话说明这条命令做什么（给用户看）"),
        "run_in_background" to prop("boolean", "true 则立刻转入后台运行，返回句柄与日志路径；适合开发服务器等长时间进程"),
    )

    override val traits = ToolTraits()

    override fun description(profile: HarnessProfile) =
        "在 shell 里执行一条命令，返回输出（stdout 与 stderr 合并）与退出码；退出码非 0 视为失败。" +
            // 模型给测试命令追加 echo 或 head/tail 会把失败伪装成退出码 0，工具本身已负责裁剪输出。
            "构建与测试请原样执行用户给出的命令，保留它的最终退出码；不要追加 echo 或用 head/tail 管道掩盖失败。工具会裁剪长输出，无需为截断输出改写命令。" +
            "每条命令都从会话目录开始执行，cd 与 export 不会保留到下一次调用——需要换目录就在同一条命令里写 cd x && ...。" +
            "默认超时 ${DEFAULT_TIMEOUT_MS / 1000} 秒，最大 ${MAX_TIMEOUT_MS / 1000} 秒。" +
            "命令前台运行超过 ${FOREGROUND_BUDGET_MS / 1000} 秒还没结束会自动转入后台，此时会返回句柄与日志路径，用 Read 看日志。" +
            "读写文件、搜索请优先用 Read / Write / Edit / Glob / Grep，不要用 cat / sed / grep / find 代替。"

    override fun parse(raw: JsonObject): ToolInput<BashInput> = parseArgs {
        val command = raw.reqString("command")
        if (command.isBlank()) throw BadArg("字段 `command` 不能为空。")
        // 超时超过上限是模型高估了需要，钳到上限继续跑，比拒绝让它重试便宜
        val timeout = (raw.optLong("timeout", min = 1) ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS)
        BashInput(
            command = command,
            timeoutMs = timeout,
            description = raw.optString("description")?.takeIf { it.isNotBlank() },
            background = raw.optBool("run_in_background") ?: false,
        )
    }

    override fun permissionAsk(input: BashInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        summary = (input.description ?: input.command).clip(SUMMARY_CHARS),
        commands = listOf(CommandAccess(raw = input.command)),
    )

    override fun run(input: BashInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { execute(input, ctx) }

    private suspend fun execute(input: BashInput, ctx: ToolContext): ToolResult {
        val options = ExecOptions(
            timeoutMs = input.timeoutMs,
            foregroundBudgetMs = if (input.background) 0 else FOREGROUND_BUDGET_MS,
            maxOutputChars = traits.resultBudgetChars,
        )
        return when (val r = ctx.exec.run(ShellCommand(input.command, ctx.cwd), options)) {
            is ExecResult.Exited -> {
                val text = outputBlock(r.output, r.truncated) + "\n退出码 ${r.exitCode}"
                if (r.exitCode == 0) okResult(text) else failResult(text)
            }
            is ExecResult.TimedOut -> failResult(
                "命令超过 ${input.timeoutMs} 毫秒仍未结束，已终止。" +
                    if (r.output.isEmpty()) "超时前没有输出。" else "超时前的输出：\n" + outputBlock(r.output, r.truncated),
            )
            is ExecResult.Backgrounded -> {
                val h = r.handle
                val log = h.logPath?.let { "，日志 $it（用 Read 查看）" } ?: "（没有日志文件）"
                val pid = h.pid?.let { "，pid $it" } ?: ""
                okResult(
                    "已转入后台（id ${h.id}$pid$log）。" +
                        if (r.outputSoFar.isEmpty()) "目前还没有输出。" else "目前的输出：\n" + r.outputSoFar,
                )
            }
        }
    }

    /** 没输出时也要说一声，免得模型误以为工具坏了 */
    private fun outputBlock(output: String, truncated: Boolean): String {
        val body = output.ifEmpty { "（无输出）" }.trimEnd('\n')
        return if (truncated) "$body\n（输出过长，中间部分已省略，只保留了头尾）" else body
    }
}
