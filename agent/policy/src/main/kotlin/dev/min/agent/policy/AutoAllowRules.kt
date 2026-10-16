package dev.min.agent.policy

/** 包装参数认不清时暂停规则，不能把未知前缀误当成窄命令授权。 */
internal fun autoRuleProgram(words: List<String>): String? {
    val assignment = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    val wrappers = setOf("timeout", "nohup", "command", "time", "nice", "stdbuf", "setsid", "flock", "watch", "busybox", "env")
    var rest = words
    while (rest.isNotEmpty()) {
        rest = rest.dropWhile { assignment.matches(it) }
        val program = rest.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() ?: return null
        if (program !in wrappers) return program
        rest = rest.drop(1)
        if (program == "command" && rest.firstOrNull() in setOf("-v", "-V")) return "command-query"
        val skipped = WrapperSyntax.skipOptions(program, rest) ?: return null
        rest = rest.drop(skipped)
    }
    return null
}
