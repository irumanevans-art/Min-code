package dev.min.agent.cli

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 桌面宿主入口：开发时直接驱动内核，也是评测跑批的入口（docs/agent/06-路线图.md M1）。
 * 交互模式一行一句；`-p` 跑完一句就退出，退出码 0 = 回合正常结束。
 */
fun main(argv: Array<String>) {
    val args = try {
        parseArgs(argv, Path.of("").toAbsolutePath())
    } catch (e: UsageException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val code = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            run(args, scope)
        } catch (e: UsageException) {
            System.err.println(e.message)
            2
        } finally {
            scope.cancel()
        }
    }
    exitProcess(code)
}

private suspend fun run(args: CliArgs, scope: CoroutineScope): Int {
    val app = DesktopApp(args, scope)
    app.checkKey()
    return interact(app, app.openSession(), args, scope)
}

private suspend fun interact(app: DesktopApp, initial: dev.min.agent.kernel.SessionRuntime, args: CliArgs, scope: CoroutineScope): Int {
    // /branch 会换会话：退出时的 SessionEnd 要跟当前会话走（分支是用户现在所在的对话）
    var runtime = initial
    val stdin = System.`in`.bufferedReader()
    var ui = ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope)
    try {
        System.err.println("会话 ${runtime.sessionId.value}（--resume 可接着用）")

    /** 会话冻结清单拼成 ext 的展开形状。正文此刻才读（与模型路径同一条）；读不了的记下名字，调用处提示 */
    suspend fun frozenDefs(entries: List<dev.min.agent.spi.SkillEntry>): FrozenDefs {
        val skills = mutableListOf<dev.min.agent.ext.config.SkillDef>()
        val commands = mutableListOf<dev.min.agent.ext.config.CommandDef>()
        val unreadable = mutableSetOf<String>()
        entries.forEach { e ->
            val body = app.readSkillBody(e.path)
            if (body == null) {
                unreadable += e.name
                return@forEach
            }
            val source = dev.min.agent.ext.config.ConfigSource.entries.firstOrNull { it.label == e.source }
                ?: dev.min.agent.ext.config.ConfigSource.User
            if (e.kind == dev.min.agent.spi.SkillKind.Command) {
                commands += dev.min.agent.ext.config.CommandDef(
                    e.name, e.description, e.path, body, source,
                    whenToUse = e.whenToUse, argumentHint = e.argumentHint, arguments = e.arguments,
                    disableModelInvocation = e.disableModelInvocation, userInvocable = e.userInvocable, context = e.context,
                )
            } else {
                skills += dev.min.agent.ext.config.SkillDef(
                    e.name, e.description, e.path, body, source,
                    whenToUse = e.whenToUse, argumentHint = e.argumentHint, arguments = e.arguments,
                    disableModelInvocation = e.disableModelInvocation, userInvocable = e.userInvocable, context = e.context,
                )
            }
        }
        return FrozenDefs(skills, commands, unreadable)
    }

    // `/名字 参数` 先展开成 skill / command 的内容再发；展不开的（未知命令等）在终端提示、不发给模型。返回是否发出去了
    suspend fun say(input: String): Boolean {
        // 斜杠路径与模型路径用同一份清单（会话冻结的快照，L8）：不读实时配置——磁盘上中途改了，两条路看到的一致
        val entries = runtime.state.value.skills?.entries.orEmpty()
        val defs = if (input.trimStart().startsWith("/")) frozenDefs(entries) else null
        if (defs != null) {
            val first = input.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().removePrefix("/")
            if (first in defs.unreadable) {
                System.err.println("skill「$first」的文件现在读不了（可能在会话开始后被删除或改动）。")
                return false
            }
        }
        val action = interpretInput(input, defs?.skills.orEmpty(), defs?.commands.orEmpty(), app.skillVars(runtime))
        val text = when (action) {
            is InputAction.Send -> action.text
            is InputAction.Notice -> {
                System.err.println(action.message)
                return false
            }
        }
        // 用户用斜杠调用 skill / command（CC 的 Hooks in skills and agents）：frontmatter hooks 从这一刻起
        // 在本会话登记（每次调用都重新登记全量），余下回合生效。模型调 Skill 工具的路在内核（KernelSkillPort），两条路同一套登记
        action.names.forEach { name ->
            entries.firstOrNull { it.name == name }?.let { e ->
                e.hooks?.let { runtime.registerComponentHooks(e.kind.name.lowercase(), name, e.source ?: "user", it) }
            }
        }
        runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(text)))
        runtime.awaitIdle()
        println()
        return true
    }

    val prompt = args.prompt
    if (prompt != null) {
        val fromSeq = runtime.state.value.lastSeq + 1
        // --json-schema：要求追加在用户消息后面，不改系统提示（任务单拍板）——提示词里带着 schema 交卷方式
        val effective = if (args.jsonSchema != null) structuredOutputPrompt(prompt) else prompt
        if (!say(effective)) return 2
        val state = runtime.state.value
        val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq), state.usage)
        result.failure?.let { System.err.println(it) }
        if (args.json) {
            println(result.json())
        } else if (args.jsonSchema != null) {
            // 没开 --json 也要把结构化答案打出来：跑批要的就是它
            result.json()["structured_output"]?.let(::println)
        }
        return if (result.completed) 0 else 1
    }

    System.err.println("输入要说的话，回车发送；/compact [侧重] 压缩上下文；/rewind 回退；/branch 分支当前会话；/rename [名字] 查看或改名；/名字 参数 调用 skill 或自定义命令；/exit 退出。")
    while (true) {
        print("\n> ")
        val line = stdin.readLine() ?: return 0
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> continue
            trimmed == "/exit" -> return 0
            trimmed == "/compact" || trimmed.startsWith("/compact ") -> {
                if (runtime.compact(trimmed.removePrefix("/compact").trim())) {
                    runtime.awaitIdle()
                    System.err.println(compactionNote(runtime.state.value))
                }
            }
            trimmed == "/output-style" || trimmed.startsWith("/output-style ") ->
                outputStyleCommand(app, runtime, trimmed.removePrefix("/output-style").trim())
            trimmed == "/rename" || trimmed.startsWith("/rename ") -> {
                // 不带参数打印当前名字（CC 的 /rename 同样）；带参数改名，空名（规整后）拒绝
                val wanted = trimmed.removePrefix("/rename").trim()
                if (wanted.isEmpty()) {
                    System.err.println(runtime.state.value.name?.let { "当前会话名字：$it" } ?: "这个会话还没有名字（/rename <名字> 起一个）")
                } else {
                    when (val renamed = runtime.rename(wanted)) {
                        null -> System.err.println("名字规整后是空的，换一个。")
                        else -> System.err.println("已改名为 $renamed")
                    }
                }
            }
            trimmed == "/branch" -> {
                // 分支并切过去继续聊：原会话一个字不动（CC 的 /branch 同样复制转录后切换写入）
                try {
                    val branched = app.branchSession(runtime)
                    ui.cancel()
                    runtime = branched
                    ui = ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope)
                    System.err.println("已切到分支 ${runtime.sessionId.value}（分支自 ${branched.state.value.branched?.from?.value}；原会话保留，可各自 --resume）")
                } catch (e: dev.min.agent.kernel.SessionBranchRefused) {
                    System.err.println(e.message)
                }
            }
            trimmed in REWIND_COMMANDS -> rewindCommand(runtime, stdin)
            trimmed.startsWith("/rewind-undo ") -> rewindUndoCommand(runtime, trimmed.removePrefix("/rewind-undo ").trim())
            else -> say(line)
        }
    }
    } finally {
        // 退出 = prompt_input_exit：SessionEnd 在会话（用户最后所在的那个，可能是分支）卸下之前跑，清理类 hook 有机会收尾
        val cleanup = app.exitSession(runtime)
        ui.cancel()
        cleanup.join()
    }
}

/** [interpretInput] 要的两组定义，外加「清单里有、正文读不了」的名字（调用处给人提示） */
private class FrozenDefs(
    val skills: List<dev.min.agent.ext.config.SkillDef>,
    val commands: List<dev.min.agent.ext.config.CommandDef>,
    val unreadable: Set<String>,
)

/** /compact 之后给人看的一句话：压缩成了没有、原文在哪 */
internal fun compactionNote(state: dev.min.agent.kernel.SessionState): String {
    val ended = state.lastTurn
    if (ended?.trigger != dev.min.agent.spi.TurnTrigger.Compact) return "没有压缩（有回合在跑）"
    val failure = (ended.outcome as? dev.min.agent.spi.TurnOutcome.Failed)?.message
    return failure ?: "已压缩较早的对话；原文在 ${state.compaction?.transcriptPath ?: "会话附件"}"
}

/**
 * `--json-schema` 时拼在用户消息后面的交卷要求（不改系统提示，任务单拍板）。schema 本身不用重复：
 * StructuredOutput 工具的入参 schema 就是它，工具表里有
 */
internal fun structuredOutputPrompt(prompt: String): String =
    "$prompt\n\n（本任务要求结构化输出：最终答案必须通过调用 StructuredOutput 工具交出——参数就是符合给定 JSON Schema 的答案，" +
        "整个任务只调用一次；需要时先用别的工具收集信息，最后调用它交卷。）"
