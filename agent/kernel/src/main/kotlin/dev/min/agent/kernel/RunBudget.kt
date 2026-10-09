package dev.min.agent.kernel

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.SessionEvent

/**
 * 「一次运行」的花费账本：`--max-budget-usd`（本次 CLI 运行）与定时任务的单次上限（一次触发）共用，
 * 范围是**本次运行、整棵会话树**（CC changelog：cap reached → new spawns denied, running background agents
 * halted）——与 `--max-turns` 同一类运行时选项，resume 重放的旧回合不走提交、自然不进这本账。
 *
 * 数据从 [ModelCharged] 事件取：金额与币种在请求当时已冻结进日志，这里不查价、不做「量不到」的特判——
 * 设了上限时量不到价格的请求在发出前就被 [ModelBilling] 的预检拦下（fail-closed），漏网的只记进
 * [unpricedRequests] 供展示与超限原因用。喂账在 [SessionRuntime.commitIf]：树里哪个会话提交了
 * `model_charged`，就记到它的根会话名下（`-p` 结果按根汇总）；中途才被打开的老子会话的历史不属于这次运行。
 */
internal class RunBudget(internal val limitUsd: Double?) {
    private val lock = Any()

    /** 每棵树（根会话）一份；超限判定用所有树的合计（CLI 的一次运行就是一个进程） */
    private val roots = LinkedHashMap<SessionId, Spend>()

    private class Spend {
        var usd = 0.0
        var unpriced = 0
        var nonUsd = 0
        val byCurrency = LinkedHashMap<String, Double>()
        val perModel = LinkedHashMap<ModelRef, ModelSpend>()
    }

    /** 一个模型的累计：请求次数与用量（给 /cost 与 -p 结果的「各模型用量」） */
    internal class ModelSpend(var requests: Int, var usage: Usage)

    /** 提交事件时喂账：只认 [ModelCharged]，别的事件（含恢复补的收尾）不进这本「运行」账 */
    fun onCommit(root: SessionId, events: List<SessionEvent>) {
        for (event in events) {
            if (event is ModelCharged) charge(root, event)
        }
    }

    /** 记一笔（[ModelBilling] 结算与定时运行共用这个口）。amount / currency 缺失 = 花费未知（不是 0） */
    internal fun charge(root: SessionId, event: ModelCharged) = charge(root, event.model, event.usage, event.amount, event.currency)

    private fun charge(root: SessionId, model: ModelRef, usage: Usage, amount: Double?, currency: String?) {
        synchronized(lock) {
            val spend = roots.getOrPut(root) { Spend() }
            val prev = spend.perModel.getOrPut(model) { ModelSpend(0, Usage()) }
            prev.requests += 1
            prev.usage += usage
            when {
                amount == null || currency == null -> spend.unpriced += 1
                currency == "USD" -> {
                    spend.usd += amount
                    spend.byCurrency.merge("USD", amount, Double::plus)
                }
                else -> {
                    // 有价格但不是 USD：CLI 的 fail-closed 预检本就不放行，能到这里的只有没设上限的运行（照实记账）；
                    // 定时运行把它当「花费无法核对」处理（与旧 CronRun 的口径一致）
                    spend.nonUsd += 1
                    spend.byCurrency.merge(currency, amount, Double::plus)
                }
            }
        }
    }

    /** 上限到了没有。没设上限（null）恒为 false；只看 USD 合计，量不到的请求另有 [unpricedRequests] 汇报 */
    fun exceeded(): Boolean {
        val limit = limitUsd ?: return false
        return synchronized(lock) { roots.values.sumOf { it.usd } >= limit }
    }

    /** 所有树的 USD 合计（展示与超限说明用，与判定同一个数） */
    fun spentUsd(): Double = synchronized(lock) { roots.values.sumOf { it.usd } }

    /** 金额缺失的请求次数：花费未知，界面与 -p 结果都要说明 */
    fun unpricedRequests(): Int = synchronized(lock) { roots.values.sumOf { it.unpriced } }

    /** 非 USD 计价的请求次数：定时运行按「花费无法核对」到限处理 */
    fun nonUsdRequests(): Int = synchronized(lock) { roots.values.sumOf { it.nonUsd } }
}
