package dev.min.agent.spi

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NetworkSettings(
    val enabled: Boolean = false,
    val enabledManaged: Boolean = false,
    val allowedDomains: List<String> = emptyList(),
    val deniedDomains: List<String> = emptyList(),
    val allowManagedDomainsOnly: Boolean = false,
    val strictAllowlist: Boolean = false,
    val httpProxyPort: Int? = null,
    val socksProxyPort: Int? = null,
)

@Serializable enum class NetworkOutcome { @SerialName("allow") Allow, @SerialName("deny") Deny, @SerialName("ask") Ask }
@Serializable enum class NetworkSource {
    @SerialName("rule") Rule, @SerialName("user") User, @SerialName("managed_lock") ManagedLock,
    @SerialName("timeout") Timeout, @SerialName("mode") Mode, @SerialName("classifier") Classifier,
}
data class NetworkVerdict(val outcome: NetworkOutcome, val source: NetworkSource, val reason: String)
data class NetworkTarget(val domain: String, val port: Int)

/** 凭据仅属于宿主租约，不能进入设置、事件或模型上下文。 */
interface NetworkLease : AutoCloseable { val env: Map<String, String> }
interface NetworkProxy {
    fun open(session: SessionId, decide: suspend (NetworkTarget) -> NetworkVerdict): NetworkLease
    fun closeSession(session: SessionId)
}

@Serializable @SerialName("network_settings_loaded")
data class NetworkSettingsLoaded(val settings: NetworkSettings) : SessionEvent

@Serializable @SerialName("network_approval_requested")
data class NetworkApprovalRequested(
    val approvalId: ApprovalId, val callId: CallId, val domain: String, val port: Int,
    val commandSummary: String, val atMs: Long,
) : SessionEvent

@Serializable @SerialName("network_decision")
data class NetworkDecision(
    val callId: CallId, val domain: String, val port: Int, val outcome: NetworkOutcome,
    val source: NetworkSource, val reason: String, val approvalId: ApprovalId? = null,
) : SessionEvent
