package dev.min.agent.ext.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 按字段类型从 frontmatter 取值。类型不对的字段：跳过并记一条说明（哪个文件、哪个键、为什么），绝不抛异常——
 * CC 的语义是「认不出的字段忽略」，一个手写笔误不该让整个 skill 或 agent 消失。
 */
internal class FrontmatterFields(
    private val fields: Map<String, FmValue>,
    private val label: String,
    private val notes: MutableList<String>,
) {
    fun has(key: String): Boolean = fields[key].let { it != null && it != FmValue.Null }

    /** 标量字段。数字 / 布尔写法（`name: 123`）也按原文收下，YAML 里它们只是类型不同 */
    fun string(key: String): String? = when (val v = fields[key]) {
        null, FmValue.Null -> null
        is FmValue.Scalar -> v.text
        else -> bad(key, "期望一个字符串", v)
    }

    /** 布尔：true/false 之外，skills 文档还接受 yes/no/on/off/1/0，大小写不限 */
    fun bool(key: String): Boolean? = when (val v = fields[key]) {
        null, FmValue.Null -> null
        is FmValue.Scalar -> BOOLEAN_WORDS[v.text.trim().lowercase()] ?: bad(key, "期望布尔值（true/false/yes/no/on/off/1/0）", v)
        else -> bad(key, "期望布尔值", v)
    }

    fun int(key: String): Int? = when (val v = fields[key]) {
        null, FmValue.Null -> null
        is FmValue.Scalar -> v.text.trim().toIntOrNull() ?: bad(key, "期望整数", v)
        else -> bad(key, "期望整数", v)
    }

    /**
     * 字符串列表：YAML 列表，或一个字符串按 [separators] 切。
     * 切分只在括号 `( [ {` 之外进行，所以 `Bash(git add *)`、`*.{ts,tsx}` 不会被劈开。
     */
    fun list(key: String, separators: Separators): List<String>? = when (val v = fields[key]) {
        null, FmValue.Null -> null
        is FmValue.Scalar -> splitOutsideBrackets(v.text, separators)
        is FmValue.Seq -> v.items.mapNotNull { item ->
            if (item is FmValue.Scalar) item.text.trim().ifEmpty { null } else bad(key, "列表里的项应是字符串", item)
        }
        else -> bad(key, "期望字符串列表或逗号分隔的字符串", v)
    }

    /** 任意嵌套结构原样转成 JSON，留给以后的执行方（hooks、mcpServers） */
    fun json(key: String): JsonElement? = fields[key]?.takeIf { it != FmValue.Null }?.toJson()

    /** 必须是映射的字段（hooks、metadata）；不是映射的丢掉并说明 */
    fun obj(key: String): JsonObject? = when (val v = fields[key]) {
        null, FmValue.Null -> null
        is FmValue.Mapping -> v.toJson() as JsonObject
        else -> bad(key, "期望一个映射", v)
    }

    private fun <T> bad(key: String, expected: String, actual: FmValue): T? {
        notes += "$label：忽略 frontmatter 字段 `$key`：$expected，实际是${describe(actual)}"
        return null
    }

    enum class Separators(val comma: Boolean, val whitespace: Boolean) {
        /** 工具名单：`Read, Grep`、`Bash(git add *) Bash(git commit *)` 两种写法都有 */
        CommaOrSpace(true, true),

        /** `paths` 的 glob：文档只说逗号分隔，glob 本身可以含空格 */
        CommaOnly(true, false),
    }

    private companion object {
        val BOOLEAN_WORDS = mapOf(
            "true" to true, "yes" to true, "on" to true, "1" to true,
            "false" to false, "no" to false, "off" to false, "0" to false,
        )

        fun describe(v: FmValue): String = when (v) {
            FmValue.Null -> "空值"
            is FmValue.Scalar -> "字符串 `${v.text.take(30)}`"
            is FmValue.Seq -> "列表"
            is FmValue.Mapping -> "映射"
        }

        fun splitOutsideBrackets(text: String, sep: Separators): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var depth = 0
            for (c in text) {
                when {
                    c == '(' || c == '[' || c == '{' -> { depth++; cur.append(c) }
                    c == ')' || c == ']' || c == '}' -> { depth = (depth - 1).coerceAtLeast(0); cur.append(c) }
                    depth == 0 && ((sep.comma && c == ',') || (sep.whitespace && c.isWhitespace())) -> {
                        if (cur.isNotBlank()) out += cur.toString().trim()
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
            return out
        }
    }
}
