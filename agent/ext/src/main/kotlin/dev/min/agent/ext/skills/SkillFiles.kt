package dev.min.agent.ext.skills

import dev.min.agent.ext.config.ConfigIo
import dev.min.agent.ext.config.FrontmatterParser
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs

/**
 * 读一个 skill / command 定义文件**此刻**的正文。会话创建时快照进日志的只有清单（名字、说明、参数名……），
 * 正文不进日志——绝大多数 skill 整个会话都不会被调用，把它们的全文塞进每个会话的日志与子会话的继承里不划算；
 * 模型调 Skill 工具时才来读，读到什么、渲染成什么都在那次工具结果里留痕，请求照样能从日志重建。
 */
object SkillFiles {
    /** frontmatter 之后的正文；文件没了、读不了、不是文本返回 null */
    suspend fun readBody(fs: WorkspaceFs, path: GuestPath): String? {
        val io = ConfigIo(fs)
        val text = io.readText(path.value) ?: return null
        return FrontmatterParser.parse(text, path.value).body
    }
}
