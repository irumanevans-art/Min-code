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

internal class BudgetExceeded(message: String = "会话已达到 --max-budget-usd，或缺少可核对的 USD 定价。") : IllegalStateException(message)

/** 用同一出口计价和限时，避免审查、提取或摘要偷偷绕过会话限制。 */
internal class ModelBilling(private val rt: SessionRuntime) {
    private val lock = Mutex()

    fun exceeded(): Boolean {
        if (rt.deps.maxBudgetUsd == null) return false
        // 判定在「一次运行」的账本上（整棵树、本次运行，见 [SessionHub.budget]）：会话自己折叠的 modelCosts
        // 带着续跑前的历史，且看不见树里别的会话花掉的，不能当预算用
        return rt.hub?.budget?.exceeded() ?: false
    }

    fun stream(model: ResolvedModel, request: ModelRequest) = flow {
        suspend fun collect() {
            val pricing = model.profile.pricing
            if (rt.deps.maxBudgetUsd != null && (exceeded() || pricing == null || pricing.currency != "USD")) throw BudgetExceeded()
            // 定时任务的这次运行有自己的单次上限（整棵会话树合计）：花到了、或这个模型没有 USD 价格（花费无法核对）就不再发请求
            val run = rt.treeCronRun()
            if (run != null) {
                val overrun = run.overrun()
                    ?: CronOverrun(CronOverrun.Cause.UnpricedModel, run.spentUsd(), run.limitUsd).takeIf { pricing == null || pricing.currency != "USD" }
                if (overrun != null) {
                    if (run.trip()) rt.hub?.crons?.tripped(run, overrun)
                    throw CronBudgetReached(overrun)
                }
            }
            val finished = withTimeoutOrNull(rt.deps.modelTimeoutMs) {
                model.client.stream(request.copy(timeoutMs = rt.deps.modelTimeoutMs)).collect { event ->
                    if (event is ModelStreamEvent.Completed) {
                        val charged = pricing?.let { cost(it, event.usage) }
                        rt.commit(listOf(ModelCharged(model.profile.ref, event.usage, charged?.amount, charged?.currency)), Durability.Sync)
                        run?.charge(charged?.amount, charged?.currency)
                    }
                    emit(event)
                }
                true
            }
            if (finished == null) throw ModelException(ModelFailureKind.Network, "模型请求超时", beforeFirstByte = false)
        }
        // 有预算时串行检查与扣账，避免同一会话的多个辅助请求同时读到尚未花费的余额。
        if (rt.deps.maxBudgetUsd == null && rt.treeCronRun() == null) collect() else lock.withLock { collect() }
    }
}
