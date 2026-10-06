package dev.min.agent.cli

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.Wire
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import java.nio.file.Path

/** 非交互时审批怎么答：交互模式默认问人 */
enum class ApprovalAnswer { Ask, Yes, No }

/** `--output-format`（CC :110）：text（默认，stdout 只出最终回复文本）/ json（一行结果对象）/ stream-json（逐行事件流） */
enum class OutputFormat { Text, Json, StreamJson }

data class CliArgs(
    val prompt: String?,
    val provider: String,
    val model: String,
    val baseUrl: String?,
    val auth: AuthScheme?,
    val apiKeyEnv: String?,
    val contextWindow: Int?,
    val maxOutput: Int?,
    val effort: Effort?,
    val mode: PermissionMode,
    val approve: ApprovalAnswer,
    val cwd: Path,
    /** `--json` 是 `--output-format json` 的别名（CC 同名；不报废弃） */
    val outputFormat: OutputFormat = OutputFormat.Text,
    val resume: String?,
    val wire: Wire? = null,
    /** 信任当前项目：项目层（.claude/settings.json / settings.local.json）里的 hooks 才会执行（hooks设计 §1） */
    val trust: Boolean = false,
    val searchBackend: String? = null,
    val searchUrl: String? = null,
    val fastModel: String? = null,
    val maxWebSearches: Int = 200,
    val webCacheTtlMs: Long = 900_000,
    val webDeadlineMs: Long = 300_000,
    /** -p 一次运行里模型请求的次数上限；null = 没给（再看 CLAUDE_CODE_MAX_TURNS） */
    val maxTurns: Int? = null,
    /** -p 模式下的结构化输出 schema（--json-schema 的原文，@文件 已在读入时展开）；null = 不用 */
    val jsonSchema: kotlinx.serialization.json.JsonObject? = null,
    /** --resume / --continue 时在分支上跑（原会话不动） */
    val forkSession: Boolean = false,
    /** --continue：接着当前目录最近一次根会话 */
    val continueMostRecent: Boolean = false,
    /** 声明模型能看图（目录外的自定义模型默认不能，图片会换成文字说明）；目录里已标能看图的不受影响 */
    val images: Boolean = false,
    /** --include-partial-messages（CC :99）：stream-json 额外输出 text 增量的 stream_event 行 */
    val includePartialMessages: Boolean = false,
    /** --forward-subagent-text（CC :93）：stream-json 里转发子 agent 的消息行（带 parent_tool_use_id） */
    val forwardSubagentText: Boolean = false,
) {
    /** 既有调用点的便利读法：`--json` 与 `--output-format json` 等价 */
    val json: Boolean get() = outputFormat == OutputFormat.Json
}

class UsageException(message: String) : Exception(message)

const val USAGE = """用法：min-agent --model <模型 id> [选项] [-p <提示>]

  -p <提示>              非交互：跑完这一句就退出；不给则进入交互模式（长形 --print）
  --provider <id>        模型目录里的供应商 id，默认 anthropic
  --model <id>           模型 id（必填）
  --base-url <url>       覆盖供应商地址（协议路径之前那一段）
  --wire <协议>         anthropic / openai_chat / openai_responses；自定义供应商可现场指定
  --auth x-api-key|bearer  凭证放哪个头，默认按供应商
  --api-key-env <名字>   从哪个环境变量读凭证，默认按供应商（如 ANTHROPIC_API_KEY）
  --context <n>          覆盖目录上下文窗口；自定义模型默认 200000
  --max-output <n>       覆盖目录输出上限；自定义模型默认 8192
  --images               声明模型能看图；自定义模型默认不能（图片换成文字说明发过去）
  --effort <档位>        off / minimal / low / medium / high / xhigh / max
  --mode <模式>          default（默认）/ acceptEdits / plan / bypassPermissions / dontAsk
  --approve ask|yes|no   审批怎么答：交互模式默认 ask，非交互默认 no
  --cwd <目录>           工作目录，默认当前目录
  --output-format <格式> -p 的输出格式：text（默认，stdout 只出最终回复文本）/ json（一行结果对象）/ stream-json（逐行事件流）
  --json                 --output-format json 的别名
  --include-partial-messages stream-json 加输出 text 增量的 stream_event 行
  --forward-subagent-text stream-json 转发子 agent 的消息行（带 parent_tool_use_id）
  --resume <会话 id>     接着一个已有会话
  --trust                信任当前项目：执行项目 .claude/ 设置里的 hooks（默认只执行用户级与托管的）
  --search-backend <id>  brave / tavily / searxng；也可用 MIN_AGENT_SEARCH_BACKEND
  --search-url <id>      SearXNG 基础地址，也可用 MIN_AGENT_SEARXNG_URL
  --fast-model <id>      提取模型，同一供应商；默认会话模型
  --max-web-searches <n> 根会话及其子 agent 的搜索上限，默认 200
  --web-cache-ttl-ms <n> 网页缓存毫秒，默认 900000；0 关闭缓存
  --web-deadline-ms <n>  下载总截止毫秒，默认 300000；0 取消截止
  --max-turns <n>        -p 一次运行里模型请求的次数上限；没给时读 CLAUDE_CODE_MAX_TURNS
  --json-schema <JSON|@文件> -p 模式下要求模型按 JSON Schema 交结构化答案（经 StructuredOutput 工具）
  --fork-session         与 --resume / --continue 一起用：在分支上跑，原会话不动
  --continue             接着当前目录最近一次根会话（跳过 -p 跑的；相当于按目录挑 --resume）
"""

/** 带值参数与开关的合法清单：不在名单里的一律报错——静默吞掉写错的参数比停下来更危险（样本 01/03 的教训） */
private val VALUE_ARGS = setOf(
    "-p", "--print", "--provider", "--model", "--base-url", "--wire", "--auth", "--api-key-env", "--context",
    "--max-output", "--effort", "--mode", "--approve", "--cwd", "--output-format", "--resume", "--search-backend",
    "--search-url", "--fast-model", "--max-web-searches", "--web-cache-ttl-ms", "--web-deadline-ms", "--max-turns",
    "--json-schema",
)
private val FLAG_ARGS = setOf(
    "--json", "--trust", "--fork-session", "--continue", "--images",
    "--include-partial-messages", "--forward-subagent-text",
)

fun parseArgs(argv: Array<String>, cwdDefault: Path): CliArgs {
    val values = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < argv.size) {
        val arg = argv[i]
        when {
            arg in FLAG_ARGS -> flags += arg
            "-h" == arg || "--help" == arg -> throw UsageException(USAGE)
            arg !in VALUE_ARGS -> throw UsageException("不认识的参数：$arg\n\n$USAGE")
            else -> {
                val value = argv.getOrNull(i + 1) ?: throw UsageException("$arg 缺少取值\n\n$USAGE")
                values[arg] = value
                i++
            }
        }
        i++
    }
    fun int(name: String) = values[name]?.let { value ->
        value.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("$name 必须是正整数")
    }

    // --print 是 -p 的长形（CC :116）：同一个提示词，两边都给是写错了
    if (values["-p"] != null && values["--print"] != null) throw UsageException("-p 与 --print 只能给一个")
    val prompt = values["-p"] ?: values["--print"]
    val outputFormat = when {
        "--json" in flags && values["--output-format"] != null ->
            // --json 是 --output-format json 的别名：同时给且不一致是写错了
            if (values["--output-format"] == "json") OutputFormat.Json
            else throw UsageException("--json 是 --output-format json 的别名，与 --output-format ${values["--output-format"]} 冲突")
        "--json" in flags -> OutputFormat.Json
        else -> values["--output-format"]?.let { v ->
            when (v) {
                "text" -> OutputFormat.Text
                "json" -> OutputFormat.Json
                "stream-json" -> OutputFormat.StreamJson
                else -> throw UsageException("--output-format 只能是 text、json 或 stream-json")
            }
        } ?: OutputFormat.Text
    }
    if (outputFormat != OutputFormat.Text && prompt == null) {
        throw UsageException("--output-format 只在 -p 非交互模式下可用（它决定的是 -p 的输出形状）")
    }
    if (flags.contains("--include-partial-messages") && (prompt == null || outputFormat != OutputFormat.StreamJson)) {
        throw UsageException("--include-partial-messages 要与 -p 和 --output-format stream-json 一起用")
    }
    if (flags.contains("--forward-subagent-text") && (prompt == null || outputFormat != OutputFormat.StreamJson)) {
        throw UsageException("--forward-subagent-text 要与 -p 和 --output-format stream-json 一起用")
    }
    if (values["--json-schema"] != null && prompt == null) {
        throw UsageException("--json-schema 只在 -p 非交互模式下可用（结构化输出是给跑批要结果的，不是给交互会话的）")
    }
    if ("--fork-session" in flags && values["--resume"] == null && "--continue" !in flags) {
        throw UsageException("--fork-session 要与 --resume 或 --continue 一起用")
    }
    if (values["--resume"] != null && "--continue" in flags) {
        throw UsageException("--resume 与 --continue 只能给一个")
    }
    fun nonNegativeLong(name: String, default: Long): Long = values[name]?.let {
        it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw UsageException("$name 必须是非负整数")
    } ?: default
    return CliArgs(
        prompt = prompt,
        provider = values["--provider"] ?: "anthropic",
        model = values["--model"] ?: throw UsageException("缺少 --model\n\n$USAGE"),
        baseUrl = values["--base-url"],
        auth = values["--auth"]?.let {
            when (it) {
                "x-api-key" -> AuthScheme.XApiKey
                "bearer" -> AuthScheme.Bearer
                else -> throw UsageException("--auth 只能是 x-api-key 或 bearer")
            }
        },
        apiKeyEnv = values["--api-key-env"],
        contextWindow = int("--context"),
        maxOutput = int("--max-output"),
        effort = values["--effort"]?.let { v ->
            Effort.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: throw UsageException("不认识的 --effort：$v")
        },
        mode = values["--mode"]?.let { v ->
            PermissionMode.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
                ?: throw UsageException("不认识的 --mode：$v")
        } ?: PermissionMode.Default,
        approve = values["--approve"]?.let { v ->
            ApprovalAnswer.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
                ?: throw UsageException("--approve 只能是 ask、yes、no")
        } ?: if (prompt == null) ApprovalAnswer.Ask else ApprovalAnswer.No,
        cwd = values["--cwd"]?.let { Path.of(it) } ?: cwdDefault,
        outputFormat = outputFormat,
        trust = "--trust" in flags,
        resume = values["--resume"],
        searchBackend = values["--search-backend"]?.also {
            if (it !in listOf("brave", "tavily", "searxng")) throw UsageException("--search-backend 只能是 brave、tavily 或 searxng")
        },
        searchUrl = values["--search-url"],
        fastModel = values["--fast-model"],
        maxWebSearches = int("--max-web-searches") ?: 200,
        webCacheTtlMs = nonNegativeLong("--web-cache-ttl-ms", 900_000),
        webDeadlineMs = nonNegativeLong("--web-deadline-ms", 300_000),
        maxTurns = int("--max-turns"),
        jsonSchema = values["--json-schema"]?.let(::readJsonSchemaArg),
        forkSession = "--fork-session" in flags,
        continueMostRecent = "--continue" in flags,
        images = "--images" in flags,
        includePartialMessages = "--include-partial-messages" in flags,
        forwardSubagentText = "--forward-subagent-text" in flags,
        wire = values["--wire"]?.let {
            when (it) {
                "anthropic" -> Wire.AnthropicMessages
                "openai_chat" -> Wire.OpenAIChat
                "openai_responses" -> Wire.OpenAIResponses
                else -> throw UsageException("--wire 只能是 anthropic、openai_chat 或 openai_responses")
            }
        },
    )
}

/**
 * `--json-schema` 的取值：要么是 schema 的 JSON 文本，要么 `@文件路径`（内容整份当 schema）。
 * 不是合法的 JSON 对象就在启动时报错退出——schema 错了跑下去也没有意义（CC 同样在启动时校验）
 */
private fun readJsonSchemaArg(raw: String): kotlinx.serialization.json.JsonObject {
    val text = if (raw.startsWith("@")) {
        val file = Path.of(raw.removePrefix("@"))
        try {
            java.nio.file.Files.readString(file)
        } catch (e: java.io.IOException) {
            throw UsageException("读不了 --json-schema 的文件 ${raw.removePrefix("@")}：${e.message}")
        }
    } else raw
    val parsed = try {
        dev.min.agent.protocol.AgentJson.parseToJsonElement(text)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw UsageException("--json-schema 不是合法的 JSON：${e.message?.lineSequence()?.firstOrNull()}")
    }
    return parsed as? kotlinx.serialization.json.JsonObject
        ?: throw UsageException("--json-schema 必须是一个 JSON 对象（JSON Schema），实际是 ${parsed::class.simpleName}")
}

/**
 * 根会话的请求次数上限：`--max-turns` 优先；没给时读 `CLAUDE_CODE_MAX_TURNS`（经内核同一个环境读取口，
 * 模型 key 那类被藏起来的变量在这里也读不到）。环境变量不是正整数就在启动时报错退出（CC 原文），
 * 而不是当作没有上限——静默忽略一个写错的限制比停下来更危险
 */
internal fun resolveMaxTurns(flag: Int?, env: (String) -> String?): Int? {
    flag?.let { return it }
    val value = env("CLAUDE_CODE_MAX_TURNS") ?: return null
    return value.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: throw UsageException("CLAUDE_CODE_MAX_TURNS 必须是正整数，实际是 \"$value\"")
}
