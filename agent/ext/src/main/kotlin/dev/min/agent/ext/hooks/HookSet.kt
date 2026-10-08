package dev.min.agent.ext.hooks

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.HookSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class HookHandler(
    val event: HookEvent,
    val matcher: String?,
    val type: String,
    val command: String?,
    val args: List<String>?,
    val timeout: Int?,
    val condition: String?,
    val source: ConfigSource,
    val raw: JsonObject,
    /** type:http 专属：POST 地址；其余类型为 null */
    val url: String? = null,
    /** type:http 专属：值里可带 $VAR 插值的附加头；值必须是字符串 */
    val headers: JsonObject? = null,
    /** type:http 专属：允许插值进头里的环境变量名 */
    val allowedEnvVars: List<String>? = null,
    /** `async: true`：后台跑、不挡回合（CC 只给 command 定义了这个语义） */
    val async: Boolean = false,
    /** `asyncRewake: true`：后台跑，退出码 2 时唤醒模型 */
    val asyncRewake: Boolean = false,
    /** `once: true`：成功跑一次后移除。CC 只在 skill frontmatter 里认它，settings 与 agent 定义里忽略——是否响应由执行方（内核）按来源决定 */
    val once: Boolean = false,
    val pluginEnv: Map<String, String> = if (source == ConfigSource.Plugin) (raw["minPluginEnv"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap().orEmpty() else emptyMap(),
) {
    val executable: Boolean get() = type == "command" && !async && !asyncRewake

    // async / asyncRewake / once 进身份：同一条命令可以既有同步版又有异步版，漏了会在去重时互吞
    internal fun identity(): List<Any?> = listOf(type, command, args, condition, timeout, async, asyncRewake, once, pluginEnv) +
        if (type == "http") listOf(url, headers, allowedEnvVars) else emptyList()
}

data class HookMatch(val handlers: List<HookHandler>, val notes: List<String>)

/** 保留不同事件和 matcher 的绑定，避免去重把另一触发时机悄悄删掉。 */
class HookSet private constructor(val handlers: List<HookHandler>, val notes: List<String>) {
    fun matching(event: HookEvent, target: String?): List<HookHandler> = matchingDetailed(event, target).handlers

    /** 调用方闭包携带工具入参；这里不认识权限引擎，也不猜测 if 规则。 */
    fun matchingDetailed(
        event: HookEvent,
        target: String?,
        ifMatches: ((rule: String) -> Boolean)? = null,
    ): HookMatch {
        val matchNotes = mutableListOf<String>()
        val selected = handlers.filter { handler ->
            if (handler.event != event || !matchesHookTarget(event, handler.matcher, target)) return@filter false
            val where = "${handler.source.label} $event ${handler.command ?: handler.type}"
            when {
                handler.type != "command" -> {
                    // 同步批只跑 command：prompt / agent / mcp_tool 本期不执行；http 由 matchingHttp 另挑
                    matchNotes += if (handler.type == "http") "$where：http 处理器走 http 通道，不在本批"
                    else "$where：本期不执行此 hook 类型"
                    false
                }
                handler.async || handler.asyncRewake -> {
                    // 异步 command 不进同步批：由内核的后台执行器另跑（不挡回合）
                    matchNotes += "$where：异步 hook 由后台执行器跑，不在本批"
                    false
                }
                handler.condition != null && event in IF_EVENTS -> {
                    if (ifMatches == null) {
                        matchNotes += "$where：未注入 if 判定，跳过处理器"
                        false
                    } else ifMatches(handler.condition)
                }
                else -> true
            }
        }
        return HookMatch(dedupByIdentity(selected), matchNotes)
    }

    /**
     * 挑 `async` / `asyncRewake` 的 command 处理器（CC：这两个字段只对 type:command 有语义）。
     * 与同步批分开挑：它们不参与「本批怎么合并结论」，由内核的后台执行器各自跑、各自落记录
     */
    fun matchingAsync(
        event: HookEvent,
        target: String?,
        ifMatches: ((rule: String) -> Boolean)? = null,
    ): HookMatch {
        val selected = handlers.filter { handler ->
            if (handler.event != event || !matchesHookTarget(event, handler.matcher, target)) return@filter false
            if (handler.type != "command" || !(handler.async || handler.asyncRewake)) return@filter false
            // 与同步批同一条规矩：if 条件没人替它判定（has() 之外的路径）就不跑，宁可漏跑也不瞎猜
            if (handler.condition != null && event in IF_EVENTS) {
                ifMatches != null && ifMatches(handler.condition)
            } else true
        }
        return HookMatch(dedupByIdentity(selected), emptyList())
    }

    /**
     * 同一套绑定、`if` 与去重，只挑 `type:http` 的处理器（设计 §8 H3）。与 [matchingDetailed] 分开：
     * 现有内核按 `executable` 只跑 command，这里换路子不会改变它的行为。异步 http 本期不执行。
     */
    fun matchingHttp(
        event: HookEvent,
        target: String?,
        ifMatches: ((rule: String) -> Boolean)? = null,
    ): HookMatch {
        val matchNotes = mutableListOf<String>()
        val selected = handlers.filter { handler ->
            if (handler.event != event || !matchesHookTarget(event, handler.matcher, target)) return@filter false
            val where = "${handler.source.label} $event ${handler.url ?: handler.type}"
            when {
                handler.type != "http" -> false
                rawAsync(handler) -> {
                    matchNotes += "$where：http 的 async 形态本期不执行"
                    false
                }
                handler.condition != null && event in IF_EVENTS -> {
                    if (ifMatches == null) {
                        matchNotes += "$where：未注入 if 判定，跳过处理器"
                        false
                    } else ifMatches(handler.condition)
                }
                else -> true
            }
        }
        return HookMatch(dedupByIdentity(selected), matchNotes)
    }

    /** 同一命令可绑定多个 matcher；本次命中多个绑定时仍只执行一次。 */
    private fun dedupByIdentity(selected: List<HookHandler>): List<HookHandler> {
        val unique = linkedMapOf<List<Any?>, HookHandler>()
        selected.forEach { h ->
            val old = unique[h.identity()]
            if (old == null || priority(h.source) > priority(old.source)) unique[h.identity()] = h
        }
        return unique.values.toList()
    }

    companion object {
        fun parse(sources: List<HookSource>): HookSet {
            val notes = mutableListOf<String>()
            val unique = linkedMapOf<List<Any?>, HookHandler>()
            sources.forEach { layer ->
                layer.hooks.forEach eventLoop@{ (name, value) ->
                    val event = HookEvent.entries.firstOrNull { it.name == name }
                    if (event == null) {
                        notes += "${layer.source.label} $name：未知事件，跳过"
                        return@eventLoop
                    }
                    val groups = value as? JsonArray
                    if (groups == null) {
                        notes += "${layer.source.label} $name：事件配置必须是数组，跳过"
                        return@eventLoop
                    }
                    groups.forEachIndexed groupLoop@{ gi, groupValue ->
                        val where = "${layer.source.label} $name 第 ${gi + 1} 组"
                        val group = groupValue as? JsonObject
                        val hooks = group?.get("hooks") as? JsonArray
                        val matcher = group?.get("matcher")
                        if (hooks == null || (matcher != null && stringOf(matcher) == null)) {
                            notes += "$where：hooks 必须是数组且 matcher 必须是字符串，跳过"
                            return@groupLoop
                        }
                        val matcherText = matcher?.let(::stringOf)
                        if (event in MATCHER_EVENTS && !validMatcher(event, matcherText)) {
                            notes += "$where：matcher 正则无效，跳过"
                            return@groupLoop
                        }
                        hooks.forEachIndexed { hi, hook ->
                            try {
                                val raw = hook as? JsonObject ?: error("处理器必须是对象")
                                fun text(key: String): String? = if (key !in raw) null else
                                    stringOf(raw[key]) ?: error("$key 必须是字符串")
                                val type = text("type") ?: error("缺少 type")
                                require(type in setOf("command", "http", "prompt", "agent", "mcp_tool")) { "未知 type: $type" }
                                val command = text("command")
                                val args = raw["args"]?.let { a ->
                                    (a as? JsonArray ?: error("args 必须是字符串数组")).map {
                                        stringOf(it) ?: error("args 必须是字符串数组")
                                    }.also { require(it.isNotEmpty() || !command.isNullOrBlank()) { "args 不能为空" } }
                                }
                                val timeout = raw["timeout"]?.let {
                                    val p = it as? JsonPrimitive
                                    val seconds = p?.takeUnless { v -> v.isString }?.intOrNull
                                    require(seconds != null && seconds > 0) { "timeout 必须是正整数秒" }
                                    seconds
                                }
                                val condition = text("if")
                                if (type == "command") {
                                    require(!command.isNullOrBlank() || args != null) { "command 或 args 至少需要一项" }
                                    require(command == null || command.isNotBlank()) { "command 不能是空字符串" }
                                    require(args == null || command != null || args.first().isNotBlank()) { "args 可执行文件名不能为空" }
                                }
                                var url: String? = null
                                var headers: JsonObject? = null
                                var allowedEnvVars: List<String>? = null
                                if (type == "http") {
                                    url = text("url")?.takeUnless { it.isBlank() } ?: error("http 处理器缺少 url")
                                    headers = raw["headers"]?.let { h ->
                                        (h as? JsonObject ?: error("headers 必须是对象")).also { obj ->
                                            obj.forEach { (k, v) ->
                                                require(stringOf(v) != null) { "headers.$k 的值必须是字符串" }
                                            }
                                        }
                                    }
                                    allowedEnvVars = raw["allowedEnvVars"]?.let { a ->
                                        (a as? JsonArray ?: error("allowedEnvVars 必须是字符串数组")).map {
                                            stringOf(it) ?: error("allowedEnvVars 必须是字符串数组")
                                        }
                                    }
                                }
                                listOf("async", "asyncRewake", "once").forEach { key ->
                                    require(key !in raw || raw[key] == JsonPrimitive(true) || raw[key] == JsonPrimitive(false)) {
                                        "$key 必须是布尔值"
                                    }
                                }
                                val h = HookHandler(event, matcherText, type, command, args, timeout, condition,
                                    layer.source, raw, url, headers, allowedEnvVars,
                                    async = raw["async"] == JsonPrimitive(true),
                                    asyncRewake = raw["asyncRewake"] == JsonPrimitive(true),
                                    once = raw["once"] == JsonPrimitive(true))
                                val key = listOf(event, matcherText) + h.identity()
                                val old = unique[key]
                                if (old == null || priority(h.source) > priority(old.source)) unique[key] = h
                            } catch (e: IllegalArgumentException) {
                                notes += "$where 第 ${hi + 1} 项：${e.message}，跳过"
                            } catch (e: IllegalStateException) {
                                notes += "$where 第 ${hi + 1} 项：${e.message}，跳过"
                            }
                        }
                    }
                }
            }
            return HookSet(unique.values.toList(), notes.toList())
        }
    }
}

private fun stringOf(value: kotlinx.serialization.json.JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content

/** async / asyncRewake 只对 command hook 有文档语义；http 挂了异步标记时同样不执行 */
private fun rawAsync(handler: HookHandler): Boolean = handler.async || handler.asyncRewake

private fun priority(source: ConfigSource): Int = when (source) {
    ConfigSource.User -> 0
    ConfigSource.Project -> 1
    ConfigSource.Local -> 2
    ConfigSource.Plugin -> 0
    ConfigSource.Managed -> 3
}

internal val IF_EVENTS = setOf(HookEvent.PreToolUse, HookEvent.PostToolUse, HookEvent.PostToolUseFailure,
    HookEvent.PermissionRequest, HookEvent.PermissionDenied)

internal val MATCHER_EVENTS = setOf(
    HookEvent.SessionStart, HookEvent.Setup, HookEvent.InstructionsLoaded, HookEvent.UserPromptExpansion,
    HookEvent.PreToolUse, HookEvent.PermissionRequest, HookEvent.PermissionDenied, HookEvent.PostToolUse,
    HookEvent.PostToolUseFailure, HookEvent.Notification, HookEvent.SubagentStart, HookEvent.SubagentStop,
    HookEvent.StopFailure, HookEvent.ConfigChange, HookEvent.DirectoryAdded, HookEvent.FileChanged,
    HookEvent.PreCompact, HookEvent.PostCompact, HookEvent.PreModelSwitch, HookEvent.PostModelSwitch,
    HookEvent.Elicitation, HookEvent.ElicitationResult, HookEvent.SessionEnd,
)

private fun exactMatcher(event: HookEvent, matcher: String): Boolean =
    Regex(if (event == HookEvent.FileChanged || event == HookEvent.StopFailure) "[A-Za-z0-9_|]+" else
        "[A-Za-z0-9_ ,|\\-]+").matches(matcher)

private fun validMatcher(event: HookEvent, matcher: String?): Boolean = try {
    if (!matcher.isNullOrEmpty() && matcher != "*" && !exactMatcher(event, matcher)) Regex(matcher)
    true
} catch (_: IllegalArgumentException) { false }

internal fun matchesHookTarget(event: HookEvent, matcher: String?, target: String?): Boolean {
    if (event !in MATCHER_EVENTS || matcher.isNullOrEmpty() || matcher == "*") return true
    if (target == null && event == HookEvent.PreModelSwitch) return true
    val text = target.orEmpty()
    return if (exactMatcher(event, matcher)) matcher.split('|', ',').any { it.trim() == text }
    else try { Regex(matcher).containsMatchIn(text) } catch (_: IllegalArgumentException) { false }
}
