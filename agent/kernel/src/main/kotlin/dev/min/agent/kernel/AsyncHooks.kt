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
import dev.min.agent.spi.ExecHandle
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
import kotlinx.coroutines.CoroutineStart
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 后台 hook 执行器（docs/agent/detail/hooks设计.md §async）：`async` / `asyncRewake` 的 command handler
 * 不挡回合——宿主的 exec 后台机制（[ExecResult.Backgrounded]）替我们养着进程，本侧一个协程轮询结果文件，
 * 完成了才落 [HooksRan] 与上下文。启动本身不落事件：进程死了日志里就没有「还在跑」的痕迹，重开会话不假装
 * 在等（日志即真相）。
 *
 * 为什么用宿主的后台句柄而不是自己 `( … ) &` 加 pid 文件：Android 的命令上下文是 `killOnExit = true`，
 * 启动脚本一退出 proot 就把 hook 一起带走；桌面上 `kill -9 <pid>` 只杀得着子 shell、孙进程成了孤儿。
 * 宿主的 `kill(handle)` 按整棵进程树回收（只杀自己名下的），两种宿主都闭环。生命周期跟着运行时：
 * 会话卸下（close / shutdown / delete）时 [cancelAll] 杀掉还在跑的进程并清掉暂存目录——手机上不能留
 * 孤儿进程（这是 Min 加的边界，CC 的 `-p` teardown 同样 kill）
 */
internal class AsyncHookRunner(
    private val rt: SessionRuntime,
    private val scope: CoroutineScope,
) {
    private val deps = rt.deps

    /** 轮询节奏从 rt 的测试缝读（默认值即生产值；内核测试调小，免得真等半分钟） */
    private val pollIntervalMs get() = rt.asyncHookTimings.pollMs
    private val livenessIntervalMs get() = rt.asyncHookTimings.livenessMs
    private val fallbackLivenessIntervalMs get() = rt.asyncHookTimings.fallbackLivenessMs

    private class Running(val id: String, val dir: GuestPath, val handle: ExecHandle?, val job: Job)

    private val running = java.util.concurrent.ConcurrentHashMap<String, Running>()

    /** launch 与 cancelAll 互斥：关会话时正在启动的 hook 也要拿到句柄再被杀，不漏任务 */
    private val launchLock = Mutex()

    /** 起不来的 handler 只报一次（CC 2.1.287 的修复：脚本缺失不再反复唤醒、反复刷屏） */
    private val reportedBroken = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 测试缝：一轮轮询调一次（等结果文件出现的空档里插进测试动作） */
    @Volatile
    internal var pollProbe: (() -> Unit)? = null

    /**
     * 启动一个后台 hook 并立刻返回（不挡回合）。[stdin] / [env] 与同步批同一份（HookDriver 组好的）；
     * [timeoutSeconds] 只对 asyncRewake 有值——CC 明说 async 不套 timeout，asyncRewake 要套。
     * 前台预算压到 1ms：宿主把进程挂进自己的后台机制后返回句柄，脚本本身活着多久 hook 就跑多久
     */
    suspend fun launch(
        event: HookEvent,
        handler: HookHandler,
        stdin: JsonObject,
        env: Map<String, String>,
        cwd: GuestPath,
        timeoutSeconds: Int?,
    ) {
        launchLock.withLock {
        val id = deps.ids.next("hookbg")
        // 删除只能走 shell，id 必须是单个安全路径段，不能把范围扩到 hooks 目录以外
        require(SAFE_ID.matches(id)) { "后台 hook id 必须是安全路径段" }
        val dir = GuestPath("/root/.min/hooks/$id")
        deps.host.fs.writeAtomic(child(dir, "in.json"), stdin.toString().encodeToByteArray())
        // owner 记号（N1）：启动清扫据此认「这是谁留下的」——别的实例的目录、且其进程确认已死，才会被删
        deps.host.fs.writeAtomic(child(dir, "owner"), (rt.hub?.hookStagingOwner ?: "unowned 0").encodeToByteArray())
        deps.host.fs.writeAtomic(child(dir, "start.sh"), script(handler, env, dir, timeoutSeconds).encodeToByteArray())
        val started = deps.clock.nowMs()
        // Windows 的 ProcessBuilder -> Git Bash 会破坏复杂的 -c 参数；与同步批同一条路：脚本文本走文件端口（HookBatchRunner 同）。
        // timeoutMs 给到天级：Android 宿主会在到点时硬杀后台进程（桌面不设限），它是后台 hook 的寿命上限而不是本轮的等待
        val result = deps.host.exec.run(
            ShellCommand("sh ${cq(deps.hookShellPath(child(dir, "start.sh")))}", cwd, dev.min.agent.ext.hooks.pluginProcessEnv(listOf(handler)), extraWritable = dev.min.agent.ext.hooks.hookWritableRoots(env)),
            ExecOptions(timeoutMs = MAX_LIFETIME_MS, foregroundBudgetMs = 1),
        )
        if (result !is ExecResult.Backgrounded) {
            // 没转成后台：脚本在前台预算（1ms）内就结束了。跑完了的（退出码文件已在）照常收尾读结果；
            // 起不来的（Exit 127 一类，退出码永远不会有）直接落说明收尾——句柄是 null 就没有存活探测兜底，
            // 注册成轮询会无限等到会话关闭（N2）
            if (fs.stat(child(dir, "code")) != null) {
                val job = scope.launch(start = CoroutineStart.LAZY) { watch(id, event, handler, dir, null, started, timeoutSeconds != null) }
                running[id] = Running(id, dir, null, job)
                job.start()
            } else {
                finishWithProblem(event, handler, started, "启动脚本没转入后台就结束了，也没写出退出码（多半是起不来）")
                withContext(NonCancellable) { cleanup(dir, null) }
            }
            return@withLock
        }
        val handle = result.handle
        // 先登记再启动（懒启动）：cancelAll 拿锁做快照，不会在登记前把任务漏掉
        val job = scope.launch(start = CoroutineStart.LAZY) { watch(id, event, handler, dir, handle, started, timeoutSeconds != null) }
        running[id] = Running(id, dir, handle, job)
        job.start()
        }
    }

    /**
     * 后台 hook 的启动脚本：进程号、输出、退出码各落各的文件。脚本自己就是进程树的根（不甩后台），
     * 宿主按句柄整树回收；数字都先写 `.tmp` 再 `mv`，轮询侧只见完整文件、读不到半截
     */
    private fun script(handler: HookHandler, env: Map<String, String>, dir: GuestPath, timeoutSeconds: Int?): String = buildString {
        append("# Min async hook\n")
        env.forEach { (name, value) ->
            if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) append("export $name=${q(value)}\n")
        }
        append("if command -v mv >/dev/null 2>&1; then\n")
        append("  atomic_out() { printf '%s\\n' \"\$2\" > \"\$1.tmp\" && mv \"\$1.tmp\" \"\$1\"; }\n")
        append("else\n  atomic_out() { printf '%s\\n' \"\$2\" > \"\$1\"; }\nfi\n")
        val invocation = if (handler.args != null) {
            val argv = (handler.command?.let(::listOf) ?: emptyList()) + handler.args!!
            argv.mapIndexed { ai, arg -> if (handler.source == dev.min.agent.ext.config.ConfigSource.Plugin)
                "\"\${MIN_PLUGIN_0_ARG_$ai}\"" else q(arg) }.joinToString(" ")
        } else "sh -c ${q(requireNotNull(handler.command))}"
        handler.pluginEnv.keys.forEachIndexed { ei, key -> if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) append("export $key=\"\${MIN_PLUGIN_0_ENV_$ei}\"\n") }
        val input = q(deps.hookShellPath(child(dir, "in.json")))
        val out = q(deps.hookShellPath(child(dir, "out")))
        val err = q(deps.hookShellPath(child(dir, "err")))
        append("atomic_out ${q(deps.hookShellPath(child(dir, "pid")))} \"\$\$\"\n")
        // asyncRewake 套 timeout（超时退出码 124，-k 补杀后 137；没有 timeout 命令时退化为不套——CC 原文）
        if (timeoutSeconds != null) {
            append("if command -v timeout >/dev/null 2>&1; then\n")
            append("timeout -k 2 $timeoutSeconds $invocation < $input > $out 2> $err\n")
            append("else\n$invocation < $input > $out 2> $err\nfi\n")
        } else {
            append("$invocation < $input > $out 2> $err\n")
        }
        append("atomic_out ${q(deps.hookShellPath(child(dir, "code")))} \"\$?\"\n")
    }

    private suspend fun watch(
        id: String,
        event: HookEvent,
        handler: HookHandler,
        dir: GuestPath,
        handle: ExecHandle?,
        startedAt: Long,
        timedOutBySelf: Boolean,
    ) {
        val codeFile = child(dir, "code")
        try {
            if (!waitForResult(codeFile, dir, handle, startedAt)) {
                // 兜底：进程没了而退出码没写出（被外部杀死、崩溃）——不能无限轮询，落一条说明收尾
                finishWithProblem(event, handler, startedAt, "进程结束了，但没写出退出码（被外部杀死或崩溃）")
                return
            }
            val code = read(child(dir, "code"))?.trim()?.toIntOrNull()
            val out = readBounded(child(dir, "out"))
            val err = readBounded(child(dir, "err"))
            val run = dev.min.agent.ext.hooks.HandlerRun(
                handler = handler,
                exitCode = code,
                stdout = out.text,
                stderr = err.text,
                // 124 = TERM 生效；137 = 没理 TERM、被 -k 的 KILL 补杀——都是自己杀的，按超时算
                timedOut = timedOutBySelf && (code == 124 || code == 137),
                durationMs = (deps.clock.nowMs() - startedAt).coerceAtLeast(0),
            )
            finish(event, handler, run, out.truncated, err.truncated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 轮询与读取的意外不许把协程炸成未捕获异常：记一条说明照常收尾
            finishWithProblem(event, handler, startedAt, "后台 hook 收尾失败：${e.javaClass.simpleName}")
        } finally {
            // 先清理、后出表：反过来的话，正在清理的协程已不在 running 里，cancelAll 返回时它的 rm 可能还没跑
            withContext(NonCancellable) { cleanup(dir, handle) }
            running.remove(id)
        }
    }

    /** 等结果文件出现；进程先没了（退出码永远等不来）返回 false */
    private suspend fun waitForResult(codeFile: GuestPath, dir: GuestPath, handle: ExecHandle?, startedAt: Long): Boolean {
        var lastHandleProbe = 0L
        var lastPidProbe = 0L
        while (fs.stat(codeFile) == null) {
            pollProbe?.invoke()
            val now = deps.clock.nowMs()
            if (now - startedAt >= livenessIntervalMs) {
                // 句柄探活走宿主登记的 Process（不起进程），按原间隔；端口说不了（null）才退回
                // `kill -0`——那要起进程，间隔拉长到 30 秒（N3）
                val byHandle = if (handle != null) hostAlive(handle) else null
                val last = if (byHandle != null) lastHandleProbe else lastPidProbe
                val interval = if (byHandle != null) livenessIntervalMs else fallbackLivenessIntervalMs
                if (now - last >= interval) {
                    if (byHandle != null) lastHandleProbe = now else lastPidProbe = now
                    val living = byHandle ?: aliveByPid(dir)
                    if (!living) {
                        // 进程可能正走在写退出码的路上：缓一步再判一次，别把刚完成的误报成死
                        delay(pollIntervalMs * 2)
                        if (fs.stat(codeFile) == null) {
                            val again = if (handle != null) hostAlive(handle) else null
                            if (!(again ?: aliveByPid(dir))) return false
                        }
                    }
                }
            }
            delay(pollIntervalMs)
        }
        return true
    }

    /** 宿主按句柄说存活：端口不认识句柄（默认实现）时是 null */
    private suspend fun hostAlive(handle: ExecHandle): Boolean? =
        catchingNonCancellation { deps.host.exec.isAlive(handle) }.getOrNull()

    /**
     * 进程还活着吗（退回路径）：pid 文件里像 pid 才信（目录在 rootfs 里，模型改得了内容），`kill -0` 探测。
     *  只有拿到**明确的非零退出码**才算死：探测超时（重负载下起个探测进程慢很正常）、探测起不来、
     *  意外转后台都当活着——把超时当已死会误杀健康的长 hook，接着 cleanup 还会杀掉它（N2）
     */
    private suspend fun aliveByPid(dir: GuestPath): Boolean {
        val pid = try {
            read(child(dir, "pid"))?.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return true // pid 还没写出来：当作活着，别误判
        if (!SAFE_PID.matches(pid)) return true
        return try {
            val r = deps.host.exec.run(ShellCommand("kill -0 $pid", rt.state.value.settings.cwd), ExecOptions(10_000))
            !(r is ExecResult.Exited && r.exitCode != 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true // 探测自己没跑成：当作活着
        }
    }

    /** 后台 hook 跑完了：剥掉控制字段（动作已经发生了）、落 HooksRan、把输出安排给下一个回合 */
    private suspend fun finish(
        event: HookEvent,
        handler: HookHandler,
        run: dev.min.agent.ext.hooks.HandlerRun,
        outTruncated: Boolean,
        errTruncated: Boolean,
    ) {
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
        if (outTruncated) problems += "「$label」的 stdout 超过 ${READ_CAP_BYTES / 1024}KB，只保留了开头"
        if (errTruncated) problems += "「$label」的 stderr 超过 ${READ_CAP_BYTES / 1024}KB，只保留了开头"
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
        // once 的成功口径与同步路径同一把尺（见 HookDriver.onceSucceeded）；exit 2 会走下面的唤醒，不算成功
        if (handler.once && onceSucceeded(event, run, verdict)) spendOnce(rt, handler)
        if (wake) {
            // 唤醒文本照同步路径限长：超长存附件给预览（读文件时也只读了有界的开头）
            val raw = run.stderr.ifBlank { run.stdout }.trim()
                .ifEmpty { "（后台 hook「$label」以退出码 2 结束，没有输出说明）" }
            val text = HookDriver.attachText(deps, rt.sessionId, event, HookText(raw))
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

    /** 结果文件按字节上限读：hook 的输出没人限过大小，整个读进内存可能把进程吃爆 */
    private suspend fun readBounded(path: GuestPath): Bounded = try {
        val stat = fs.stat(path)
        if (stat == null) Bounded("", false)
        else {
            val bytes = fs.read(path, 0, READ_CAP_BYTES)
            // 尾巴上可能切了半个多字节字符，decode 成替换字符——预览够用
            Bounded(bytes.decodeToString(), stat.size > bytes.size)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Bounded("", false)
    }

    private class Bounded(val text: String, val truncated: Boolean)

    /**
     * 会话卸下前调用：join 而不是只 cancel——返回时后台进程已杀完、暂存目录已删（每个协程的 finally
     * 是 NonCancellable 的 [cleanup]）。拿锁做快照：与正在进行的 launch 互斥，不会漏掉刚起的任务。
     * 各任务**并行**收尾：Windows 上一次 `rm` 几百毫秒，十个后台 hook 串行 join 会让关会话多等几秒
     */
    suspend fun cancelAll() = kotlinx.coroutines.coroutineScope {
        val all = launchLock.withLock { running.values.toList() }
        all.forEach { task ->
            launch { catchingNonCancellation { task.job.cancelAndJoin() } }
        }
    }

    /**
     * 收尾：自然结束（退出码已写出）只删目录、不杀——句柄在宿主运行表里可能已被摘掉，盲目杀有误伤的
     * 窗口；没结束就 `kill(handle)` 整树回收。删目录是唯一的一次进程调用
     */
    private suspend fun cleanup(dir: GuestPath, handle: ExecHandle?) {
        try {
            val finished = fs.stat(child(dir, "code")) != null
            if (!finished && handle != null) deps.host.exec.kill(handle)
            val rm = deps.host.exec.run(
                ShellCommand("rm -rf -- ${q(deps.hookShellPath(dir))}", rt.state.value.settings.cwd, extraWritable = dev.min.agent.ext.hooks.hookWritableRoots()),
                ExecOptions(5_000),
            )
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
        val SAFE_PID = Regex("[0-9]{1,10}")

        /** 后台 hook 的寿命上限：Android 宿主到点硬杀（桌面宿主对后台进程不设限），7 天足够任何会话 */
        const val MAX_LIFETIME_MS = 86_400_000L * 7

        /** 单个结果文件的读取上限（字节）：预览要 2,000 字、附件上限 1 万字，64KB 绰绰有余 */
        const val READ_CAP_BYTES = 64 * 1024
    }
}
