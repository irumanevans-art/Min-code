package dev.min.agent.testkit

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/*
 * 回合测试用的替身：脚本化的模型、只记录不碰外界的工具、不该被用到的宿主端口。
 */

val TEST_MODEL = ModelRef("test", "scripted")

val TEST_PROFILE = ModelProfile(
    ref = TEST_MODEL, family = ModelFamily.Other, contextWindow = 100_000, maxOutputTokens = 4_000,
)

typealias Step = (ModelRequest) -> Flow<ModelStreamEvent>

/** 按顺序回放的模型；记下收到的每个请求。脚本用完还被调用就抛错 */
class ScriptedModel(vararg steps: Step) : ModelClient {
    private val queue = ArrayDeque(steps.toList())
    val requests = mutableListOf<ModelRequest>()

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        synchronized(requests) { requests += request }
        val step = synchronized(queue) { queue.removeFirstOrNull() } ?: error("模型脚本用完了，却又收到请求")
        emitAll(step(request))
    }
}

fun reply(
    vararg parts: Part,
    stop: StopReason = StopReason.EndTurn,
    truncated: Boolean = false,
    usage: Usage = Usage(inputTokens = 10, outputTokens = 5),
): Step = {
    flowOf(
        ModelStreamEvent.Started(null),
        ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts.toList()), usage, stop, truncated),
    )
}

fun failing(error: Throwable): Step = { flow { throw error } }
