package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.InstructionsLoadedInput
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import kotlinx.serialization.json.jsonObject

/**
 * InstructionsLoaded（hooks.md :1278–:1313）：CLAUDE.md 与 .claude/rules 的 *.md 载入上下文时逐文件触发，
 * matcher 对 load_reason。原文没有决策控制、JSON 输出全部丢弃（引擎丢弃表里是 ALL_OUTPUT），只作审计，
 * 所以这里跑完就把结论丢掉。原文的触发面里 Min 有两处对应：
 * - 会话创建的记忆快照（loadMemory 落的 MemoryLoaded）：eager 载入，load_reason = session_start；
 *   被导入的文件（@导入）原文记 include（:1298）；
 * - 工具碰到文件后的按需注入（ToolExecutor 落的 MemoryAttached）：子目录记忆 = nested_traversal、
 *   带 paths: 的规则 = path_glob_match。
 * 没有的对应：compact（Min 的记忆快照会话内冻结，压缩后不重读盘，没有「重载入」那一刻）。
 * trigger_file_path / globs / parent_file_path 的归因信息 Min 的 LoadedMemoryFile 不携带，输入里缺席（不硬造）。
 * 分身 / 隔离 worktree 子会话经 initial 继承的快照不是新的读盘载入，也不发。
 */
internal suspend fun SessionRuntime.fireInstructionsLoaded(files: List<LoadedMemoryFile>, reasonOf: (MemorySource) -> String?) {
    val driver = HookDriver(this)
    val home = deps.memoryHome
    for (file in files) {
        val reason = reasonOf(file.source) ?: continue
        val input = InstructionsLoadedInput(
            filePath = deps.hookShellPath(file.path),
            memoryType = memoryTypeOf(file, home),
            loadReason = reason,
        )
        driver.run(HookEvent.InstructionsLoaded, reason, HookJson.encodeToJsonElement(InstructionsLoadedInput.serializer(), input).jsonObject)
    }
}

/** eager（会话创建）：导入的文件是 include，其余都是 session_start；自动记忆索引不是 CLAUDE.md / 规则，不发 */
internal fun eagerLoadReason(source: MemorySource): String? = when (source) {
    MemorySource.Import -> "include"
    MemorySource.Auto -> null
    else -> "session_start"
}

/** 按需注入：子目录记忆与路径规则各按原文的懒加载原因；导入跟着 include */
internal fun lazyLoadReason(source: MemorySource): String? = when (source) {
    MemorySource.Nested -> "nested_traversal"
    MemorySource.PathRule -> "path_glob_match"
    MemorySource.Import -> "include"
    MemorySource.Auto -> null
    else -> "session_start"
}

/**
 * memory_type：原文四档 User / Project / Local / Managed。Min 的来源里 User / Local 直接对上；
 * 规则与导入按文件位置分（主目录下 = User，工作区里 = Project）；原文的 Managed（托管层说明文件）Min 没有
 */
private fun memoryTypeOf(file: LoadedMemoryFile, home: GuestPath?): String = when {
    file.source == MemorySource.User -> "User"
    file.source == MemorySource.Local -> "Local"
    home != null && file.path.value.startsWith(home.value.trimEnd('/') + "/") -> "User"
    else -> "Project"
}
