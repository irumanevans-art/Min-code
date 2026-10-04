package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * 企业管控的 allow / deny 两张表（cc-managed-mcp.md「Policy-based control」）。
 * 条目三选一：serverName（字面精确，不展开）、serverCommand（命令 + 参数逐项精确）、serverUrl（`*` 通配）。
 * 未设 allowed = 全放行；`[]` = 全不放行；deny 永远优先；allowlist 一旦含 serverUrl 项，远程服务器就必须匹配它
 * （serverName 不再放行远程），含 serverCommand 项时 stdio 同理。serverCommand / serverUrl 两边的值都先做
 * `${VAR}` 展开再比（两张表展开用的「钉死环境」不同，见 [fromLayers]）。
 */

/** 一条管控条目，解析自 settings 的 `allowedMcpServers` / `deniedMcpServers` 数组里的一项 */
internal sealed interface McpPolicyEntry {
    /** 条目写给 note / reason 用的描述（只含管理员自己写的策略内容，不含服务器侧的配置值） */
    val describe: String

    data class Name(val name: String) : McpPolicyEntry {
        override val describe: String get() = "serverName=$name"
    }

    data class Command(val argv: List<String>) : McpPolicyEntry {
        override val describe: String get() = "serverCommand=${argv.joinToString(" ")}"
    }

    data class Url(val pattern: String) : McpPolicyEntry {
        override val describe: String get() = "serverUrl=$pattern"
    }
}

/**
 * 两张表合一的求值器。[allowed] 为 null 表示所有层都没设（不设这道闸）；空列表是「设了、一个都不放」。
 * 组织自己的服务器（managedMcpServers、不含 `${VAR}` 的 managed-mcp.json 条目）跳过 allowlist，由调用方先判。
 */
internal class McpPolicy(val allowed: List<McpPolicyEntry>?, val denied: List<McpPolicyEntry>) {

    /** deny 命中（URL / 命令各按服务器类型对口，名字对两类都算）。deny 对一切来源生效，没有豁免 */
    fun deniedBy(spec: McpServerSpec): McpPolicyEntry? = when (spec) {
        is McpServerSpec.Remote -> denied.firstOrNull { entry ->
            (entry is McpPolicyEntry.Name && entry.name == spec.name) ||
                (entry is McpPolicyEntry.Url && urlMatches(entry.pattern, spec.url))
        }

        is McpServerSpec.Stdio -> denied.firstOrNull { entry ->
            (entry is McpPolicyEntry.Name && entry.name == spec.name) ||
                (entry is McpPolicyEntry.Command && entry.argv == spec.commandLine())
        }
    }

    /** allowlist 判定（未设 = 全放行）。表里有 serverUrl 项时远程必须匹配 URL；有 serverCommand 项时 stdio 必须匹配命令 */
    fun allowedBy(spec: McpServerSpec): Boolean {
        if (allowed == null) return true
        return when (spec) {
            is McpServerSpec.Remote -> {
                val urls = allowed.filterIsInstance<McpPolicyEntry.Url>()
                urls.any { urlMatches(it.pattern, spec.url) } ||
                    (urls.isEmpty() && allowed.any { it is McpPolicyEntry.Name && it.name == spec.name })
            }

            is McpServerSpec.Stdio -> {
                val commands = allowed.filterIsInstance<McpPolicyEntry.Command>()
                commands.any { it.argv == spec.commandLine() } ||
                    (commands.isEmpty() && allowed.any { it is McpPolicyEntry.Name && it.name == spec.name })
            }
        }
    }

    companion object {
        private fun McpServerSpec.Stdio.commandLine(): List<String> = listOf(command) + args

        /**
         * URL 通配匹配（cc-managed-mcp.md 的表与规则）：`*` 在任何位置都算通配（含协议段）；协议与主机名不区分大小写、
         * 主机名末尾的 `.`（FQDN 写法）等价于没有；路径与查询区分大小写；**模式里不写路径 = 该域的任意路径**。
         */
        fun urlMatches(pattern: String, url: String): Boolean {
            val p = canonical(pattern)
            val hasPath = p.substringAfter("://", "").any { it == '/' || it == '?' || it == '#' }
            // 模式停在授权段（没有路径）→ 任意路径都算：`https://mcp.example.com` 等价于在该域上任意匹配
            val padded = if (hasPath) p else "$p/*"
            val regex = padded.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex(regex, RegexOption.DOT_MATCHES_ALL).matches(canonical(url))
        }

        /** 协议与主机名转小写、主机名末尾的点去掉；`*` 原样保留（模式里协议 / 主机也可能带通配） */
        private fun canonical(raw: String): String {
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd < 0) return raw
            val authorityStart = schemeEnd + 3
            val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) raw.length else it }
            val authority = raw.substring(authorityStart, authorityEnd)
            // 主机在端口之前；IPv6 方括号里的冒号不是端口分隔
            val hostEnd = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') } ?: authority.length
            val host = authority.substring(0, hostEnd).lowercase().removeSuffix(".")
            return raw.substring(0, schemeEnd).lowercase() + "://" + host + authority.substring(hostEnd) + raw.substring(authorityEnd)
        }

        /**
         * 从 settings 各层抽出两张表合成一个求值器。
         * - allowlist 从**每个作用域**合并，但 `allowManagedMcpServersOnly`（只认 managed 层）为真时只剩 managed 层的
         *   ——用户自己的 settings 不能放宽企业的 allowlist；denylist 恒从所有层合并，用户永远能给自己加禁令。
         * - 条目值里的 `${VAR}` 也展开，且用「钉死环境」而非会话环境（原文「How policy entries expand」）：
         *   allowed 叠 managed 层的 env，denied 再叠 user 层的 env（settings 只会**放宽** deny 的匹配面，不会收紧 allow）。
         */
        fun fromLayers(
            layers: List<SettingsLayer>,
            startupEnv: (String) -> String?,
            notes: MutableList<String>,
        ): McpPolicy {
            val managedLayers = layers.filter { it.source == ConfigSource.Managed }
            val managedOnly = managedLayers.any { it.raw.boolOf("allowManagedMcpServersOnly") == true }
            if (managedOnly) notes += "allowManagedMcpServersOnly 生效：allowedMcpServers 只认 managed 层，用户 / 项目层的清单被忽略"
            val allowLayers = if (managedOnly) managedLayers else layers
            val managedEnv = managedLayers.flatMap { it.env.entries }
            val allowLookup: (String) -> String? = { name -> managedEnv.firstOrNull { it.key == name }?.value ?: startupEnv(name) }
            val userEnv = layers.filter { it.source == ConfigSource.User }.flatMap { it.env.entries }
            val denyLookup: (String) -> String? = { name -> startupEnv(name) ?: userEnv.firstOrNull { it.key == name }?.value }

            val allowedDefined = allowLayers.any { it.raw.hasEntry("allowedMcpServers") }
            val allowed = if (allowedDefined) collect(allowLayers, "allowedMcpServers", allowLookup, strictUrl = true, notes) else null
            val denied = collect(layers, "deniedMcpServers", denyLookup, strictUrl = false, notes)
            return McpPolicy(allowed, denied)
        }

        /** 一组层的同一张表合并。坏了的条目跳过并记 note，不拖垮整张表 */
        private fun collect(
            layers: List<SettingsLayer>,
            key: String,
            lookup: (String) -> String?,
            strictUrl: Boolean,
            notes: MutableList<String>,
        ): List<McpPolicyEntry> {
            val out = mutableListOf<McpPolicyEntry>()
            for (layer in layers) {
                val value = layer.raw[key] ?: continue
                if (value is JsonNull) continue
                if (value !is JsonArray) {
                    notes += "跳过 ${layer.path.value} 的 `$key`：期望数组，实际是${describeJson(value)}"
                    continue
                }
                value.forEachIndexed { index, item ->
                    parseEntry(item, layer.path.value, "$key[$index]", key, lookup, strictUrl, notes)?.let(out::add)
                }
            }
            return out
        }

        /** 一个条目 → 三选一；形状不对（不是对象、不是恰好一个认识的键、值类型不对）跳过并记 note */
        private fun parseEntry(
            item: JsonElement,
            file: String,
            where: String,
            key: String,
            lookup: (String) -> String?,
            strictUrl: Boolean,
            notes: MutableList<String>,
        ): McpPolicyEntry? {
            fun skip(why: String) {
                notes += "跳过 $file 的 `$where`：$why"
            }

            if (item !is JsonObject || item.size != 1) {
                skip("条目应只含 serverName / serverCommand / serverUrl 中的一个键，实际是${describeJson(item)}")
                return null
            }
            val (name, value) = item.entries.single()
            return when (name) {
                "serverName" -> {
                    val text = value.primitiveStringOrNull()
                    if (text == null) {
                        skip("serverName 应是字符串，实际是${describeJson(value)}")
                        null
                    } else if (key == "deniedMcpServers") {
                        // denylist 的 serverName 放得宽：任何非空、无首尾空白的串（要能按 claude.ai 连接器的显示名禁）
                        if (text.isEmpty() || text.trim() != text) {
                            skip("serverName「$text」为空或带首尾空白")
                            null
                        } else McpPolicyEntry.Name(text)
                    } else {
                        if (text.isEmpty() || !text.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                            skip("serverName「$text」只许字母数字连字符下划线")
                            null
                        } else McpPolicyEntry.Name(text)
                    }
                }

                "serverCommand" -> {
                    if (value !is JsonArray) {
                        skip("serverCommand 应是字符串数组，实际是${describeJson(value)}")
                        null
                    } else {
                        // 命令逐项精确匹配，元素也过一遍 ${VAR}（两边同一套展开，未设的都保留字面，字面 == 字面照样相等）
                        val argv = value.map { el -> el.primitiveStringOrNull() }
                        if (argv.any { it == null }) {
                            skip("serverCommand 的数组元素应是字符串")
                            null
                        } else McpPolicyEntry.Command(argv.map { McpVars.expand(it!!, lookup, credentialEmpty = false).value })
                    }
                }

                "serverUrl" -> {
                    val text = value.primitiveStringOrNull()
                    when {
                        text == null -> {
                            skip("serverUrl 应是字符串，实际是${describeJson(value)}")
                            null
                        }

                        else -> {
                            val expanded = McpVars.expand(text, lookup, credentialEmpty = false)
                            if (expanded.unresolved.isNotEmpty() && strictUrl) {
                                // allowlist 的 URL 条目展开不完整就没法核它的范围，宁可不用（保守 = 少放行）
                                skip("serverUrl 引用了未设置的变量（${expanded.unresolved.joinToString("、")}），无法核定范围")
                                null
                            } else if (strictUrl && scopeChanged(text, expanded.value)) {
                                skip("serverUrl 展开改变了协议或主机，范围不可信")
                                null
                            } else McpPolicyEntry.Url(expanded.value)
                        }
                    }
                }

                else -> {
                    skip("不认识的键「$name」")
                    null
                }
            }
        }

        /** allowlist 的 URL 条目展开前后协议 / 主机变了 → 原文的「Claude Code ignores the entry」 */
        private fun scopeChanged(before: String, after: String): Boolean = schemeHostOf(before) != schemeHostOf(after)

        private fun schemeHostOf(u: String): String {
            val schemeEnd = u.indexOf("://")
            if (schemeEnd < 0) return u.substringBefore('/').lowercase()
            val authorityStart = schemeEnd + 3
            val authorityEnd = u.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) u.length else it }
            return u.substring(0, schemeEnd).lowercase() + "://" + u.substring(authorityStart, authorityEnd).lowercase().removeSuffix(".")
        }

        private fun describeJson(v: JsonElement): String = when (v) {
            is JsonObject -> "对象"
            is JsonArray -> "数组"
            JsonNull -> "null"
            is JsonPrimitive -> if (v.isString) "字符串" else "非字符串字面量"
        }

        private fun JsonElement.primitiveStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.boolOf(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.hasEntry(key: String): Boolean = containsKey(key) && this[key] !is JsonNull
    }
}
