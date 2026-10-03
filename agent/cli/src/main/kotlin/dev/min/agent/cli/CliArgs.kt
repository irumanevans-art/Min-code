package dev.min.agent.cli

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.Wire
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import java.nio.file.Path

/** 非交互时审批怎么答：交互模式默认问人 */
enum class ApprovalAnswer { Ask, Yes, No }

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
    val json: Boolean,
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
)

class UsageException(message: String) : Exception(message)

const val USAGE = """用法：min-agent --model <模型 id> [选项] [-p <提示>]

  -p <提示>              非交互：跑完这一句就退出；不给则进入交互模式
  --provider <id>        模型目录里的供应商 id，默认 anthropic
  --model <id>           模型 id（必填）
  --base-url <url>       覆盖供应商地址（协议路径之前那一段）
  --wire <协议>         anthropic / openai_chat / openai_responses；自定义供应商可现场指定
  --auth x-api-key|bearer  凭证放哪个头，默认按供应商
  --api-key-env <名字>   从哪个环境变量读凭证，默认按供应商（如 ANTHROPIC_API_KEY）
  --context <n>          覆盖目录上下文窗口；自定义模型默认 200000
  --max-output <n>       覆盖目录输出上限；自定义模型默认 8192
  --effort <档位>        off / minimal / low / medium / high / xhigh / max
  --mode <模式>          default（默认）/ acceptEdits / plan / bypassPermissions / dontAsk
  --approve ask|yes|no   审批怎么答：交互模式默认 ask，非交互默认 no
  --cwd <目录>           工作目录，默认当前目录
  --json                 非交互时最后输出一行 JSON 结果（评测用）
  --resume <会话 id>     接着一个已有会话
  --trust                信任当前项目：执行项目 .claude/ 设置里的 hooks（默认只执行用户级与托管的）
  --search-backend <id>  brave / tavily / searxng；也可用 MIN_AGENT_SEARCH_BACKEND
  --search-url <url>     SearXNG 基础地址，也可用 MIN_AGENT_SEARXNG_URL
  --fast-model <id>      提取模型，同一供应商；默认会话模型
  --max-web-searches <n> 根会话及其子 agent 的搜索上限，默认 200
  --web-cache-ttl-ms <n> 网页缓存毫秒，默认 900000；0 关闭缓存
  --web-deadline-ms <n>  下载总截止毫秒，默认 300000；0 取消截止
"""

fun parseArgs(argv: Array<String>, cwdDefault: Path): CliArgs {
    val values = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < argv.size) {
        val arg = argv[i]
        when (arg) {
            "--json", "--trust" -> flags += arg
            "-h", "--help" -> throw UsageException(USAGE)
            else -> {
                if (!arg.startsWith("-")) throw UsageException("不认识的参数：$arg\n\n$USAGE")
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

    val prompt = values["-p"]
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
        json = "--json" in flags,
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
