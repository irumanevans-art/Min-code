package dev.min.agent.ext.workflow

import kotlinx.serialization.json.*
import java.security.MessageDigest

data class WorkflowScript(val meta: JsonObject, val body: String) {
    val name: String get() = requireNotNull((meta["name"] as? JsonPrimitive)?.contentOrNull)
    val phases: List<String> get() = (meta["phases"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    companion object {
        fun hash(source: String): String = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** 发现命令时不能执行项目脚本；只接受字面量语法，拒绝 getter、展开和表达式。 */
        fun parse(source: String): WorkflowScript {
            require(source.length <= 256 * 1024) { "Workflow script exceeds 256 KiB." }
            val literal = Literal(source)
            literal.word("export"); literal.word("const"); literal.word("meta"); literal.take('=')
            val meta = literal.value() as? JsonObject ?: error("meta must be an object literal.")
            literal.skip()
            require(literal.pos == source.length || source[literal.pos] == ';' || literal.lineBreak) { "meta must be the first statement." }
            if (literal.pos < source.length && source[literal.pos] == ';') literal.pos++
            require((meta["name"] as? JsonPrimitive)?.isString == true && meta["name"]!!.jsonPrimitive.content.matches(Regex("[A-Za-z0-9_-]+"))) { "meta.name must be a command name." }
            require((meta["description"] as? JsonPrimitive)?.isString == true) { "meta.description must be a string." }
            meta["phases"]?.let { phases -> require(phases is JsonArray && phases.all { it is JsonPrimitive && it.isString }) { "meta.phases must be strings." } }
            return WorkflowScript(meta, source.substring(literal.pos))
        }
    }
}

private class Literal(private val source: String) {
    var pos = 0
    var lineBreak = false
    fun skip() {
        lineBreak = false
        while (pos < source.length) {
            when {
                source[pos].isWhitespace() -> { if (source[pos] == '\n' || source[pos] == '\r') lineBreak = true; pos++ }
                source.startsWith("//", pos) -> { while (pos < source.length && source[pos] != '\n') pos++ }
                source.startsWith("/*", pos) -> {
                    val end = source.indexOf("*/", pos + 2); require(end >= 0)
                    if ('\n' in source.substring(pos, end)) lineBreak = true
                    pos = end + 2
                }
                else -> return
            }
        }
    }
    fun take(c: Char) { skip(); require(pos < source.length && source[pos++] == c) { "Expected $c in meta." } }
    fun word(w: String) { skip(); require(identifier() == w) { "First statement must be export const meta = {...}." } }
    private fun identifier(): String {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] in "_$")) pos++
        require(pos > start) { "Expected a literal identifier." }
        return source.substring(start, pos)
    }
    fun value(depth: Int = 0): JsonElement {
        require(depth < 32); skip(); require(pos < source.length)
        return when (source[pos]) {
            '{' -> {
                pos++; val pairs = linkedMapOf<String, JsonElement>(); skip()
                while (pos < source.length && source[pos] != '}') {
                    val key = if (source[pos] in "\"'") string() else identifier()
                    take(':'); require(key !in pairs); pairs[key] = value(depth + 1); skip()
                    if (source.getOrNull(pos) != ',') break
                    pos++; skip()
                }
                take('}'); JsonObject(pairs)
            }
            '[' -> {
                pos++; val values = mutableListOf<JsonElement>(); skip()
                while (pos < source.length && source[pos] != ']') {
                    values += value(depth + 1); skip()
                    if (source.getOrNull(pos) != ',') break
                    pos++; skip()
                }
                take(']'); JsonArray(values)
            }
            '\'', '"' -> JsonPrimitive(string())
            else -> {
                val start = pos
                while (pos < source.length && source[pos] !in ",}] \t\r\n;") pos++
                val token = source.substring(start, pos)
                require(token in listOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "meta must contain literals only." }
                Json.parseToJsonElement(token)
            }
        }
    }
    private fun string(): String {
        val quote = source[pos++]; val out = StringBuilder()
        while (pos < source.length) {
            val c = source[pos++]
            if (c == quote) return out.toString()
            require(c != '\n' && c != '\r')
            if (c != '\\') out.append(c) else {
                require(pos < source.length)
                out.append(when (val e = source[pos++]) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                    '\\', '\'', '"', '/' -> e
                    'u' -> { require(pos + 4 <= source.length); source.substring(pos, pos + 4).toInt(16).toChar().also { pos += 4 } }
                    else -> error("Unsupported literal escape.")
                })
            }
        }
        error("Unterminated string in meta.")
    }
}
