package dev.min.agent.tools

import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.MonitorRequest
import dev.min.agent.spi.MonitorStart
import dev.min.agent.spi.MonitorWebSocket
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 后台任务的两个工具：Monitor（起 watch）与 TaskOutput（按 id 取输出）。TaskStop 在 SubagentTools.kt（它先认后台任务，再认子 agent）。
 * 字段名照 Claude Code（tools-reference 与 Agent SDK 的 MonitorInput / TaskOutput）；说明用中文，口径照它的文档。
 * 工具只转发 [dev.min.agent.spi.BackgroundPort]：起、限流、期限、通知都在内核（BackgroundJobs）。
 */

data class MonitorInput(
    val description: String,
    val timeoutMs: Long?,
    val command: String?,
    val ws: MonitorWebSocket?,
)

/**
 * Monitor：在后台跑一个命令（或开一个 WebSocket），每一行输出（每条文本消息）作为一个事件送回会话。
 * 权限与 Bash 同一套：命令走 `Bash(…)` 的 allow / deny 规则；WebSocket 源每次都要批准（CC：弹窗不给「以后别问」）。
 * 不标只读：它起进程。
 */
object MonitorTool : Tool<MonitorInput> {
    override val name = "Monitor"
    override val traits = ToolTraits()

    /** CC：timeout_ms 接受到 3600000，生效的期限最多 1800000（内核钳）。这里只管形状 */
    const val MAX_ACCEPTED_TIMEOUT_MS = 3_600_000L

    private const val SUMMARY_CHARS = 80

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(mapOf(
            "description" to prop("string", "用一句话说明在盯什么（给用户看，也是通知里的名字）"),
            "timeout_ms" to prop("integer", "期限毫秒数，默认 300000（5 分钟），最多接受 $MAX_ACCEPTED_TIMEOUT_MS，实际最长 1800000（30 分钟）；`-p` 下最长 600000"),
            "command" to prop("string", "要在后台跑的命令，每输出一行就是一个事件。与 ws 二选一"),
            "ws" to buildJsonObject {
                put("type", "object")
                put("description", "改用 WebSocket 源：每条文本消息一个事件。与 command 二选一")
                put("properties", JsonObject(mapOf(
                    "url" to prop("string", "ws:// 或 wss:// 地址；不能带用户名密码或空白，只能是 ASCII"),
                    "protocols" to buildJsonObject {
                        put("type", "array")
                        put("description", "握手时提议的 WebSocket 子协议名，不能重复")
                        put("items", prop("string", "子协议名"))
                    },
                )))
                put("required", JsonArray(listOf(JsonPrimitive("url"))))
            },
        )))
        put("required", JsonArray(listOf(JsonPrimitive("description"))))
    }

    override fun description(profile: HarnessProfile) =
        "在后台跑一个命令（command），把它的每一行输出当作一个事件送回对话；或者开一个 WebSocket（ws），把每条文本消息当作一个事件。" +
            "你不必轮询：事件到达时会作为通知送给你，会话空闲时也会唤醒你。适合盯日志里的错误、轮询 CI / PR 状态、盯文件变化。" +
            "command 与 ws 二选一。watch 有期限：默认 5 分钟、最长 30 分钟；到期结束时你会收到一条通知，需要的话再开一个。" +
            "返回任务 id 与输出文件路径（可用 Read 读）；用 TaskStop 按 id 提前停掉。" +
            "命令用管道时请加 --line-buffered（如 `tail -f app.log | grep --line-buffered ERROR`），否则输出被缓冲、事件迟迟不来；" +
            "只打印你关心的行：事件太密时会被合并、省略（完整输出始终在文件里）。命令与 Bash 走同一套权限规则。" +
            "WebSocket 源：每条文本消息一个事件（多行消息里的换行在通知和输出文件里写成 \\n 两个字符）；二进制消息只给一行 `[binary frame, N bytes]`；" +
            "对端关闭时告诉你关闭码；单条消息超过 1 MiB 就结束 watch（请换带过滤的订阅）；每次打开都要用户批准；私网、链路本地、云元数据地址一律拒绝。"

    override fun parse(raw: JsonObject): ToolInput<MonitorInput> = parseArgs {
        val description = raw.reqString("description")
        if (description.isBlank()) throw BadArg("字段 `description` 不能为空。")
        val timeout = raw.optLong("timeout_ms", min = 1)?.also {
            if (it > MAX_ACCEPTED_TIMEOUT_MS) throw BadArg("字段 `timeout_ms` 最多 $MAX_ACCEPTED_TIMEOUT_MS（实际生效的期限最长 30 分钟）。")
        }
        val command = raw.optString("command")
        val wsRaw = raw["ws"]?.takeUnless { it is JsonNull }
        if (command != null && wsRaw != null) throw BadArg("`command` 与 `ws` 只能给一个。")
        if (command == null && wsRaw == null) throw BadArg("需要给 `command`（跑命令）或 `ws`（开 WebSocket）其中一个。")
        if (command != null && command.isBlank()) throw BadArg("字段 `command` 不能为空。")
        MonitorInput(description, timeout, command, wsRaw?.let(::parseWs))
    }

    private fun parseWs(element: JsonElement): MonitorWebSocket {
        val obj = element as? JsonObject ?: throw BadArg("字段 `ws` 必须是对象 {url, protocols?}。")
        val url = obj.reqString("url").also(::checkWsUrl)
        val protocols = obj["protocols"]?.takeUnless { it is JsonNull }?.let { p ->
            val arr = p as? JsonArray ?: throw BadArg("`ws.protocols` 必须是字符串数组。")
            arr.map { e ->
                val s = e as? JsonPrimitive
                if (s == null || !s.isString) throw BadArg("`ws.protocols` 的每一项必须是字符串。")
                s.content
            }
        }.orEmpty()
        protocols.forEach { if (!SUBPROTOCOL_TOKEN.matches(it)) throw BadArg("`ws.protocols` 里的「$it」不是合法的子协议名（只允许 RFC 6455 的 token 字符）。") }
        if (protocols.toSet().size != protocols.size) throw BadArg("`ws.protocols` 不能有重复项。")
        return MonitorWebSocket(url, protocols)
    }

    /** CC：必须是 ws:// 或 wss://，不带凭据与空白，只用 ASCII。主机解析到私网等地址的拒绝在打开连接时由宿主判（需要 DNS） */
    private fun checkWsUrl(url: String) {
        if (url.any { it.code > 0x7E || it.code < 0x21 }) throw BadArg("`ws.url` 不能含空白或非 ASCII 字符。")
        val lower = url.lowercase()
        if (!lower.startsWith("ws://") && !lower.startsWith("wss://")) throw BadArg("`ws.url` 必须以 ws:// 或 wss:// 开头。")
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) throw BadArg("`ws.url` 不能带用户名或密码。")
        if (authority.isEmpty() || authority.startsWith(':')) throw BadArg("`ws.url` 缺少主机。")
    }

    override fun permissionAsk(input: MonitorInput, ctx: ToolContext): PermissionAsk =
        if (input.command != null) {
            // 命令按 Bash 的规则判（allow / deny 的 Bash 模式对它同样生效）：命令放进 commands，规则匹配只看命令
            PermissionAsk(name, input.description.clip(SUMMARY_CHARS), commands = listOf(CommandAccess(raw = input.command)))
        } else {
            // WebSocket 源不填 domains：WebFetch(domain:…) 的 allow 规则不能顺手放行一条长连接（CC：每次都弹窗）
            PermissionAsk(name, "打开 WebSocket：${input.ws!!.url}".clip(SUMMARY_CHARS + 40))
        }

    override fun run(input: MonitorInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        val start = ctx.background.startMonitor(
            MonitorRequest(input.description, input.timeoutMs, input.command, input.ws),
        )
        when (start) {
            is MonitorStart.Rejected -> failResult(start.messageForModel)
            is MonitorStart.Started -> okResult(buildString {
                append("已启动 watch「").append(input.description).append("」，任务 id ").append(start.taskId).append("。")
                start.timeoutMs?.let { append("期限 ").append(durationText(it)).append("，到期会收到一条通知。") }
                append("每个事件会作为通知送给你，不用轮询。")
                start.outputPath?.let { append("输出也写在 ").append(it.value).append("（用 Read 查看）。") }
                append("用 TaskStop 停掉它。")
            })
        }
    }

    private fun durationText(ms: Long): String = when {
        ms >= 60_000 && ms % 60_000 == 0L -> "${ms / 60_000} 分钟"
        ms >= 1_000 -> "${ms / 1_000} 秒"
        else -> "$ms 毫秒"
    }

    /** RFC 6455 §4.1 的子协议名是 RFC 2616 的 token */
    private val SUBPROTOCOL_TOKEN = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
}

data class TaskOutputInput(val taskId: String, val block: Boolean, val timeoutMs: Long)

/**
 * TaskOutput：按 id 取后台任务的输出。CC 把它标为已弃用、推荐直接对输出文件路径用 Read——所以开跑时就把路径告诉模型
 * （Bash 的回执、Monitor 的回执），这个工具只为习惯它的模型和「要等任务跑完」的场合保留。
 */
object TaskOutputTool : Tool<TaskOutputInput> {
    override val name = "TaskOutput"
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true)

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 600_000L

    override val inputSchema: JsonObject = schemaObject(
        listOf("task_id"),
        "task_id" to prop("string", "后台任务 id（Bash 转后台时给的 id，或 Monitor 返回的任务 id）"),
        "block" to prop("boolean", "任务还在跑时是否等它结束，默认 true"),
        "timeout" to prop("integer", "等多久，毫秒，默认 $DEFAULT_TIMEOUT_MS，最大 $MAX_TIMEOUT_MS"),
    )

    override fun description(profile: HarnessProfile) =
        "按 task_id 取后台任务（转后台的 Bash 命令、Monitor 的 watch）的状态与输出末尾。已弃用：优先直接用 Read 读任务的输出文件路径" +
            "（开跑时回执里给了），可以分段、可以反复读。block=true（默认）时任务还在跑就等它结束，最多 timeout 毫秒。" +
            "找不到 id 时，错误里会列出正在跑的后台任务和 agent（id + 描述）。"

    override fun parse(raw: JsonObject): ToolInput<TaskOutputInput> = parseArgs {
        val id = raw.reqString("task_id")
        if (id.isBlank()) throw BadArg("字段 `task_id` 不能为空。")
        TaskOutputInput(
            id.trim(),
            raw.optBool("block") ?: true,
            (raw.optLong("timeout", min = 0) ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS),
        )
    }

    override fun permissionAsk(input: TaskOutputInput, ctx: ToolContext) = PermissionAsk(name, "读取任务 ${input.taskId} 的输出")

    override fun run(input: TaskOutputInput, ctx: ToolContext): Flow<ToolProgress> =
        doneFlow { ctx.background.taskOutput(input.taskId, input.block, input.timeoutMs) }
}
