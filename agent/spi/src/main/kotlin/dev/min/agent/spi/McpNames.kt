package dev.min.agent.spi

import java.security.MessageDigest

object McpNames {
    /** OpenAI 系函数名上限 64，取各家最严 */
    const val MAX_LENGTH = 64

    private val ILLEGAL = Regex("[^A-Za-z0-9_-]")

    fun sanitize(part: String): String = ILLEGAL.replace(part, "_")

    /**
     * 每个 (服务器, 工具) 原名 → 全名 `mcp__服务器__工具`。按原名排序后依次分配：先到的用干净的名字，
     * 超长或清洗后撞名的截断并追加原名的 8 位哈希（Codex 的做法：确定性，同一组工具永远得到同一组名字）
     */
    fun assign(pairs: Collection<Pair<String, String>>): Map<Pair<String, String>, String> {
        val result = LinkedHashMap<Pair<String, String>, String>()
        val used = HashSet<String>()
        for (pair in pairs.distinct().sortedWith(compareBy({ it.first }, { it.second }))) {
            val base = "mcp__${sanitize(pair.first)}__${sanitize(pair.second)}"
            var name = if (base.length <= MAX_LENGTH) base else hashed(base, pair)
            if (name in used) name = hashed(base, pair)
            var n = 2
            val stem = name
            while (name in used) name = stem.take(MAX_LENGTH - 3) + "_" + n++
            used += name
            result[pair] = name
        }
        return result
    }

    private fun hashed(base: String, pair: Pair<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${pair.first}\u0000${pair.second}".toByteArray(Charsets.UTF_8))
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return base.take(MAX_LENGTH - hash.length - 1) + "_" + hash
    }
}
