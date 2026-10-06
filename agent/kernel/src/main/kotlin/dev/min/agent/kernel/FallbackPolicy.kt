package dev.min.agent.kernel

import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFailureKind

/** 模型回退链的两条纯规则：什么失败才换、换给谁（配置解析在 [FallbackChain]，执行在 [TurnRunner]） */
internal object FallbackPolicy {
    /**
     * 重试用尽后值得换模型的失败。照 CC：过载、不可用、其他服务端错误才换；鉴权、计费、限流、请求格式、
     * 上下文超长、网络（传输层）都不换——换模型解决不了，还会掩盖真问题（key 错了却悄悄去用另一家）。
     * [ModelFailureKind.Overloaded] / [ModelFailureKind.Server] 只有调用方确认「重试已经用尽」才该问这里
     */
    fun triggers(kind: ModelFailureKind): Boolean =
        kind == ModelFailureKind.Overloaded || kind == ModelFailureKind.Server || kind == ModelFailureKind.ModelUnavailable

    /**
     * 链上排在 [current] 之后的备用模型，按顺序。主模型 [primary] 与重复项不进候选；
     * [current] 不在链上（主模型本身，或回合中途被 `/model` 改了）就从链头开始
     */
    fun after(chain: List<ModelRef>, primary: ModelRef, current: ModelRef): List<ModelRef> {
        val candidates = chain.filter { it != primary }.distinct()
        val at = candidates.indexOf(current)
        return if (at < 0) candidates else candidates.drop(at + 1)
    }
}
