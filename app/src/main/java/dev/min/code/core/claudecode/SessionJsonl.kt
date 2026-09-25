package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 导入被拒的原因。界面按它取文案，带行号的几种由 [SessionImportScan.Rejected.line] 给出第几行 */
enum class SessionImportReject {
    /** 没有一行 JSON（空文件、全是空行） */
    EMPTY,

    /** 超过 [SessionJsonl.MAX_IMPORT_BYTES] */
    TOO_LARGE,

    /** 某一行不是 JSON 对象 */
    BAD_LINE,

    /** 通篇没有一行带 `sessionId` */
    NO_SESSION_ID,

    /** 两行的 `sessionId` 不一样：一个文件里混了多个会话 */
    MIXED_SESSION_ID,

    /** `sessionId` 是空串 / 不是字符串 / 不是 UUID */
    BAD_SESSION_ID,
}

sealed interface SessionImportScan {
    data class Ok(
        val sessionId: String,
        /** 改写了 `cwd` 的行数；0 = 文件里的工作目录本来就和目标一致 */
        val cwdRewritten: Int,
    ) : SessionImportScan

    /** [line] 从 1 数；和具体某一行无关的原因（空文件、超大、通篇没 id）是 0 */
    data class Rejected(val reason: SessionImportReject, val line: Int = 0) : SessionImportScan
}

/**
 * Claude Code transcript（`~/.claude/projects/<编码后的 cwd>/<sessionId>.jsonl`）导入时的纯逻辑：
 * 逐行校验、取 `sessionId`、改写顶层 `cwd`，以及 CLI 给 cwd 起目录名的规则。IO 在
 * [ClaudeCodeSessionTransfer]。
 *
 * ## CLI 怎么找回一个会话（为什么改写 cwd 只是「让元数据说真话」）
 *
 * `claude --resume <id>` 按 **sessionId 文件名**在 `projects/` 下的所有项目目录里找
 * `<id>.jsonl`，不要求它在「当前 cwd 编码出的那个目录」里。实测（CLI 2.1.281）：把一份
 * transcript 放进 A 目录、在 B 目录下 `--resume` 照样续上，新写的行追加回 A 里那份文件，
 * 新行的 `cwd` 是 B。Min 本来就靠这一点活着：proot 的 `-w` 永远是 `/workspace`，
 * `set_cwd` 切到子目录后的会话落在别的项目目录里，续聊时照样能从 `/workspace` 找回来。
 *
 * 所以放进哪个项目目录、行里的 `cwd` 写的是什么，都不决定能不能续上；改写是为了让
 * 导入后的会话在元数据上属于当前工作目录（跨设备时默认的 `/workspace` 本来就一致——
 * 它是 files/ 的固定挂载点，见 [ClaudeCodeManager.DEFAULT_CWD]；不一致的是用户选的子目录，
 * 以及从电脑上的 Claude Code 拿来的 `C:\...`、`/Users/...`）。
 *
 * ## 为什么是「只替换那个值」而不是解析后整行重新序列化
 *
 * 重新序列化会改键序、改转义（`\u00e9` ↔ `é`、`\/` ↔ `/`）、改数字写法，一份几十 MB 的
 * transcript 被整个「洗」一遍，出了问题也没法和原文件逐字比对。这里用 kotlinx 做校验和取值，
 * 定位靠一个只认顶层的小扫描器，改写时只把 `"cwd":` 后面那个字符串字面量换掉，其余字节不动；
 * 不需要改的行连解码都不回写，原样透传。
 */
object SessionJsonl {

    /**
     * 导入文件的上限：64 MiB。
     *
     * 来历：本机电脑上 228 份真实 transcript 里最大的是 74 MB（1M 上下文、带图的长会话），
     * 其次都在 43 MB 以内。上限卡的是手机：打开会话时 [ClaudeCodeSessionStore.loadTranscript]
     * 会把整份文件解析成事件留在 App 堆里，CLI 的 `--resume` 也要在 proot 里的 node 进程
     * 里整份读进来——比这还大的会话在手机上多半打不开，不如在导入时就说清楚。
     * 扫描本身是流式的，上限不是为了扫描时的内存。
     */
    const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024

    /**
     * CLI 给项目目录起名的长度上限。超过时 CLI 截到 200 再拼一段路径的哈希（2.1.281 二进制里的
     * `uR()`），那个哈希函数这里不去复刻——见 [projectDirName]。
     */
    const val PROJECT_DIR_NAME_MAX = 200

    /** CLI 写的 sessionId 一律是 `crypto.randomUUID()`；别的形状既不是它写的，也不能放心拿来当文件名 */
    private val SESSION_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private const val READ_CHUNK = 64 * 1024
    private const val LF: Byte = '\n'.code.toByte()
    private const val CR: Byte = '\r'.code.toByte()
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private val json = Json

    fun isSessionId(value: String): Boolean = SESSION_ID.matches(value)

    /**
     * cwd → `projects/` 下的目录名，照 CLI 的规则：每个不是 `[a-zA-Z0-9]` 的 **UTF-16 码元**
     * 换成 `-`（CLI 是 JS 的 `replace(/[^a-zA-Z0-9]/g, "-")`，所以一个 emoji 是两个 `-`，
     * 这里按 Char 逐个换而不用 JVM 正则——后者按码点算，会少一个）。
     * `/workspace` → `-workspace`，`/workspace/my app` → `-workspace-my-app`。
     *
     * 超过 [PROJECT_DIR_NAME_MAX] 时返回 null：CLI 会在后面拼一段哈希，猜错了就会多出一个
     * CLI 自己永远不会写进去的目录。调用方退回默认目录即可——`--resume` 按文件名跨目录找，
     * 放在哪个目录都续得上（见类注释）。
     */
    fun projectDirName(cwd: String): String? {
        val name = buildString(cwd.length) {
            for (c in cwd) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
        }
        return name.takeIf { it.length <= PROJECT_DIR_NAME_MAX }
    }

    /**
     * 流式扫一遍 [input]，把（必要时改写过 cwd 的）内容写进 [output]，同时校验：
     *
     * - 每个非空行必须是一个 JSON 对象；空行原样保留；
     * - 带 `sessionId` 的行必须全部一致、是 UUID；`file-history-snapshot` 这类行本来就不带，
     *   不强求每行都有，但通篇至少要有一行；
     * - 总字节数不超过 [maxBytes]。
     *
     * 行尾原样保留（`\n` 还是 `\r\n`、最后一行有没有换行）。唯一丢掉的是文件头的 UTF-8 BOM：
     * CLI 自己从不写它，留着的话第一行在 CLI 的 `JSON.parse` 里就是坏行。
     *
     * 被拒时 [output] 里已经写了一部分，由调用方丢掉。
     */
    fun scanImport(
        input: InputStream,
        output: OutputStream,
        targetCwd: String,
        maxBytes: Long = MAX_IMPORT_BYTES,
    ): SessionImportScan {
        val scanner = LineScanner(output, targetCwd)
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return SessionImportScan.Rejected(SessionImportReject.TOO_LARGE)
            var start = 0
            for (i in 0 until n) {
                if (chunk[i] != LF) continue
                line.write(chunk, start, i - start)
                scanner.line(line.toByteArray(), terminated = true)?.let { return it }
                line.reset()
                start = i + 1
            }
            line.write(chunk, start, n - start)
        }
        if (line.size() > 0) scanner.line(line.toByteArray(), terminated = false)?.let { return it }
        return scanner.finish()
    }

    private class LineScanner(private val output: OutputStream, private val targetCwd: String) {
        private var lineNo = 0
        private var objects = 0
        private var sessionId: String? = null
        private var rewritten = 0

        fun line(raw: ByteArray, terminated: Boolean): SessionImportScan.Rejected? {
            lineNo++
            var bytes = raw
            if (lineNo == 1 && bytes.startsWith(UTF8_BOM)) bytes = bytes.copyOfRange(UTF8_BOM.size, bytes.size)
            val crlf = bytes.isNotEmpty() && bytes.last() == CR
            val body = if (crlf) bytes.copyOfRange(0, bytes.size - 1) else bytes
            val text = body.decodeToString()
            if (text.isBlank()) {
                write(bytes, terminated)
                return null
            }
            val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return reject(SessionImportReject.BAD_LINE)
            objects++

            when (val id = obj["sessionId"]) {
                null, JsonNull -> Unit
                else -> {
                    val value = (id as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (value == null || !isSessionId(value)) return reject(SessionImportReject.BAD_SESSION_ID)
                    val seen = sessionId
                    if (seen == null) sessionId = value
                    else if (seen != value) return reject(SessionImportReject.MIXED_SESSION_ID)
                }
            }

            val changed = rewriteTopLevelCwd(text, targetCwd)
            if (changed == null) {
                write(bytes, terminated)
            } else {
                rewritten++
                output.write(changed.encodeToByteArray())
                if (crlf) output.write(CR.toInt())
                if (terminated) output.write(LF.toInt())
            }
            return null
        }

        fun finish(): SessionImportScan {
            if (objects == 0) return SessionImportScan.Rejected(SessionImportReject.EMPTY)
            val id = sessionId ?: return SessionImportScan.Rejected(SessionImportReject.NO_SESSION_ID)
            return SessionImportScan.Ok(sessionId = id, cwdRewritten = rewritten)
        }

        private fun write(bytes: ByteArray, terminated: Boolean) {
            output.write(bytes)
            if (terminated) output.write(LF.toInt())
        }

        private fun reject(reason: SessionImportReject) = SessionImportScan.Rejected(reason, lineNo)
    }

    /**
     * 把一行（一个 JSON 对象）**顶层**的 `cwd` 字符串值换成 [targetCwd]，其余字符一个不动。
     * 返回 null 表示不用改：没有顶层 `cwd`、它不是字符串，或者本来就等于目标。
     *
     * 嵌套在里面的 `cwd`（工具输入、hook 附件里也可能有）不碰——那是对话内容，不是这一行的元数据。
     * 新值按 JSON 字符串字面量写（`JsonPrimitive.toString()`：转义引号、反斜杠、控制字符，
     * `/` 不转义，和 CLI 的 `JSON.stringify` 一致）。
     */
    fun rewriteTopLevelCwd(line: String, targetCwd: String): String? {
        val spans = topLevelValueSpans(line, "cwd").filter { line[it.first] == '"' }
        if (spans.isEmpty()) return null
        val literal = JsonPrimitive(targetCwd).toString()
        val out = StringBuilder(line)
        var changed = false
        // 从后往前换，前面的下标才不会被长度变化推歪
        for (span in spans.asReversed()) {
            val current = runCatching {
                (json.parseToJsonElement(line.substring(span.first, span.last + 1)) as JsonPrimitive).content
            }.getOrNull()
            if (current == targetCwd) continue
            out.replace(span.first, span.last + 1, literal)
            changed = true
        }
        return if (changed) out.toString() else null
    }

    /**
     * 顶层对象里键为 [key] 的值在 [json] 里的下标范围（含两端）。只认顶层：进了嵌套的对象 / 数组
     * 就整段跳过。键按原文比较——`"c\u0077d"` 这种转义写法的键认不出来，那只会少改一行，不会改错。
     * 重复键全部返回（`JSON.parse` 取最后一个，这里干脆都改）。
     *
     * 输入应当已经是合法 JSON；遇到对不上的结构直接停，返回已经找到的部分。
     */
    internal fun topLevelValueSpans(json: String, key: String): List<IntRange> {
        var i = skipWs(json, 0)
        if (i >= json.length || json[i] != '{') return emptyList()
        i++
        val out = ArrayList<IntRange>(1)
        while (true) {
            i = skipWs(json, i)
            if (i >= json.length || json[i] != '"') return out
            val keyEnd = skipString(json, i)
            val rawKey = json.substring(i + 1, (keyEnd - 1).coerceAtLeast(i + 1))
            i = skipWs(json, keyEnd)
            if (i >= json.length || json[i] != ':') return out
            i = skipWs(json, i + 1)
            val valueStart = i
            i = skipValue(json, i)
            if (rawKey == key && i > valueStart) out += valueStart until i
            i = skipWs(json, i)
            if (i < json.length && json[i] == ',') i++ else return out
        }
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        return i
    }

    /** [start] 指着开头的引号；返回收尾引号之后的下标 */
    private fun skipString(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return s.length
    }

    private fun skipValue(s: String, start: Int): Int {
        if (start >= s.length) return start
        return when (s[start]) {
            '"' -> skipString(s, start)
            '{', '[' -> {
                var depth = 0
                var i = start
                while (i < s.length) {
                    when (s[i]) {
                        '"' -> {
                            i = skipString(s, i)
                            continue
                        }
                        '{', '[' -> depth++
                        '}', ']' -> if (--depth == 0) return i + 1
                    }
                    i++
                }
                s.length
            }
            else -> {
                var i = start
                while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' &&
                    s[i] != ' ' && s[i] != '\t' && s[i] != '\r' && s[i] != '\n'
                ) i++
                i
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
