package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookHandler
import dev.min.agent.ext.hooks.HookText
import dev.min.agent.ext.hooks.interpret
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import kotlin.coroutines.cancellation.CancellationException
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.TurnTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 后台 hook 执行器（docs/agent/detail/hooks设计.md §async）：`async` / `asyncRewake` 的 command handler
 * 不挡回合——启动脚本把 hook 进程甩进 shell 后台并记下 pid，本侧一个协程轮询结果文件，完成了才落 [HooksRan]
 * 与上下文。启动本身不落事件：进程死了日志里就没有「还在跑」的痕迹，重开会话不假装在等（日志即真相）。
 *
 * 生命周期跟着运行时：会话卸下（close / shutdown / delete）时 [cancelAll] 杀掉还在跑的进程并清掉暂存目录——
 * 手机上不能留孤儿进程（这是 Min 加的边界，CC 的 `-p` teardown 同样 kill）。
 */
internal class AsyncHookRunner(
    private val rt: SessionRuntime,
    private val scope: CoroutineScope,
    /** 测试给小间隔；生产半秒足够新（后台 hook 的输出本来就不赶时间） */
    private val pollIntervalMs: Long = 500,
) {
    private val deps = rt.deps

    private class Running(val id: String, val dir: GuestPath, val job: Job)

    private val running = java.util.concurrent.ConcurrentHashMap<String, Running>()

    /** 起不来的 handler 只报一次（CC 2.1.287 的修复：脚本缺失不再反复唤醒、反复刷屏） */
    private val reportedBroken = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 测试缝：一轮轮询调一次（等结果文件出现的空档里插进测试动作） */
    @Volatile
    internal var pollProbe: (() -> Unit)? = null

    /**
     * 启动一个后台 hook 并立刻返回（不挡回合）。[stdin] / [env] 与同步批同一份（HookDriver 组好的）；
     * [timeoutSeconds] 只对 asyncRewake 有值——CC 明说 async 不套 timeout，asyncRewake 要套。
     * 启动脚本把 hook 进程甩进 shell 后台就退出：这一步只花毫秒，真正干活的进程在后台继续
     */
    suspend fun launch(
        event: HookEvent,
        handler: HookHandler,
        stdin: JsonObject,
        env: Map<String, String>,
        cwd: GuestPath,
        timeoutSeconds: Int?,
    ) {
        val id = deps.ids.next("hookbg")
        // 删除只能走 shell，id 必须是单个安全路径段，不能把范围扩到 hooks 目录以外
        require(SAFE_ID.matches(id)) { "后台 hook id 必须是安全路径段" }
        val dir = GuestPath("/root/.min/hooks/$id")
        deps.host.fs.writeAtomic(child(dir, "in.json"), stdin.toString().encodeToByteArray())
        deps.host.fs.writeAtomic(child(dir, "start.sh"), script(handler, env, dir, timeoutSeconds).encodeToByteArray())
        val started = deps.clock.nowMs()
        // Windows 的 ProcessBuilder -> Git Bash 会破坏复杂的 -c 参数；与同步批同一条路：脚本文本走文件端口（HookBatchRunner 同）
        val result = deps.host.exec.run(
            ShellCommand("sh ${cq(deps.hookShellPath(child(dir, "start.sh")))}", cwd),
            ExecOptions(5_000),
        )
        if (result is ExecResult.Backgrounded) deps.host.exec.kill(result.handle)
        val job = scope.launch { watch(id, event, handler, dir, started, timedOutBySelf = timeoutSeconds != null) }
        running[id] = Running(id, dir, job)
    }

    /** 后台 hook 的启动脚本：进程甩进 shell 后台、pid 落文件（卸下时按它杀），退出码与输出各落各的文件 */
    private fun script(handler: HookHandler, env: Map<String, String>, dir: GuestPath, timeoutSeconds: Int?): String = buildString {
        append("# Min async hook\n")
        env.forEach { (name, value) ->
            if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) append("export $name=${q(value)}\n")
        }
        val invocation = if (handler.args != null) {
            val argv = (handler.command?.let(::listOf) ?: emptyList()) + handler.args!!
            argv.joinToString(" ", transform = ::q)
        } else "sh -c ${q(requireNotNull(handler.command))}"
        val input = q(deps.hookShellPath(child(dir, "in.json")))
        val out = q(deps.hookShellPath(child(dir, "out")))
        val err = q(deps.hookShellPath(child(dir, "err")))
        val code = q(deps.hookShellPath(child(dir, "code")))
        append("(\n")
        // asyncRewake 套 timeout（超时退出码 124）；async 不套（CC 原文）。没有 timeout 命令时退化为不套
        if (timeoutSeconds != null) {
            append("if command -v timeout >/dev/null 2>&1; then\n")
            append("timeout -k 2 $timeoutSeconds $invocation < $input > $out 2> $err\n")
            append("else\n$invocation < $input > $out 2> $err\nfi\n")
        } else {
            append("$invocation < $input > $out 2> $err\n")
        }
        append("printf '%s\\n' \"\$?\" > $code\n")
        append(") &\n")
        append("printf '%s\\n' \"\$!\" > ${q(deps.hookShellPath(child(dir, "pid")))}\n")
    }

    private suspend fun watch(id: String, event: HookEvent, handler: HookHandler, dir: GuestPath, startedAt: Long, timedOutBySelf: Boolean) {
        val codeFile = child(dir, "code")
        try {
            while (fs.stat(codeFile) == null) {
                pollProbe?.invoke()
                delay(pollIntervalMs)
            }
            val run = dev.min.agent.ext.hooks.HandlerRun(
                handler = handler,
                exitCode = read(child(dir, "code"))?.trim()?.toIntOrNull(),
                stdout = read(child(dir, "out")).orEmpty(),
                stderr = read(child(dir, "err")).orEmpty(),
                timedOut = timedOutBySelf && read(child(dir, "code"))?.trim() == "124",
                durationMs = (deps.clock.nowMs() - startedAt).coerceAtLeast(0),
            )
            finish(event, handler, run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 轮询与读取的意外不许把协程炸成未捕获异常：记一条说明照常收尾
            finishWithProblem(event, handler, startedAt, "后台 hook 收尾失败：${e.javaClass.simpleName}")
        } finally {
            running.remove(id)
            withContext(NonCancellable) { cleanup(dir) }
        }
    }

    /** 后台 hook 跑完了：剥掉控制字段（动作已经发生了）、落 HooksRan、把输出安排给下一个回合 */
    private suspend fun finish(event: HookEvent, handler: HookHandler, run: dev.min.agent.ext.hooks.HandlerRun) {
        val label = label(handler)
        val verdict = interpret(event, run)
        val problems = mutableListOf<String>()
        // CC：async 的 decision / permissionDecision / continue 一律无效——对应的动作早就发生了。收到就说明忽略
        if (verdict.decision != dev.min.agent.ext.hooks.HookDecision.Neutral || !verdict.shouldContinue ||
            verdict.stopReason != null || verdict.updatedInput != null
        ) {
            problems += "「$label」是后台 hook，decision / continue / updatedInput 这类控制字段无效（对应的动作已经发生了），已忽略"
        }
        if (run.timedOut) problems += "「$label」超时被杀"
        val rewake = handler.asyncRewake
        val wake = rewake && run.exitCode == 2 && !run.timedOut && !verdict.failedToStart
        if (verdict.failedToStart) {
            // 脚本缺失 / 起不来：只报一次，不唤醒（CC 2.1.287 修复的正是这个反复唤醒）
            if (reportedBroken.add(label)) {
                problems += "「$label」没能运行（路径写错时它会一直悄悄失败）"
            }
        } else if (rewake && run.exitCode != 2 && run.exitCode != 0) {
            problems += "「$label」退出码 ${run.exitCode}，只有 2 才唤醒"
        }
        // additionalContext 与 systemMessage 都在下一个回合送给模型（CC：都不给人看）——systemMessage 在同步路径
        // 是给人的 Note，这里转成给模型的上下文，走同一个 hook_context_added（挂在下一条人发的消息上）
        val texts = verdict.additionalContext + listOfNotNull(verdict.systemMessage)
        val events = buildList {
            add(HooksRan(event.name, 1, "neutral", run.durationMs, problems = problems, background = true))
            if (problems.isNotEmpty()) add(Note(NoteLevel.Warning, "$event 后台 hook：" + problems.joinToString("；")))
            texts.forEach { t ->
                add(HookContextAdded(HookContextTarget.NextUserMessage, event.name, HookDriver.attachText(deps, rt.sessionId, event, t)))
            }
        }
        rt.commit(events, Durability.Batched)
        // once 的成功口径与同步路径一致：跑完、退出码 0、没超时没缺胳膊少腿（exit 2 会走下面的唤醒，不算成功）
        if (handler.once && run.exitCode == 0 && !run.timedOut && !verdict.failedToStart) spendOnce(rt, handler)
        if (wake) {
            val text = run.stderr.ifBlank { run.stdout }.trim().ifEmpty { "（后台 hook「$label」以退出码 2 结束，没有输出说明）" }
            rt.receive(
                ClientMsgId("async-rewake:${rt.sessionId.value}:${deps.ids.next("rewake")}"),
                listOf(TextPart("后台 asyncRewake hook「$label」报告了问题，需要你处理：\n$text")),
                Delivery.Queue,
                InputOrigin.Hook("async_rewake"),
                startTurn = TurnTrigger.Hook,
            )
        }
    }

    private suspend fun finishWithProblem(event: HookEvent, handler: HookHandler, startedAt: Long, problem: String) {
        rt.commit(
            listOf(
                HooksRan(event.name, 1, "neutral", (deps.clock.nowMs() - startedAt).coerceAtLeast(0), problems = listOf(problem), background = true),
                Note(NoteLevel.Warning, "$event 后台 hook：$problem"),
            ),
            Durability.Batched,
        )
    }

    private val fs get() = deps.host.fs

    private suspend fun read(path: GuestPath): String? = try {
        if (fs.stat(path) == null) null else fs.read(path).decodeToString()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 会话卸下前调用：停掉轮询协程。杀进程与清目录由每个协程的 finally（NonCancellable 的 [cleanup]）做——
     * cancel 之后它必然执行，这里不重复 reap。pid 文件的内容必须长得像 pid 才用它——目录在 rootfs 里，模型改得了它
     */
    suspend fun cancelAll() {
        running.values.toList().forEach { it.job.cancel() }
    }

    /** 收尾：尽力杀进程（自然结束时多半已退出，kill 报错忽略），再删掉整个暂存目录 */
    private suspend fun cleanup(dir: GuestPath) {
        reap(dir)
    }

    private suspend fun reap(dir: GuestPath) {
        val cwd = rt.state.value.settings.cwd
        try {
            val pid = read(child(dir, "pid"))?.trim()
            if (pid != null && Regex("[0-9]{1,10}").matches(pid)) {
                val kill = deps.host.exec.run(
                    // kill 自己 + kill 直接子进程（pkill -P）：后台 job 是一层 subshell，命令在里面
                    ShellCommand("kill -9 $pid 2>/dev/null; pkill -9 -P $pid 2>/dev/null; true", cwd),
                    ExecOptions(5_000),
                )
                if (kill is ExecResult.Backgrounded) deps.host.exec.kill(kill.handle)
            }
            val rm = deps.host.exec.run(ShellCommand("rm -rf -- ${q(deps.hookShellPath(dir))}", cwd), ExecOptions(5_000))
            if (rm is ExecResult.Backgrounded) deps.host.exec.kill(rm.handle)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 收尾失败只剩一个暂存目录，不值得为它打断卸下流程
        }
    }

    private fun label(handler: HookHandler): String =
        (handler.command ?: handler.args?.firstOrNull() ?: handler.type).lineSequence().first().take(200)

    private fun child(dir: GuestPath, name: String) = GuestPath("${dir.value}/$name")
    private fun q(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"
    private fun cq(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** 测试断言「关闭时后台 hook 都收掉了」用 */
    internal fun runningCount(): Int = running.size

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
