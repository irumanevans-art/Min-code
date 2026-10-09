package dev.min.agent.ext.config

import dev.min.agent.spi.*
import dev.min.agent.protocol.PermissionMode
import kotlinx.serialization.json.*
import java.net.IDN
import java.net.InetAddress
import java.util.Locale

fun MergedSettings.networkSettings(trustProject: Boolean = true, trustedLocal: Set<GuestPath> = emptySet()): NetworkSettings {
    fun SettingsLayer.sandbox() = raw["sandbox"] as? JsonObject
    fun SettingsLayer.network() = sandbox()?.get("network") as? JsonObject
    fun JsonObject?.bool(key: String) = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    fun JsonObject?.port(key: String) = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..65535 }
    fun JsonObject?.strings(key: String) = (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    fun SettingsLayer.web(kind: RuleSpecKind): List<String> = (when (kind) {
        RuleSpecKind.Allow -> permissions.allow
        RuleSpecKind.Deny -> permissions.deny
        else -> emptyList()
    }).mapNotNull { rule -> Regex("^WebFetch\\(domain:([^()]+)\\)$").matchEntire(rule.trim())?.groupValues?.get(1) }
        .filter { NetworkRules.validPattern(it) }
    val effective = layers.filter { trustProject || it.source == ConfigSource.Managed || it.source == ConfigSource.User || it.source == ConfigSource.Local && it.path in trustedLocal }
    val managed = layers.lastOrNull { it.source == ConfigSource.Managed }
    val managedOnly = managed?.network().bool("allowManagedDomainsOnly") == true
    val adminRequired = managedOnly || managed?.sandbox().bool("allowUnsandboxedCommands") == false
    val strict = effective.filter { it.source == ConfigSource.Managed || it.source == ConfigSource.User }.any { it.network().bool("strictAllowlist") == true }
    val narrowLock = strict || layers.filter { it.source == ConfigSource.Managed }.any { it.network().strings("deniedDomains").isNotEmpty() || it.web(RuleSpecKind.Deny).isNotEmpty() }
    val allowLayers = effective.filter { if (managedOnly) it.source == ConfigSource.Managed else !adminRequired && !strict || it.source == ConfigSource.Managed || it.source == ConfigSource.User }
    val proxyLayers = effective.filter { if (managedOnly) it.source == ConfigSource.Managed else !adminRequired && !narrowLock || it.source == ConfigSource.Managed || it.source == ConfigSource.User }
    val enabledLayers = if (adminRequired && effective.any { it.source == ConfigSource.User && it.sandbox().bool("enabled") == true }) effective.filter { it.source == ConfigSource.User || it.source == ConfigSource.Managed } else effective
    return NetworkSettings(
        enabled = enabledLayers.asReversed().firstNotNullOfOrNull { it.sandbox().bool("enabled") } ?: false,
        enabledManaged = managed?.sandbox().bool("enabled") != null,
        allowedDomains = allowLayers.flatMap { it.network().strings("allowedDomains") + it.web(RuleSpecKind.Allow) }.distinct(),
        // 拒绝来源不能被项目未信任或 managed-only 抹掉，否则仓库限制会失效。
        deniedDomains = layers.flatMap { it.network().strings("deniedDomains") + it.web(RuleSpecKind.Deny) }.distinct(),
        allowManagedDomainsOnly = managedOnly,
        strictAllowlist = strict,
        httpProxyPort = proxyLayers.asReversed().firstNotNullOfOrNull { it.network().port("httpProxyPort") },
        socksProxyPort = proxyLayers.asReversed().firstNotNullOfOrNull { it.network().port("socksProxyPort") },
    )
}

object NetworkRules {
    fun normalizeHost(raw: String): String? {
        val host = raw.removeSurrounding("[", "]").trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty() || host.any { it.isWhitespace() || it in "/\\@?#%" }) return null
        if (':' in host) {
            if (!Regex("[0-9a-f:.]+").matches(host)) return null
            return try { InetAddress.getByName(host).hostAddress } catch (_: java.net.UnknownHostException) { null }
        }
        // JVM 与系统解析器对八进制、短 IPv4 和整数地址解释不一；规则和连接都只接受完整十进制。
        val labels = host.split('.')
        if (labels.all { it.all(Char::isDigit) || it.startsWith("0x") && it.drop(2).all { c -> c in "0123456789abcdef" } }) {
            if (labels.size != 4 || labels.any { !it.all(Char::isDigit) || it.length > 1 && it.startsWith('0') || it.toIntOrNull() !in 0..255 }) return null
            return labels.joinToString(".") { it.toInt().toString() }
        }
        return try { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) } catch (_: IllegalArgumentException) { null }
    }
    private fun parse(pattern: String): Pair<String, Int?>? {
        if (pattern.isEmpty() || pattern != pattern.trim()) return null
        val host: String
        val port: Int?
        if (pattern.startsWith('[')) {
            val end = pattern.indexOf(']')
            if (end < 0) return null
            host = pattern.substring(1, end)
            val suffix = pattern.substring(end + 1)
            port = if (suffix.isEmpty()) null else if (suffix.startsWith(':')) suffix.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null else return null
        } else {
            if (pattern.count { it == ':' } > 1) return null
            host = pattern.substringBefore(':')
            port = if (':' in pattern) pattern.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null else null
        }
        if (host == "*") return host to port
        val wildcard = host.startsWith("*.")
        if ('*' in host.removePrefix("*.")) return null
        val normalized = normalizeHost(if (wildcard) host.drop(2) else host) ?: return null
        return (if (wildcard) "*.$normalized" else normalized) to port
    }
    fun validPattern(pattern: String) = parse(pattern) != null
    fun matches(pattern: String, target: NetworkTarget): Boolean {
        val (host, port) = parse(pattern) ?: return false
        if (port != null && port != target.port) return false
        val actual = normalizeHost(target.domain) ?: return false
        return host == "*" || host == actual || host.startsWith("*.") && actual.endsWith(host.drop(1)) && actual.length > host.length - 1
    }
    fun evaluate(settings: NetworkSettings, target: NetworkTarget, mode: PermissionMode,
                 commandDomains: List<String> = emptyList()): NetworkVerdict {
        fun answer(outcome: NetworkOutcome, source: NetworkSource, reason: String) = NetworkVerdict(outcome, source, reason)
        if (normalizeHost(target.domain) == null || target.port !in 1..65535) return answer(NetworkOutcome.Deny, NetworkSource.Rule, "无效的域名或端口")
        if (settings.deniedDomains.any { matches(it, target) }) return answer(NetworkOutcome.Deny, NetworkSource.Rule, "域名命中拒绝规则")
        if (settings.allowedDomains.any { matches(it, target) }) return answer(NetworkOutcome.Allow, NetworkSource.Rule, "域名命中允许规则")
        if (settings.allowManagedDomainsOnly) return answer(NetworkOutcome.Deny, NetworkSource.ManagedLock, "只允许托管名单中的域名")
        if (settings.strictAllowlist) return answer(NetworkOutcome.Deny, NetworkSource.Rule, "严格名单拒绝未列出的域名")
        return when (mode) {
            PermissionMode.BypassPermissions -> answer(NetworkOutcome.Allow, NetworkSource.Mode, "跳过权限模式允许未列出的域名")
            PermissionMode.DontAsk -> answer(NetworkOutcome.Deny, NetworkSource.Mode, "dontAsk 模式拒绝未列出的域名")
            PermissionMode.Auto -> if (commandDomains.any { matches(it, target) }) answer(NetworkOutcome.Allow, NetworkSource.Classifier, "本命令已批准的域名")
                else answer(NetworkOutcome.Deny, NetworkSource.Mode, "auto 模式：域名未在本命令获批的 allowed_domains 中声明")
            else -> answer(NetworkOutcome.Ask, NetworkSource.Rule, "域名未列出，需要批准")
        }
    }
}
