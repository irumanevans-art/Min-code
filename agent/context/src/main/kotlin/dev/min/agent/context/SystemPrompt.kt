package dev.min.agent.context

import dev.min.agent.llm.ModelProfile

/**
 * 会话开始时就定下、整个会话内逐字不变的环境事实。会话中途变了的（换目录、换模式）不改这里，
 * 由内核在对话末尾追加一段说明——改写前缀会让提示缓存整段失效（docs/agent/04-子系统设计.md §4.5）。
 */
data class Environment(
    /** 例：`Ubuntu 24.04 (proot on Android)`；桌面评测时是本机系统 */
    val platform: String,
    val cwd: String,
    /** 会话开始那天的日期，ISO 格式。跨过午夜不更新：换来的是一整天的缓存命中 */
    val date: String,
)

/**
 * 系统提示。分两段是为了缓存：第一段对所有会话都一样（身份与规矩），第二段对一个会话不变（环境）。
 *
 * 写得短是有意的：规矩越多，模型越容易在不相干的地方照搬；能靠工具描述讲清楚的不写在这里
 * （Cursor 删掉约 2/3 的系统提示质量没降；pi 的系统提示不到 1000 token）。
 * 改这里必须更新请求快照测试，并在提交说明里写 token 代价与缓存影响（docs/agent/07-工程规范.md 第 9 条）。
 */
object SystemPrompt {
    fun build(@Suppress("UNUSED_PARAMETER") profile: ModelProfile, env: Environment): List<String> =
        listOf(RULES, environmentBlock(env))

    private val RULES = """
        You are Min Agent, a coding agent running inside the Min app on the user's Android phone.
        You work in a Linux environment through the tools you are given. The user reads your replies on a small screen.

        - Reply in the user's language. Be concise: lead with the result, skip filler.
        - Read a file before you edit it. Keep changes minimal and focused on what was asked.
        - Use the dedicated file tools (Read, Write, Edit, Glob, Grep) for files. Shell commands run under proot, where
          file-heavy commands such as find or grep -r are tens of times slower than these tools.
        - Use Bash for builds, tests, git and everything else. Prefer non-interactive flags.
        - If a tool result says a call was interrupted or its result is unknown, check the current state before
          retrying anything that has side effects.
        - If a call is denied, do not retry it unchanged; take the note into account and choose another way or ask.
        - Do not guess at facts you can check with a tool. When you are done, say what changed and anything left to do.
    """.trimIndent()

    private fun environmentBlock(env: Environment): String = """
        Environment:
        - Platform: ${env.platform}
        - Working directory: ${env.cwd}
        - Session date: ${env.date}
    """.trimIndent()
}
