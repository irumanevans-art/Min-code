package dev.min.agent.context

/**
 * 规则 `paths:` 里的 glob，对「相对规则所属项目根的路径」做整串匹配（CC 文档的写法：双星号开头的任意深度扩展名匹配、
 * `src` 目录下双星号、只匹配根下 md 的 `*.md`、某目录下单层的 `*.tsx`、`*.{ts,tsx}`）。纯函数。
 *
 * - `*` 不跨 `/`，`?` 一个非 `/` 字符，`**` 跨目录；`**` 加 `/` 匹配零层或多层目录。
 * - `{a,b}` 取其一，可嵌套；`[abc]` 字符集（取 `!` 或 `^` 开头为取反）。
 * - 不含 `/` 的模式**不会**因此匹配任意深度（与 gitignore 不同，照 CC 文档：`*.md` 是根目录的 markdown）。
 * - 开头的 `./` 与 `/` 去掉：写成以斜杠开头的也按相对根算。
 */
internal object PathGlob {
    fun matches(pattern: String, relativePath: String): Boolean {
        val glob = pattern.trim().removePrefix("./").trimStart('/')
        if (glob.isEmpty()) return false
        return Regex(toRegex(glob)).matches(relativePath.trimStart('/'))
    }

    private fun toRegex(glob: String): String {
        val out = StringBuilder()
        var depth = 0 // 花括号嵌套层数
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '{' -> { out.append("(?:"); depth++ }
                c == '}' && depth > 0 -> { out.append(")"); depth-- }
                c == ',' && depth > 0 -> out.append("|")
                c == '[' -> {
                    val close = glob.indexOf(']', i + 2)
                    if (close < 0) {
                        out.append("\\[")
                    } else {
                        val body = glob.substring(i + 1, close)
                        val negated = body.startsWith("!") || body.startsWith("^")
                        out.append(if (negated) "[^" else "[").append(body.drop(if (negated) 1 else 0).replace("\\", "\\\\")).append("]")
                        i = close
                    }
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        // 花括号没闭合：补齐，免得拼出非法正则；这样的模式多半匹配不上，宁可不注入
        repeat(depth) { out.append(")") }
        return out.toString()
    }
}
