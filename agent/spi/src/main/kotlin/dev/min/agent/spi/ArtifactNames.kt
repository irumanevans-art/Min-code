package dev.min.agent.spi

import java.security.MessageDigest

/**
 * 会话附件的名字（[ArtifactSink.put] 的参数：相对会话附件目录的路径，可带子目录，如 `tool-results/<id>.txt`）。
 *
 * 名字里常带模型供应商给的调用 id——那是外来的字符串：带 `/`、`..` 能跳出附件目录，带 `:` 一类字符
 * （有的供应商的 id 形如 `functions.bash:0`）在宿主的校验下整次落盘失败。所以分两道：
 * 产出名字的地方用 [segment] 把外来部分洗成安全的一段；宿主落盘前用 [validate] 再查一遍整名。
 * 两个都是纯函数，桌面与 Android 两个宿主共用同一套判定。
 */
object ArtifactNames {
    private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
    private val UNSAFE_CHAR = Regex("[^A-Za-z0-9._-]")
    private const val MAX_SEGMENT = 80

    /**
     * 外来字符串 → 一段安全的名字。本来就安全的原样返回（常见的 `toolu_…`、`call_…` 名字不变）；
     * 改过字符或截短过的，后面接原文的短哈希——`a:b` 与 `a_b` 洗完不能撞成同一个文件、互相覆盖。
     */
    fun segment(raw: String): String {
        if (raw.length <= MAX_SEGMENT && raw.matches(SAFE_SEGMENT) && raw != "." && raw != "..") return raw
        val cleaned = raw.replace(UNSAFE_CHAR, "_").take(MAX_SEGMENT - 9).trim('.')
        return (if (cleaned.isEmpty()) "x" else cleaned) + "-" + shortHash(raw)
    }

    /**
     * 整名能不能落盘：按 `/` 分段，每段非空、只含 `[A-Za-z0-9._-]`、不是 `.` / `..`——这样名字跳不出会话的附件目录。
     * 合格原样返回，不合格抛 [IllegalArgumentException]（是产出名字一方的缺陷，不该静默改名后照写）
     */
    fun validate(name: String): String {
        require(name.split('/').all { it.matches(SAFE_SEGMENT) && it != "." && it != ".." }) { "附件名不合法：$name" }
        return name
    }

    private fun shortHash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
}
