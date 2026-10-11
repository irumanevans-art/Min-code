package dev.min.agent.kernel

import dev.min.agent.ext.config.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private val networkSettingsWriteLock = Mutex()

/** 每次请求重读名单，撤销立即生效；每次变化先冻结日志，重放不依赖磁盘当前内容。 */
internal suspend fun SessionRuntime.refreshNetworkSettings(): NetworkSettings {
    val home = deps.memoryHome ?: return state.value.networkSettings
    val cwd = state.value.settings.cwd
    val snapshot = SettingsLoader.load(deps.host.fs, cwd, home, inputs = deps.settingsInputs)
    val trust = deps.trustProjectHooks(cwd)
    val locals = trustedLocalSettings(deps, snapshot, cwd, trust || state.value.created.headless)
    // 企业策略损坏不能放宽网络，也不能让纯本地回合失败；强制代理拒绝所有目标，关在连接这一侧。
    val settings = if (snapshot.managedPolicyBroken) NetworkSettings(enabled = true, enabledManaged = true,
        deniedDomains = listOf("*"), allowManagedDomainsOnly = true, strictAllowlist = true)
    else snapshot.merged.networkSettings(trust, locals)
    if (settings != state.value.networkSettings) commit(listOf(NetworkSettingsLoaded(settings)), Durability.Sync)
    return settings
}

/**
 * Bash、Monitor、Skill 预执行三条路径起 shell 的唯一出口。权限判定与网络批准都在它之前 / 之内按**原文**命令做；
 * `CLAUDE_ENV_FILE` 的前导是在这里、判定之后才接到进程脚本前的（[SessionEnvFiles]），所以执行它不改变任何判定。
 */
internal suspend fun SessionRuntime.runNetworkShell(callId: CallId, request: ShellCommand, options: ExecOptions, domains: List<String>): ExecResult {
    val command = envFiles.arm(request)
    val settings = refreshNetworkSettings()
    if (!settings.enabled) return deps.host.exec.run(command.copy(network = GuestNetwork.Offline), options)
    require(domains.all(NetworkRules::validPattern)) { "allowed_domains 含无效的域名或端口" }
    if ((settings.allowManagedDomainsOnly || settings.strictAllowlist) && state.value.settings.mode == PermissionMode.Auto && domains.isNotEmpty())
        return ExecResult.Exited(1, "网络名单已锁定，不能使用本命令的 allowed_domains。", false, 0)
    // 自有代理端口照 CC 交由该代理过滤；它们不是 Min 内置代理的监听端口。
    if (settings.httpProxyPort != null || settings.socksProxyPort != null) {
        val http = settings.httpProxyPort?.let { "http://127.0.0.1:$it" }
        val socks = settings.socksProxyPort?.let { "socks5h://127.0.0.1:$it" }
        val lease = if (http == null) requireNotNull(deps.networkProxy) { "网络代理不可用" }.open(sessionId) { target -> decideNetwork(callId, command.script, target, domains) } else null
        val env = networkEnvironment(http ?: requireNotNull(lease).env.getValue("HTTP_PROXY"), socks)
        var background = false
        try { return deps.host.exec.run(command.copy(env = command.env + env, network = dev.min.agent.policy.GuestIsolationPolicy.proxyNetwork(env)), options).also { result ->
            background = result is ExecResult.Backgrounded
            if (result is ExecResult.Backgrounded && lease != null) watchNetworkLease(result.handle, lease, options.timeoutMs)
        } }
        finally { if (!background) lease?.close() }
    }
    val proxy = requireNotNull(deps.networkProxy) { "沙箱网络代理不可用，命令未执行" }
    val lease = proxy.open(sessionId) { target -> decideNetwork(callId, command.script, target, domains) }
    var background = false
    try { return deps.host.exec.run(command.copy(env = command.env + lease.env, network = dev.min.agent.policy.GuestIsolationPolicy.proxyNetwork(lease.env)), options).also { result ->
        background = result is ExecResult.Backgrounded
        if (result is ExecResult.Backgrounded) watchNetworkLease(result.handle, lease, options.timeoutMs)
    } }
    finally { if (!background) lease.close() }
}

internal fun networkEnvironment(http: String, socks: String? = null): Map<String, String> = buildMap {
    for (key in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) { val value = if (key == "ALL_PROXY") socks ?: http else http; put(key, value); put(key.lowercase(), value) }
    put("NO_PROXY", "localhost,127.0.0.1,::1"); put("no_proxy", "localhost,127.0.0.1,::1")
}

internal suspend fun SessionRuntime.decideNetwork(callId: CallId, command: String, target: NetworkTarget, domains: List<String>): NetworkVerdict {
    val settings = refreshNetworkSettings()
    val verdict = NetworkRules.evaluate(settings, target, state.value.settings.mode, domains)
    if (verdict.outcome != NetworkOutcome.Ask) {
        commit(listOf(NetworkDecision(callId, target.domain, target.port, verdict.outcome, verdict.source, verdict.reason)), Durability.Sync)
        return verdict
    }
    val id = ApprovalId(deps.ids.next("network"))
    commit(listOf(NetworkApprovalRequested(id, callId, target.domain, target.port, command.take(80), deps.clock.nowMs())), Durability.Sync)
    try {
        val decision = withTimeoutOrNull(deps.networkApprovalTimeoutMs) {
            state.first { s -> id !in s.networkPending }.networkDecisions.lastOrNull { it.approvalId == id }
        }
        if (decision != null) return NetworkVerdict(decision.outcome, decision.source, decision.reason)
        networkSettingsWriteLock.withLock {
            commitIf(listOf(NetworkDecision(callId, target.domain, target.port, NetworkOutcome.Deny, NetworkSource.Timeout, "网络批准超时，连接已拒绝", id)), Durability.Sync) { id in it.networkPending }
        }
        val result = state.value.networkDecisions.last { it.approvalId == id }
        return NetworkVerdict(result.outcome, result.source, result.reason)
    } catch (e: CancellationException) {
        withContext(NonCancellable) { networkSettingsWriteLock.withLock {
            commitIf(listOf(NetworkDecision(callId, target.domain, target.port, NetworkOutcome.Deny, NetworkSource.Timeout, "命令或连接已取消", id)), Durability.Sync) { id in it.networkPending }
        } }
        throw e
    }
}

internal suspend fun SessionRuntime.answerNetworkApproval(id: ApprovalId, choice: ApprovalChoice): Boolean = networkSettingsWriteLock.withLock {
    val pending = state.value.networkPending[id] ?: return@withLock false
    val target = NetworkTarget(pending.domain, pending.port)
    val settings = refreshNetworkSettings()
    val policy = NetworkRules.evaluate(settings, target, PermissionMode.Default)
    var reason = "用户允许本次网络请求"
    var outcome = if (choice is ApprovalChoice.Deny || policy.outcome == NetworkOutcome.Deny) NetworkOutcome.Deny else NetworkOutcome.Allow
    var source = if (policy.outcome == NetworkOutcome.Deny) policy.source else NetworkSource.User
    if (policy.outcome == NetworkOutcome.Deny) reason = policy.reason
    else if (choice is ApprovalChoice.Deny) reason = choice.note ?: "用户拒绝此域名"
    else if (choice is ApprovalChoice.Always) {
        try {
            val home = requireNotNull(deps.memoryHome) { "没有用户设置目录" }
            val entry = (if (':' in pending.domain) "[${pending.domain}]" else pending.domain) + ":${pending.port}"
            NetworkSettingsEditor.allow(deps.host.fs, home, entry)
            refreshNetworkSettings()
            reason = "用户总是允许，已写入用户层 sandbox.network.allowedDomains"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { outcome = NetworkOutcome.Deny; source = NetworkSource.User; reason = "无法保存允许域名，本次连接已拒绝" }
    }
    commitIf(listOf(NetworkDecision(pending.callId, pending.domain, pending.port, outcome, source, reason, id)), Durability.Sync) { id in it.networkPending } != null
}
