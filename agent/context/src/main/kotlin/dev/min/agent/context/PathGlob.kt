package dev.min.agent.context

import dev.min.agent.spi.catchingNonCancellation

/**
 * 规则 `paths:` 里的 glob，对「相对规则所属项目根的路径」做整串匹配（CC 文档的写法：双星号开头的任意深度扩展名匹配、
 * `src` 目录下双星号、只匹配根下 md 的 `*.md`、某目录下单层的 `*.tsx`、`*.{ts,tsx}`）。纯函数。
 *
 * - `*` 不跨 `/`，`?` 一个非 `/` 字符，`**` 跨目录；`**` 加 `/` 匹配零层或多层目录。
 * - `{a,b}` 取其一，可嵌套。
 * - **`\x` 是转义**，匹配字面的 x；要匹配字面 `[` 写 `\[`。
 * - **未转义的 `[` 让整条模式失效**（匹配不到任何文件，由调用方记一条说明）：CC 文档原文是未转义的 `[` 会让模式匹配不到，
 *   不是字符集。所以 Next.js 的 `app/[id]/page.tsx` 在这里和在 CC 里一样匹配不到，要写 `app/\[id\]/page.tsx`。
 * - 不含 `/` 的模式**不会**因此匹配任意深度（与 gitignore 不同，照 CC 文档：`*.md` 是根目录的 markdown）。
 * - 开头的 `./` 与 `/` 去掉：写成以斜杠开头的也按相对根算。
 *
 * 模式写得有问题时**绝不抛异常**：编译失败一律当作「不匹配」（[compile] 返回 [Compiled.Invalid]）。
 */
internal object PathGlob {
    sealed interface Compiled {
        class Ok(val regex: Regex) : Compiled

        /** [reason] 给人看 */
        class Invalid(val reason: String) : Compiled
    }

    fun matches(pattern: String, relativePath: String): Boolean =
        (compile(pattern) as? Compiled.Ok)?.regex?.matches(relativePath.trimStart('/')) == true

    fun compile(pattern: String): Compiled {
        val glob = pattern.trim().removePrefix("./").trimStart('/')
        if (glob.isEmpty()) return Compiled.Invalid("空模式")
        val source = toRegex(glob) ?: return Compiled.Invalid("含未转义的 [（要匹配字面 [ 请写成 \\[）")
        return catchingNonCancellation { Regex(source) }
            .fold({ Compiled.Ok(it) }, { Compiled.Invalid("写法无法编译：${it.message?.lineSequence()?.firstOrNull() ?: it::class.simpleName}") })
    }

    /** 转成正则源码；遇到未转义的 `[` 返回 null */
    private fun toRegex(glob: String): String? {
        val out = StringBuilder()
        var depth = 0 // 花括号嵌套层数
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 3
                    // 连着的 `**/` 与一个等价，合并掉：多个可选的 `.*/` 叠在一起会让回溯爆炸
                    while (glob.startsWith("**/", i)) i += 3
                    continue
                }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '\\' -> {
                    // 转义：下一个字符按字面；末尾孤零零的反斜杠就是字面反斜杠
                    if (i + 1 < glob.length) {
                        out.append(Regex.escape(glob[i + 1].toString()))
                        i += 2
                    } else {
                        out.append(Regex.escape("\\"))
                        i++
                    }
                    continue
                }
                c == '[' -> return null
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '{' -> { out.append("(?:"); depth++ }
                c == '}' && depth > 0 -> { out.append(")"); depth-- }
                c == ',' && depth > 0 -> out.append("|")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        // 花括号没闭合：补齐，免得拼出非法正则；这样的模式多半匹配不上，宁可不注入
        repeat(depth) { out.append(")") }
        return out.toString()
    }
}
