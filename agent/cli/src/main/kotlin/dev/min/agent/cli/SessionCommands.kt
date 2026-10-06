package dev.min.agent.cli

import dev.min.agent.kernel.RecapResult
import dev.min.agent.kernel.ResumeAdvice
import java.io.BufferedReader
import java.io.PrintStream
import java.util.Locale

/** `/recap` 打印的一句话（回顾本身是一行，说明性的前缀让它在终端里一眼能认出来） */
internal fun recapNote(result: RecapResult): String = when (result) {
    is RecapResult.Generated -> "※ 回顾：${result.text}"
    is RecapResult.Skipped -> "没有生成回顾：${result.reason}"
    is RecapResult.Failed -> "回顾生成失败（会话不受影响）：${result.reason}"
}

/**
 * 只有交互模式的 `--resume` / `--continue` 才问（CC：`-p` 不弹框，照原样续接）。
 * `--fork-session` 也走这里，但分支的日志是刚复制出来的，内核量到的静置时间接近 0，自然不会建议
 */
internal fun shouldOfferResume(args: CliArgs): Boolean = args.prompt == null && (args.resume != null || args.continueMostRecent)

/** 「1」「s」「summary」「摘要」选摘要；其余（含直接回车、输入流已关）一律完整续接——默认不丢任何东西 */
internal fun wantsSummary(answer: String?): Boolean = answer?.trim()?.lowercase(Locale.ROOT) in setOf("1", "s", "summary", "摘要")

internal fun resumeQuestion(advice: ResumeAdvice): String = buildString {
    append("这个会话已经空闲约 ").append(describeIdle(advice.idleMs))
    append("，上下文约 ").append(describeTokens(advice.contextTokens)).append(" token。")
    append("提示缓存早已过期，完整续接要把它们按全价重新读一遍")
    advice.rereadCost?.let { append("（估算约 ").append(String.format(Locale.ROOT, "%.2f", it.amount)).append(' ').append(it.currency).append('）') }
    append("。\n  1) 从摘要续接：先压缩成摘要再继续，省钱，细节靠原文附件\n  2) 完整续接（默认）\n选择 [1/2]：")
}

internal fun describeIdle(ms: Long): String {
    val hours = ms / 3_600_000.0
    return if (hours >= 48) "${(hours / 24).toInt()} 天" else if (hours >= 1.5) String.format(Locale.ROOT, "%.0f 小时", hours) else "1 小时"
}

internal fun describeTokens(tokens: Long): String = if (tokens >= 1_000) String.format(Locale.ROOT, "%.0fk", tokens / 1000.0) else tokens.toString()

/**
 * 重开会话后按内核的建议问一句（[advice] 为 null = 不该问）。返回 true = 选了摘要并且压缩成功。
 * 压缩没成（模型报错、被 hook 拦下……）不是错误：说一声，照完整续接往下走。
 */
internal suspend fun offerResume(
    advice: ResumeAdvice?,
    stdin: BufferedReader,
    out: PrintStream,
    fromSummary: suspend () -> Boolean,
): Boolean {
    advice ?: return false
    out.print(resumeQuestion(advice))
    out.flush()
    if (!wantsSummary(stdin.readLine())) return false
    val done = fromSummary()
    out.println(if (done) "已压缩较早的对话，接着用摘要继续。" else "没能压缩，按完整续接继续。")
    return done
}
