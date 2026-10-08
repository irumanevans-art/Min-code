package dev.min.agent.kernel

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.cost
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class BudgetExceeded : IllegalStateException("会话已达到 --max-budget-usd，或缺少可核对的 USD 定价。")

/** 用同一出口计价和限时，避免审查、提取或摘要偷偷绕过会话限制。 */
internal class ModelBilling(private val rt: SessionRuntime) {
    private val lock = Mutex()

    fun exceeded(): Boolean {
        val limit = rt.deps.maxBudgetUsd ?: return false
        val state = rt.state.value
        return state.unpricedModelCalls > 0 || state.modelCosts.any { it.key != "USD" && it.value > 0 } ||
            state.modelCosts.getOrDefault("USD", 0.0) >= limit
    }

    fun stream(model: ResolvedModel, request: ModelRequest) = flow {
        suspend fun collect() {
            val pricing = model.profile.pricing
            if (rt.deps.maxBudgetUsd != null && (exceeded() || pricing == null || pricing.currency != "USD")) throw BudgetExceeded()
            val finished = withTimeoutOrNull(rt.deps.modelTimeoutMs) {
                model.client.stream(request.copy(timeoutMs = rt.deps.modelTimeoutMs)).collect { event ->
                    if (event is ModelStreamEvent.Completed) {
                        val charged = pricing?.let { cost(it, event.usage) }
                        rt.commit(listOf(ModelCharged(model.profile.ref, event.usage, charged?.amount, charged?.currency)), Durability.Sync)
                    }
                    emit(event)
                }
                true
            }
            if (finished == null) throw ModelException(ModelFailureKind.Network, "模型请求超时", beforeFirstByte = false)
        }
        // 有预算时串行检查与扣账，避免同一会话的多个辅助请求同时读到尚未花费的余额。
        if (rt.deps.maxBudgetUsd == null) collect() else lock.withLock { collect() }
    }
}
