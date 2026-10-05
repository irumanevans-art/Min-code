package dev.min.agent.host

import dev.min.agent.spi.catchingNonCancellation

/** 不调用 git：Android 宿主扫描不能为每条路径启动 proot。兼容性由真 git 生成的样本固定。 */
internal class SnapshotIgnore(private val rules: List<Rule> = emptyList()) {
    internal data class Rule(val base: String, val regex: Regex, val directoryOnly: Boolean, val negated: Boolean)

    fun withFile(base: String, text: String): SnapshotIgnore = SnapshotIgnore(rules + parse(base, text))

    fun ignored(path: String, directory: Boolean): Boolean {
        val parts = path.split('/')
        // Git 不进入已排除的父目录；对子文件取反也不能把它救回来。
        for (n in 1..parts.size) {
            val candidate = parts.take(n).joinToString("/")
            val isDirectory = n < parts.size || directory
            var excluded = false
            for (rule in rules) {
                val relative = if (rule.base.isEmpty()) candidate else candidate.takeIf { it.startsWith(rule.base + "/") }?.removePrefix(rule.base + "/") ?: continue
                if ((!rule.directoryOnly || isDirectory) && rule.regex.matches(relative)) excluded = !rule.negated
            }
            if (excluded) return true
        }
        return false
    }

    companion object {
        val DEFAULT = listOf("node_modules", ".venv", "venv", "__pycache__", ".gradle", "build", "dist", "target", ".cache", ".pytest_cache", ".mypy_cache", ".tox")
            .joinToString("\n") { "$it/" }

        private fun parse(base: String, text: String): List<Rule> = text.lineSequence().mapNotNull { raw ->
            var line = raw.removeSuffix("\r")
            // 未转义的尾空格不是模式的一部分，转义的空格必须保留。
            while (line.endsWith(' ')) {
                var escapes = 0
                var at = line.length - 2
                while (at >= 0 && line[at--] == '\\') escapes++
                if (escapes % 2 == 1) break
                line = line.dropLast(1)
            }
            if (line.isEmpty() || line.startsWith('#')) return@mapNotNull null
            val negated = line.startsWith('!')
            if (negated) line = line.drop(1)
            if (line.isEmpty()) return@mapNotNull null
            val directory = line.endsWith('/')
            if (directory) line = line.dropLast(1)
            val anchored = line.startsWith('/')
            if (anchored) line = line.drop(1)
            val anywhere = !anchored && '/' !in line
            val pattern = glob(line) ?: return@mapNotNull null
            catchingNonCancellation { Rule(base, Regex("^" + (if (anywhere) "(?:.*/)?" else "") + pattern + "$"), directory, negated) }.getOrNull()
        }.toList()

        private fun glob(pattern: String): String? = buildString {
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '\\' -> {
                        if (++i == pattern.length) return null
                        append(Regex.escape(pattern[i].toString()))
                    }
                    '*' -> {
                        var end = i
                        while (end < pattern.length && pattern[end] == '*') end++
                        val double = end - i >= 2 && (i == 0 || pattern[i - 1] == '/') && (end == pattern.length || pattern[end] == '/')
                        if (double && end < pattern.length) { append("(?:[^/]+/)*"); end++ }
                        else append(if (double) ".*" else "[^/]*")
                        i = end - 1
                    }
                    '?' -> append("[^/]")
                    '[' -> {
                        var end = i + 1
                        if (end < pattern.length && pattern[end] in "!^") end++
                        if (end < pattern.length && pattern[end] == ']') end++
                        while (end < pattern.length && pattern[end] != ']') {
                            if (pattern[end] == '[' && pattern.getOrNull(end + 1) == ':') {
                                val close = pattern.indexOf(":]", end + 2)
                                if (close >= 0) { end = close + 2; continue }
                            }
                            if (pattern[end] == '\\') end++
                            end++
                        }
                        if (end == pattern.length) append("\\[")
                        else {
                            var contents = pattern.substring(i + 1, end)
                            if (contents.startsWith('!')) contents = "^" + contents.drop(1)
                            // POSIX 类照 fnmatch；斜线仍不能被字符类匹配。
                            contents = contents.replace("[:digit:]", "0-9").replace("[:alpha:]", "a-zA-Z").replace("[:alnum:]", "a-zA-Z0-9")
                            append("(?!/)[").append(contents).append(']'); i = end
                        }
                    }
                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }
    }
}
