package dev.min.agent.policy

/** 包装参数认不清时暂停规则，不能把未知前缀误当成窄命令授权。 */
internal fun autoRuleProgram(words: List<String>): String? {
    val assignment = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    var rest = words
    fun options(values: Set<String>, flags: Set<String>): Boolean {
        while (rest.firstOrNull()?.startsWith('-') == true) {
            val option = rest.first()
            if (option == "--") { rest = rest.drop(1); return true }
            rest = when {
                option in values -> { if (rest.size < 2) return false; rest.drop(2) }
                option in flags || option.substringBefore('=') in values && '=' in option -> rest.drop(1)
                else -> return false
            }
        }
        return true
    }
    while (rest.isNotEmpty()) {
        rest = rest.dropWhile { assignment.matches(it) }
        val program = rest.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() ?: return null
        val wrappers = setOf("timeout", "nohup", "command", "time", "nice", "stdbuf", "setsid", "flock", "watch", "busybox", "env")
        if (program !in wrappers) return program
        rest = rest.drop(1)
        when (program) {
            "timeout" -> {
                if (!options(setOf("-s", "--signal", "-k", "--kill-after"), setOf("--foreground", "--preserve-status", "-v", "--verbose"))) return null
                rest = rest.drop(1)
            }
            "nice" -> {
                if (rest.firstOrNull()?.matches(Regex("-\\d+|-n-?\\d+")) == true) rest = rest.drop(1)
                else if (!options(setOf("-n", "--adjustment"), emptySet())) return null
            }
            "env" -> if (!options(setOf("-u", "--unset", "-C", "--chdir"), setOf("-i", "--ignore-environment", "-", "-0", "--null"))) return null
            "time" -> if (!options(setOf("-f", "--format", "-o", "--output"), setOf("-p", "--portability", "-v", "--verbose", "-a", "--append"))) return null
            "command" -> {
                if (rest.firstOrNull() in setOf("-v", "-V")) return "command-query"
                if (!options(emptySet(), setOf("-p"))) return null
            }
            "stdbuf" -> {
                while (rest.firstOrNull()?.startsWith('-') == true) {
                    val option = rest.first()
                    rest = when {
                        option == "--" -> rest.drop(1).also { break }
                        option in setOf("-i", "-o", "-e", "--input", "--output", "--error") -> rest.drop(2)
                        option.matches(Regex("-[ioe].+")) || option.substringBefore('=') in setOf("--input", "--output", "--error") && '=' in option -> rest.drop(1)
                        else -> return null
                    }
                }
            }
            "setsid" -> if (!options(emptySet(), setOf("-f", "--fork", "-w", "--wait", "-c", "--ctty"))) return null
            "flock" -> {
                if (!options(setOf("-w", "--timeout", "-E", "--conflict-exit-code"), setOf("-x", "--exclusive", "-s", "--shared", "-n", "--nonblock", "-o", "--close", "-F", "--no-fork"))) return null
                rest = rest.drop(1)
            }
            "watch" -> if (!options(setOf("-n", "--interval"), setOf("-x", "--exec", "-t", "--no-title", "-d", "--differences", "-g", "--chgexit", "-e", "--errexit", "-p", "--precise"))) return null
            "nohup" -> if (!options(emptySet(), emptySet())) return null
            "busybox" -> if (rest.firstOrNull()?.startsWith('-') == true) return null
        }
    }
    return null
}
