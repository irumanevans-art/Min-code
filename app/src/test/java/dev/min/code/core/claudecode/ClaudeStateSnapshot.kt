package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.escapedPaths
import dev.min.code.core.session.touchesDeviceStorage
import dev.min.code.ui.session.effortSupportedFor
import dev.min.code.ui.session.permissionOriginOf
import java.io.File

/*
 * ClaudeCodeManager.SessionState 的「稳定文本快照」（M2-B 的 B0）。
 *
 * 用途：B4 的 `ClaudeStateMapper` 黄金测试、B6 换掉 ClaudeCodeVM 之后的对拍，都拿这份快照当基准 ——
 * 「每个字段 == 快照里 Page 读到的值」。所以这里渲染的是**会话页实际读到的那些字段**
 * （设计 §3.4 的 Claude Code 一列，外加页面上现算的几样：子 agent 线程、权限来源、路径越界提示、effort 支持），
 * 不是整个 data class 的 toString。
 *
 * ## 格式
 * 一行一个 `字段名: 值`，顺序固定（先会话级字段，再 turn、设置、用量，最后是列表：权限队列 / 任务 / 条目 / 线程）。
 * 字符串一律带双引号并转义换行，`null` 不带引号，所以 `""` 与 `null` 看得出区别。列表项用 `名[下标]`，
 * 嵌套（工具卡的 subItems）用 `.` 往下接。
 *
 * ## 规范化规则（凡是每次跑都会变的值都在这里收口，加新字段先想一遍它会不会变）
 * 1. **墙钟时间**（`turnStartedAt`、`lastTurnFinishedAt`、`AssistantText.finishedAt`、`TaskInfo.startedAt/endedAt`）
 *    只留有没有：非空渲染成 `<ts>`，空渲染成 `null`。
 * 1b. **stderr 条目**（`ProcessOutput`）单列一节 `processOutput[]`，不占 `items[]` 的位置：它由单独的读线程插入，
 *    相对 stdout 帧派生条目的先后每次跑都可能不同。
 * 2. **随机 id**：`ChatItem.id`（`newId()`）根本不打印，条目靠位置与 `toolUseId`（来自 CLI 帧）认；
 *    `options.newSessionId` / `options.resumeSessionId`（注册表或启动器随机生成）渲染成 `<set>` / `null`。
 * 3. **临时目录**：脚手架每次建一个随机的 `ccm-test…` 目录，值里出现的它的路径一律换成 `<tmp>`。
 * 3b. **启动 id**：启动器拿到的会话 id（`newSessionId` 或随机 UUID）在 CLI 的 system/init 到之前就是 `sessionId`，
 *    值里出现的它一律换成 `<launch-id>`；init 之后是夹具里录到的 id，原样打印。
 * 4. 夹具里录到的东西（CLI 会话 id、工具调用 id、权限请求 id、时长、token 数）**不**规范化 ——
 *    它们每次回放都一样，改动它们（重录夹具）本来就该让快照报警。
 *
 * 流式缓冲 `streamingText / streamingThinking / outputTokensCurrent` 照常打印（一轮收尾后应为空 / 0），
 * 它们在 B3/B4 之后会搬进 `LiveStream`，快照里留着是为了看它们收尾时确实清零了。
 */
internal object ClaudeStateSnapshot {

    /** 把一份状态渲染成快照文本。[tmpDir] 是脚手架的临时目录（见规范化规则 3） */
    fun render(
        s: ClaudeCodeManager.SessionState,
        tmpDir: File? = null,
        launchIds: Collection<String> = emptyList(),
    ): String {
        val out = StringBuilder()
        val tmpForms = tmpDir?.let { listOf(it.path, it.path.replace('\\', '/'), it.canonicalPath, it.canonicalPath.replace('\\', '/')) }
            ?.distinct()?.sortedByDescending { it.length }.orEmpty()

        fun str(v: String?): String {
            if (v == null) return "null"
            var t: String = v
            // 启动器收到的会话 id 是随机生成的；CLI 的 system/init 到之前 state.sessionId 就是它
            for (id in launchIds) t = t.replace(id, "<launch-id>")
            for (form in tmpForms) t = t.replace(form, "<tmp>")
            val escaped = t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
            return "\"$escaped\""
        }

        fun any(v: Any?): String = when (v) {
            null -> "null"
            is String -> str(v)
            is Enum<*> -> v.name
            else -> v.toString()
        }

        fun ts(v: Long?): String = if (v == null) "null" else "<ts>"
        fun kv(key: String, v: Any?) { out.append(key).append(": ").append(any(v)).append('\n') }
        fun raw(key: String, text: String) { out.append(key).append(": ").append(text).append('\n') }

        // —— 会话级 ——
        kv("status", s.status)
        kv("sessionId", s.sessionId)
        kv("title", s.liveTitle)
        kv("cwd", s.cwd)
        kv("model", s.model)
        kv("errorMessage", s.errorMessage)
        kv("stopping", s.stopping)
        kv("launchedProfileId", s.launchedProfileId)
        kv("toolCount", s.toolCount)
        kv("plan", s.plan)

        // —— 本轮 ——
        kv("busy", s.busy)
        kv("turnProduced", s.turnProduced)
        kv("statusPhase", s.statusPhase)
        kv("statusDetail", s.statusDetail)
        kv("retryNotice", s.retryNotice)
        raw("turnStartedAt", ts(s.turnStartedAt))
        kv("outputTokensSettled", s.outputTokensSettled)
        kv("outputTokensCurrent", s.outputTokensCurrent)
        kv("turnOutputTokens", s.turnOutputTokens)
        kv("streamingText", s.streamingText)
        kv("streamingThinking", s.streamingThinking)
        kv("lastTurnDurationMs", s.lastTurnDurationMs)
        raw("lastTurnFinishedAt", ts(s.lastTurnFinishedAt))

        // —— 设置 ——
        kv("permissionMode", s.permissionMode)
        kv("options.model", s.options.model)
        kv("options.effort", s.options.effort)
        kv("options.ultracode", s.options.ultracode)
        kv("options.skipPermissions", s.options.skipPermissions)
        kv("options.promptCacheTtl", s.options.promptCacheTtl)
        kv("options.permissionMode", s.options.permissionMode)
        kv("options.cwd", s.options.cwd)
        raw("options.newSessionId", if (s.options.newSessionId == null) "null" else "<set>")
        raw("options.resumeSessionId", if (s.options.resumeSessionId == null) "null" else "<set>")
        kv("appliedModel", s.appliedModel)
        kv("appliedEffort", s.appliedEffort)
        kv("appliedUltracode", s.appliedUltracode)
        kv("applyingSettings", s.applyingSettings)
        kv("applyingEffort", s.applyingEffort)
        kv("effortSupported", effortSupportedFor(s))
        kv("availableModels.size", s.availableModels.size)
        s.availableModels.forEachIndexed { i, m ->
            raw(
                "availableModels[$i]",
                "value=${str(m.value)} displayName=${str(m.displayName)} resolvedModel=${str(m.resolvedModel)} " +
                    "hiddenAlias=${m.hiddenAlias} efforts=${m.supportedEffortLevels} description=${str(m.description)}",
            )
        }
        kv("relayModelsChecked", s.relayModelsChecked)
        kv("relayModelsError", s.relayModelsError)
        kv("relayModels.size", s.relayModels.size)
        s.relayModels.forEachIndexed { i, m -> raw("relayModels[$i]", "id=${str(m.id)} displayName=${str(m.displayName)}") }
        // 斜杠命令清单很长（夹具里是整份技能表），描述不进快照，只留名字
        kv("slashCommands.size", s.slashCommands.size)
        raw("slashCommands.names", s.slashCommands.joinToString(",") { it.name })

        // —— 用量 ——
        kv("contextTokens", s.contextTokens)
        kv("contextLimit", s.contextLimit)
        kv("costText", s.costText)

        // —— 待批请求队列（队头 = 界面上挂着的那张卡）——
        kv("permissionQueue.size", s.permissionQueue.size)
        s.permissionQueue.forEachIndexed { i, p ->
            val key = "permissionQueue[$i]"
            raw(
                key,
                "requestId=${str(p.requestId)} toolName=${str(p.toolName)} toolUseId=${str(p.toolUseId)} agentId=${str(p.agentId)}",
            )
            kv("$key.description", p.description)
            kv("$key.decisionReason", p.decisionReason)
            kv("$key.blockedPath", p.blockedPath)
            kv("$key.input", p.input.toString())
            kv("$key.suggestions", p.suggestions.map { it.label })
        }
        // 页面对队头现算的几样（PermissionOrigin.kt、PermissionScope.kt）
        s.pendingPermission?.let { p ->
            val origin = permissionOriginOf(p, s.tasks, s.permissionQueue.size)
            raw(
                "pending.origin",
                "agent=${str(origin.agent)} background=${origin.background} fork=${origin.fork} queuedAfter=${origin.queuedAfter}",
            )
            kv("pending.escapedPaths", escapedPaths(p.input))
            kv("pending.touchesDeviceStorage", touchesDeviceStorage(p.input))
        }

        // —— 子 agent / 后台任务表 ——
        kv("tasks.size", s.tasks.size)
        kv("runningTasks.size", s.runningTasks.size)
        s.tasks.forEachIndexed { i, t ->
            val key = "tasks[$i]"
            raw(
                key,
                "id=${str(t.id)} taskType=${str(t.taskType)} subagentType=${str(t.subagentType)} status=${str(t.status)} " +
                    "backgrounded=${t.backgrounded} toolUseId=${str(t.toolUseId)} startedAt=${ts(t.startedAt.takeIf { it != 0L })} " +
                    "endedAt=${ts(t.endedAt)}",
            )
            kv("$key.description", t.description)
            kv("$key.progress", t.progress)
            kv("$key.outputFile", t.outputFile)
            raw(
                "$key.stats",
                "totalTokens=${t.totalTokens} toolUses=${t.toolUses} durationMs=${t.durationMs} lastToolName=${str(t.lastToolName)}",
            )
            kv("$key.summary", t.summary)
            kv("$key.error", t.error)
        }

        // —— 条目流 ——
        // stderr 条目（ProcessOutput）不进 items[] 的位置序列：它由单独的读线程插入，相对于 stdout 帧派生的条目
        // 落在哪一位每次跑都可能不同（model_not_found 实测出现过「失败提示」前后两种顺序）。所以单列一节，位置不钉。
        val flow = s.items.filter { it !is ChatItem.ProcessOutput }
        val stderrItems = s.items.filterIsInstance<ChatItem.ProcessOutput>()
        kv("items.size", flow.size)
        fun item(key: String, it: ChatItem) {
            when (it) {
                is ChatItem.UserText -> {
                    raw(key, "UserText queued=${it.queued} handoff=${it.handoff?.let { h -> "${str(h.agentType)}/${h.state}" }}")
                    kv("$key.text", it.text)
                }
                is ChatItem.AssistantText -> {
                    raw(key, "AssistantText durationMs=${it.durationMs} outputTokens=${it.outputTokens} finishedAt=${ts(it.finishedAt)}")
                    kv("$key.text", it.text)
                }
                is ChatItem.Thinking -> {
                    raw(key, "Thinking")
                    kv("$key.text", it.text)
                }
                is ChatItem.Note -> {
                    raw(key, "Note isError=${it.isError}")
                    kv("$key.text", it.text)
                }
                is ChatItem.Folded -> raw(key, "Folded ${it.title}")
                is ChatItem.ProcessOutput -> {
                    raw(key, "ProcessOutput dropped=${it.dropped}")
                    it.lines.forEachIndexed { n, line -> kv("$key.lines[$n]", line) }
                }
                is ChatItem.ToolCall -> {
                    raw(key, "ToolCall name=${str(it.name)} toolUseId=${str(it.toolUseId)} status=${it.status} isError=${it.isError}")
                    kv("$key.input", it.input.toString())
                    kv("$key.result", it.result)
                    kv("$key.resultTotalChars", it.resultTotalChars)
                    kv("$key.editDiff", it.editDiff)
                    it.subItems.forEachIndexed { n, sub -> item("$key.subItems[$n]", sub) }
                }
            }
        }
        flow.forEachIndexed { i, it -> item("items[$i]", it) }
        kv("processOutput.size", stderrItems.size)
        stderrItems.forEachIndexed { i, it -> item("processOutput[$i]", it) }

        // —— Page 在 items + tasks 上现算的子 agent 线程（ClaudeCodePage 的 agentThreads）——
        val threads = subagentThreads(s.items, s.tasks)
        kv("threads.size", threads.size)
        threads.forEachIndexed { i, t ->
            val key = "threads[$i]"
            raw(
                key,
                "toolUseId=${str(t.toolUseId)} agentId=${str(t.agentId)} agentType=${str(t.agentType)} phase=${t.phase} " +
                    "tracked=${t.tracked} running=${t.running} items=${t.items.size}",
            )
            kv("$key.description", t.description)
            kv("$key.prompt", t.prompt)
            kv("$key.activity", t.activity)
        }
        return out.toString()
    }
}

/**
 * 快照文件的读写与比对。
 *
 * 默认**比对**；要重新生成基准时（改了夹具、改了渲染规则、或确认现行为的变化是有意的）：
 * - `./gradlew :app:testDebugUnitTest --tests '*ClaudeStateSnapshotTest' -Dmin.snapshot.update=true`
 * - 或设环境变量 `MIN_SNAPSHOT_UPDATE=true`（Gradle 不会把命令行的 `-D` 转给测试 JVM，而测试 JVM 继承环境变量，
 *   所以在不改 build.gradle.kts 的前提下，环境变量是更可靠的那个；两个开关任一为 true 即生成）。
 *
 * 生成之后 `git diff app/src/test/resources/claudecode/snapshots` 逐行看过再提交。
 */
internal object SnapshotStore {
    private const val RELATIVE_DIR = "src/test/resources/claudecode/snapshots"

    val updating: Boolean
        get() = System.getProperty("min.snapshot.update") == "true" || System.getenv("MIN_SNAPSHOT_UPDATE") == "true"

    /** Gradle 的测试工作目录是 app/；从仓库根跑（IDE）时在 app/ 下面 */
    private fun dir(): File {
        val base = if (File("src/test").isDirectory) File(RELATIVE_DIR) else File("app/$RELATIVE_DIR")
        return base.also { it.mkdirs() }
    }

    fun file(name: String): File = File(dir(), "$name.txt")

    /** 比对或生成。不一致抛 [AssertionError]，消息里直接带 diff */
    fun check(name: String, actual: String) {
        val file = file(name)
        if (updating) {
            file.parentFile.mkdirs()
            file.writeText(actual, Charsets.UTF_8)
            return
        }
        if (!file.isFile) {
            throw AssertionError(
                "快照不存在：${file.path}\n生成它：-Dmin.snapshot.update=true（或环境变量 MIN_SNAPSHOT_UPDATE=true）",
            )
        }
        val expected = file.readText(Charsets.UTF_8)
        if (expected == actual) return
        throw AssertionError(
            "快照「$name」与现状不一致（${file.path}）。\n" +
                "「-」是快照里的、「+」是这次跑出来的：\n${lineDiff(expected, actual)}\n" +
                "若这是有意的变化，用 -Dmin.snapshot.update=true（或 MIN_SNAPSHOT_UPDATE=true）重新生成后看 git diff。",
        )
    }

    /**
     * 行级 diff（最长公共子序列），只打印有变化的行并带上下 2 行上下文，行号是快照文件里的行号。
     * 快照最多几百行，O(n*m) 足够。
     */
    fun lineDiff(expected: String, actual: String, context: Int = 2): String {
        val a = expected.split('\n')
        val b = actual.split('\n')
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        // ops: ' ' 相同、'-' 只在快照、'+' 只在实际
        val ops = ArrayList<Triple<Char, String, Int>>()   // (op, text, 快照行号 1 起；'+' 时取最近的快照行号)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { ops += Triple(' ', a[i], i + 1); i++; j++ }
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> { ops += Triple('+', b[j], i + 1); j++ }
                else -> { ops += Triple('-', a[i], i + 1); i++ }
            }
        }
        val show = BooleanArray(ops.size)
        ops.forEachIndexed { k, op ->
            if (op.first != ' ') for (d in -context..context) if (k + d in ops.indices) show[k + d] = true
        }
        val sb = StringBuilder()
        var gap = false
        ops.forEachIndexed { k, (op, text, line) ->
            if (!show[k]) { gap = true; return@forEachIndexed }
            if (gap) { sb.append("  ...\n"); gap = false }
            sb.append(op).append(' ').append(line.toString().padStart(4)).append(" | ").append(text).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }
}
