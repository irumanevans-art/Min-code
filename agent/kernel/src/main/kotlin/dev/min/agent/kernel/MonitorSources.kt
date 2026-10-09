package dev.min.agent.kernel

import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一个 watch 的事件源怎么结束的（期限与被停不在这里：那是协程取消，由 [BackgroundJobs] 判） */
internal sealed interface MonitorEnd {
    /** 命令自己结束了；[code] 拿不到（端口说不了、记录被挤掉）为 null */
    data class Exited(val code: Int?) : MonitorEnd

    /** 对端正常关闭（WebSocket 的关闭码写在 [detail] 里，要告诉模型） */
    data class Closed(val detail: String) : MonitorEnd

    /** 源出了问题（消息超过上限、连接断了）：[detail] 写给模型 */
    data class Failed(val detail: String) : MonitorEnd
}

/**
 * Monitor 的事件源：命令（每输出一行一个事件）或 WebSocket（每条文本消息一个事件）。
 * [collect] 一直跑到源自己结束；协程被取消（期限到、被停、会话关）时在 finally 里收尾（杀进程、关连接）。
 */
internal interface MonitorSource {
    /** 模型能用 Read 读的输出文件；没有为 null */
    val outputPath: GuestPath?

    suspend fun collect(emit: (String) -> Unit): MonitorEnd
}

/** 日志里一行最多给模型多少字符：一行压缩过的 JSON 也可能有几万字，整条塞进上下文不值得，全文在输出文件里 */
internal const val MONITOR_MAX_LINE_CHARS = 2_000

internal fun clipMonitorLine(line: String): String =
    if (line.length <= MONITOR_MAX_LINE_CHARS) line
    else line.take(MONITOR_MAX_LINE_CHARS) + "…（这一行还有 ${line.length - MONITOR_MAX_LINE_CHARS} 个字符，见输出文件）"

/**
 * 命令源：用宿主的后台机制起命令（与 Bash 的 `run_in_background` 同一套，不另起一条进程路径），
 * 再读它的日志文件。输出是 stdout 与 stderr 合并的——和 Bash 的后台日志一样，错误信息也能让模型看到。
 *
 * 为什么轮询日志而不是接管道：宿主的 [Exec] 端口只在「跑完 / 转后台」时返回，没有流式读口；
 * 日志从第一字节起持续可读（桌面 Tee、Android guest 内重定向），按偏移读新增的字节就是「每行一个事件」。
 * 只在读到换行时才切出一行，半行留到下一轮：不会把一行拆成两个事件，也不会把多字节字符切开。
 */
internal class CommandMonitorSource private constructor(
    private val exec: Exec,
    private val fs: WorkspaceFs,
    private val handle: ExecHandle?,
    override val outputPath: GuestPath,
    private val pollMs: () -> Long,
    /** 没有句柄的（跑得比前台预算还快）命令的退出码；有句柄时向宿主查 */
    private val knownExit: Int? = null,
) : MonitorSource {
    override suspend fun collect(emit: (String) -> Unit): MonitorEnd {
        var offset = 0L
        var carry = ByteArray(0)
        var ended = false
        try {
            /** 读新增字节，切出整行交给 [emit]；返回这一轮有没有读到东西 */
            suspend fun pump(): Boolean {
                val size = fs.stat(outputPath)?.size ?: 0L
                // 文件比上次短了：命令自己截断 / 重建了日志，从头来
                if (size < offset) { offset = 0; carry = ByteArray(0) }
                if (size == offset) return false
                val chunk = fs.read(outputPath, offset, minOf(size - offset, READ_CHUNK_BYTES.toLong()).toInt())
                if (chunk.isEmpty()) return false
                offset += chunk.size
                val buf = carry + chunk
                val cut = buf.lastIndexOf('\n'.code.toByte())
                if (cut < 0) {
                    // 一整块都没有换行：超过上限就当一行切出去，不让「永不换行的输出」把内存吃光
                    if (buf.size > MAX_CARRY_BYTES) { emitLines(buf.decodeToString(), emit); carry = ByteArray(0) } else carry = buf
                    return true
                }
                carry = buf.copyOfRange(cut + 1, buf.size)
                emitLines(buf.copyOfRange(0, cut).decodeToString(), emit)
                return true
            }
            while (true) {
                while (pump()) Unit
                val alive = handle?.let { catchingNonCancellation { exec.isAlive(it) }.getOrNull() } ?: (handle != null)
                if (!alive) {
                    // 进程刚走，最后一批输出可能还在路上：再读到没有为止，剩下的半行当最后一行
                    while (pump()) Unit
                    if (carry.isNotEmpty()) emitLines(carry.decodeToString(), emit)
                    ended = true
                    return MonitorEnd.Exited(handle?.let { catchingNonCancellation { exec.exitOf(it) }.getOrNull() } ?: knownExit)
                }
                delay(pollMs())
            }
        } finally {
            // 没自己结束就是被取消了（期限、停止、会话关闭）：杀整棵进程树，手机上不能留孤儿进程
            if (!ended && handle != null) withContext(NonCancellable) { catchingNonCancellation { exec.kill(handle) } }
        }
    }

    private fun emitLines(text: String, emit: (String) -> Unit) {
        // 空行没有信息量；\r 是 Windows 换行的尾巴
        text.split('\n').forEach { raw -> raw.trimEnd('\r').takeIf { it.isNotBlank() }?.let { emit(clipMonitorLine(it)) } }
    }

    companion object {
        private const val READ_CHUNK_BYTES = 256 * 1024
        private const val MAX_CARRY_BYTES = 1024 * 1024

        /** 起命令。起不来（宿主抛异常）由调用方转成「没起」；跑得比前台预算还快的命令没有句柄，输出落到一份自己的文件里照常当事件发 */
        suspend fun start(
            exec: Exec, fs: WorkspaceFs, command: String, cwd: GuestPath, timeoutMs: Long,
            spillPath: GuestPath, pollMs: () -> Long,
            /** 起进程的口：经网络沙箱（命令的网络访问要过逐域名批准，与 Bash 同一条路），没开沙箱时就是 [Exec.run] */
            runShell: suspend (ShellCommand, ExecOptions) -> ExecResult,
        ): CommandMonitorSource = when (val r = runShell(ShellCommand(command, cwd), ExecOptions(timeoutMs = timeoutMs, foregroundBudgetMs = 1))) {
            is ExecResult.Backgrounded -> CommandMonitorSource(
                exec, fs, r.handle,
                // 没有日志路径的宿主（端口说不了）：没法读输出，退回一份只有开头输出的文件，至少事件不为空
                r.handle.logPath ?: spillPath.also { fs.writeAtomic(it, r.outputSoFar.encodeToByteArray()) }, pollMs,
            )
            is ExecResult.Exited -> CommandMonitorSource(exec, fs, null, spillPath.also { fs.writeAtomic(it, r.output.encodeToByteArray()) }, pollMs, r.exitCode)
            is ExecResult.TimedOut -> CommandMonitorSource(exec, fs, null, spillPath.also { fs.writeAtomic(it, r.output.encodeToByteArray()) }, pollMs)
        }
    }
}
