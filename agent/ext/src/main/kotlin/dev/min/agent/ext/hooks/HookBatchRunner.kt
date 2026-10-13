package dev.min.agent.ext.hooks

import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.ShellQuote
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class HandlerRun(
    val handler: HookHandler,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val failedToStart: Boolean = false,
    val durationMs: Long = 0,
    val notes: List<String> = emptyList(),
)

data class HookBatchScript(val script: String, val notes: List<String>)

/** shell 命令只在 guest 执行；文件端口负责原样传回三个通道。 */
class HookBatchRunner(
    private val exec: Exec,
    private val fs: WorkspaceFs,
    private val ids: IdGenerator,
    private val shellPathOf: (GuestPath) -> String,
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
) {
    suspend fun run(
        handlers: List<HookHandler>,
        stdin: JsonObject,
        env: Map<String, String>,
        cwd: GuestPath,
        timeoutFor: (HookHandler) -> Int,
    ): List<HandlerRun> {
        if (handlers.isEmpty()) return emptyList()
        require(handlers.all { it.executable }) { "仅同步 command hook 可以进合批执行器" }
        val id = ids.next("hooks")
        // 删除只能走 shell，id 必须是单个安全路径段，不能把删除范围扩到批目录以外。
        require(Regex("[A-Za-z0-9_-]+").matches(id)) { "批 id 必须是安全路径段" }
        val dir = GuestPath("/root/.min/hooks/$id")
        val timeouts = handlers.map(timeoutFor)
        require(timeouts.all { it > 0 }) { "hook 超时必须为正整数秒" }
        val batch = buildScript(handlers, env, dir, timeouts)
        val started = clock.nowMs()
        val notes = batch.notes.toMutableList()
        var runs: List<HandlerRun> = emptyList()
        try {
            fs.writeAtomic(child(dir, "in.json"), stdin.toString().encodeToByteArray())
            // Windows 的 ProcessBuilder -> Git Bash 会破坏复杂的 -c 参数；把脚本文本走文件端口，
            // Exec 只运输一个安全引用的路径，Android 也走相同协议。
            val scriptPath = child(dir, "batch.sh")
            fs.writeAtomic(scriptPath, batch.script.encodeToByteArray())
            val result = exec.run(ShellCommand("sh ${commandQuote(shellPathOf(scriptPath))}", cwd, pluginProcessEnv(handlers), extraWritable = hookWritableRoots(env)),
                ExecOptions(timeouts.max().toLong() * 1_000 + 5_000))
            if (result is ExecResult.Exited && (result.exitCode != 0 || result.output.isNotBlank())) {
                notes += "hook 批脚本诊断（退出码 ${result.exitCode}）：${result.output.lineSequence().firstOrNull().orEmpty().take(2_000)}"
            }
            if (result is ExecResult.Backgrounded) {
                exec.kill(result.handle)
                notes += "hook 批执行意外转后台，已回收进程"
            }
            if (readOptional(child(dir, "timeout.missing"), notes) != null) {
                notes += "rootfs 未找到 timeout，逐处理器超时已退化为整批 Exec 超时"
            }
            val elapsed = (clock.nowMs() - started).coerceAtLeast(0)
            runs = handlers.mapIndexed { i, handler ->
                val code = readOptional(child(dir, "code.$i"), notes)?.trim()?.toIntOrNull()
                val stdout = readOptional(child(dir, "out.$i"), notes).orEmpty()
                val stderr = readOptional(child(dir, "err.$i"), notes).orEmpty()
                val duration = readOptional(child(dir, "duration.$i"), notes)?.trim()?.toLongOrNull()
                HandlerRun(handler, code, stdout, stderr,
                    timedOut = code == 124 || (code == null && result is ExecResult.TimedOut),
                    failedToStart = code == null || code == 127,
                    durationMs = duration?.coerceAtLeast(0) ?: elapsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notes += "hook 批执行失败：${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}"
            runs = handlers.map { HandlerRun(it, null, "", "", failedToStart = true,
                durationMs = (clock.nowMs() - started).coerceAtLeast(0)) }
        } finally {
            // WorkspaceFs 没有删除端口；读完再用一次受限的维护命令，取消也不能留下 stdin。
            withContext(NonCancellable) {
                try {
                    val result = exec.run(ShellCommand("rm -rf -- ${commandQuote(shellPathOf(dir))}", cwd, extraWritable = hookWritableRoots()), ExecOptions(5_000))
                    if (result is ExecResult.Backgrounded) exec.kill(result.handle)
                    if (result !is ExecResult.Exited || result.exitCode != 0) notes += "hook 批目录清理失败：$dir"
                } catch (e: Exception) {
                    notes += "hook 批目录清理失败：$dir（${e.javaClass.simpleName}）"
                }
            }
        }
        return runs.map { it.copy(notes = notes.toList()) }
    }

    /** 暴露脚本文本便于审计参数转义；调用方只传已获准注入的环境。 */
    fun buildScript(
        handlers: List<HookHandler>,
        env: Map<String, String>,
        dir: GuestPath,
        timeouts: List<Int>,
    ): HookBatchScript {
        require(handlers.size == timeouts.size && timeouts.all { it > 0 })
        val notes = mutableListOf<String>()
        val script = buildString {
            append("# Min hooks batch\n")
            env.forEach { (name, value) ->
                if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) append("export $name=${ShellQuote.posix(value)}\n")
                else notes += "hook 环境变量名不合法，已跳过：$name"
            }
            // timeout 不在 PATH 时仍执行，整批的 Exec 超时兜底；不能把门禁无声地当成通过。
            append("if command -v timeout >/dev/null 2>&1; then min_hook_timeout=1; else min_hook_timeout=0; ")
            append("printf 'missing' > ${ShellQuote.posix(shellPathOf(child(dir, "timeout.missing")))}; fi\n")
            handlers.forEachIndexed { i, h ->
                require(h.executable)
                val invocation = if (h.args != null) {
                    val argv = if (h.command != null) listOf(h.command) + h.args else h.args
                    require(argv.isNotEmpty())
                    argv.mapIndexed { ai, arg -> if (h.source == dev.min.agent.ext.config.ConfigSource.Plugin)
                        "\"\${MIN_PLUGIN_${i}_ARG_$ai}\"" else ShellQuote.posix(arg) }.joinToString(" ")
                } else "sh -c ${ShellQuote.posix(requireNotNull(h.command))}"
                val input = ShellQuote.posix(shellPathOf(child(dir, "in.json")))
                val out = ShellQuote.posix(shellPathOf(child(dir, "out.$i")))
                val err = ShellQuote.posix(shellPathOf(child(dir, "err.$i")))
                append("(\n")
                h.pluginEnv.keys.forEachIndexed { ei, key -> if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) append("export $key=\"\${MIN_PLUGIN_${i}_ENV_$ei}\"\n") }
                append("min_hook_start=\$(date +%s%3N 2>/dev/null)\n")
                append("if [ \"\$min_hook_timeout\" = 1 ]; then\n")
                append("timeout -k 2 ${timeouts[i]} $invocation < $input > $out 2> $err\n")
                append("else\n$invocation < $input > $out 2> $err\nfi\n")
                append("min_hook_code=\$?\n")
                append("printf '%s\\n' \"\$min_hook_code\" > ${ShellQuote.posix(shellPathOf(child(dir, "code.$i")))}\n")
                append("min_hook_end=\$(date +%s%3N 2>/dev/null)\n")
                append("case \"\$min_hook_start:\$min_hook_end\" in *[!0-9:]*|:*|*:) ;; *) ")
                append("printf '%s\\n' \"\$((min_hook_end - min_hook_start))\" > ${ShellQuote.posix(shellPathOf(child(dir, "duration.$i")))} ;; esac\n")
                append(") &\n")
            }
            append("wait\n")
        }
        return HookBatchScript(script, notes)
    }

    private suspend fun readOptional(path: GuestPath, notes: MutableList<String>): String? = try {
        if (fs.stat(path) == null) null else fs.read(path).decodeToString()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        notes += "hook 结果文件读取失败：$path（${e.javaClass.simpleName}）"
        null
    }
}

private fun child(dir: GuestPath, name: String) = GuestPath("${dir.value}/$name")
private fun commandQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/**
 * hook 流程里的命令要额外写的 guest 目录（[ShellCommand.extraWritable]）：staging 根（批目录 / 后台 hook 目录的输出与退出码文件、
 * 收尾的 `rm -rf`），以及 hook 要往里追加 `export` 的 `CLAUDE_ENV_FILE` 所在目录。Android 的命令沙箱默认只让写工作区、临时与缓存目录，
 * 不放行这些的话 command hook 连 `out.0` 都建不出来、环境文件也写不进去。
 * 环境文件不在 `/root/.min/artifacts/` 下的宿主（桌面是宿主路径）不需要放行，也不放进清单。
 */
fun hookWritableRoots(env: Map<String, String> = emptyMap()): List<GuestPath> {
    val envDir = env["CLAUDE_ENV_FILE"]?.substringBeforeLast('/', "")?.takeIf { ENV_FILE_DIR.matches(it) }?.let(::GuestPath)
    return listOfNotNull(GuestPath("/root/.min/hooks"), envDir)
}

private val ENV_FILE_DIR = Regex("/root/\\.min/artifacts/[A-Za-z0-9_-]+/env")
