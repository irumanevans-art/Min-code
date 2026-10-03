package dev.min.agent.context

import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource

/**
 * 记忆文件进模型的样子。系统提示（会话创建时的快照）与工具结果之后的按需记忆（[dev.min.agent.spi.MemoryAttached]）
 * 共用同一套标题：用户现成的 CLAUDE.md 是在 Claude Code 里对着「Contents of <路径> (<说明>):」调出来的。
 * 文字是模型看的，英文。
 */
object MemoryRender {
    /** 标题里括号中的来源说明 */
    fun describe(source: MemorySource): String = when (source) {
        MemorySource.User -> "user's instructions for all projects"
        MemorySource.Project -> "project instructions, checked into the codebase"
        MemorySource.Local -> "user's private instructions for this project, not checked in"
        MemorySource.Rule -> "rule"
        MemorySource.Import -> "imported by a memory file"
        MemorySource.Nested -> "project instructions for this directory, loaded because you accessed a file in it"
        MemorySource.PathRule -> "rule for files matching its paths, loaded because you accessed a matching file"
        MemorySource.Auto -> "your auto memory index"
    }

    /** 一个文件的小节：标题、空行、正文（去掉首尾多余换行） */
    fun section(file: LoadedMemoryFile): String =
        "Contents of ${file.path} (${describe(file.source)}):\n\n" + file.content.trimEnd().trimStart('\n')

    /** 按需加载的记忆挂在工具结果之后：每个文件一段 system-reminder，与 hook 上下文同一种包法 */
    fun reminder(file: LoadedMemoryFile): String = "<system-reminder>\n${section(file)}\n</system-reminder>"
}
