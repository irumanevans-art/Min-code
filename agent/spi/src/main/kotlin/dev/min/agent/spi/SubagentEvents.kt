package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 子 agent（含分身 fork）进日志的形状。设计见 docs/agent/detail/子agent设计.md §2。
 * 单独一个文件放，和同时在改 SessionEvent.kt 其他部分的分支互不冲突；sealed 子类同包同模块即可。
 */

// ---- 父会话日志 ----

/**
 * 委派落盘。写在子会话日志**建好之后**、子回合开始之前：父会话永远不会引用一个不存在的子会话
 * （反过来，子日志建好了而这条没写成，子会话就是没开过回合的孤儿，可以安全清掉）。
 */
@Serializable
@SerialName("subagent_spawned")
data class SubagentSpawned(
    val turnId: TurnId,
    /** 哪一次 Agent 调用 */
    val callId: CallId,
    val child: SessionId,
    /** `general-purpose`、`Explore`、自定义名……；分身固定为 `fork` */
    val agentType: String,
    val fork: Boolean,
    val background: Boolean,
    /** 委派时给的 3–5 个词，界面上的标题 */
    val description: String,
    /** 模型给它起的名字（SendMessage 可以按名字找） */
    val name: String? = null,
) : SessionEvent

// ---- 子会话日志 ----

/**
 * 组系统提示要用的环境事实。分身必须用父会话的这几样，而不是自己的（日期、cwd 一变前缀就变，缓存全失）。
 * 字段与 `dev.min.agent.context.Environment` 一一对应。
 */
@Serializable
data class PromptBasis(val platform: String, val cwd: String, val date: String)

/** 子会话的来历。紧跟在 SessionCreated 之后 */
@Serializable
@SerialName("subagent_origin")
data class SubagentOrigin(
    val parent: SessionId,
    /** 这棵子 agent 树的根会话（界面画树、新开子 agent 的缓存键都用它） */
    val root: SessionId,
    val parentCall: CallId,
    val agentType: String,
    val fork: Boolean,
    /** 根会话 = 0，它的子 agent = 1…… */
    val depth: Int,
    /** 分身：父会话的环境事实；新开的子 agent 为 null（用自己的） */
    val promptBasis: PromptBasis? = null,
    /**
     * 请求的缓存键。分身沿用父会话的（一路继承到根），OpenAI 系的 prompt_cache_key 因此落到同一个缓存分片；
     * 新开的按「类型 + 根会话」给，同类型反复委派也能命中
     */
    val cacheKey: String,
    /** 新开的子 agent 能用的工具（名字）；null = 全部。分身总是 null：工具表一个都不能删，否则前缀就变了 */
    val tools: List<String>? = null,
    /** 新开的子 agent 的角色说明（定义文件正文），拼在系统提示末尾；分身为 null */
    val instructions: String? = null,
    /** 回合里模型请求次数上限（定义的 maxTurns）；null = 内核默认 */
    val maxTurns: Int? = null,
) : SessionEvent

/** 分身继承的一条对话消息：与父会话里那条逐字节相同（含消息 id） */
@Serializable
data class SeedMessage(val messageId: MessageId, val role: Role, val parts: List<Part>)

@Serializable
data class SeedResult(val callId: CallId, val result: ToolResult)

/**
 * 分身继承的压缩视图（父会话压缩过时才有）：字段与父会话最后一次 [ContextCompacted] 相同，
 * 另带挂在摘要末尾的 hook 上下文（SessionStart source = compact）。折叠时分身的模型视图从这份摘要开始，
 * 与父会话的下一个请求逐字相同——没有它，分身会回放出被摘要掉的原始对话，前缀反而对不上。
 */
@Serializable
data class SeedCompaction(
    val compactionId: String,
    val summary: String,
    val through: MessageId,
    val transcriptPath: String? = null,
    /** 挂在这份摘要末尾的 hook 上下文，按父会话里的顺序 */
    val contexts: List<String> = emptyList(),
)

/**
 * 分身继承的对话：父会话到委派点为止的全部消息（最后一条是带 fork 调用的那条助手消息），
 * 以及这些消息里每个工具调用的结果——更早的用父会话的真实结果，委派那条消息里的调用用合成结果
 * （fork 调用 → 分身指令；同一条消息里的其他调用 → 「由主会话执行，你看不到结果」）。
 * 父会话压缩过时 [compaction] 带上压缩视图，messages 只从摘要边界开始（边界前的原始对话不进分身日志）。
 *
 * 复制而不是引用父日志：子日志要能单独折叠，父日志丢了坏了也不连累它（设计 §2.2）。
 */
@Serializable
@SerialName("fork_seeded")
data class ForkSeeded(
    val messages: List<SeedMessage>,
    val results: List<SeedResult>,
    val compaction: SeedCompaction? = null,
) : SessionEvent

// ---- 输入的来历 ----

/** 一条输入是谁送来的。界面据此决定怎么显示（用户气泡 / 子 agent 报告卡 / 委派说明） */
@Serializable
sealed interface InputOrigin {
    @Serializable
    @SerialName("user")
    data object User : InputOrigin

    /** 父会话视角：子 agent 交回报告，或子 agent 用 SendMessage 发来消息 */
    @Serializable
    @SerialName("agent")
    data class Agent(
        val session: SessionId,
        val kind: AgentInputKind,
        /** 报告对应子会话的哪个回合（去重键的一部分） */
        val childTurn: TurnId? = null,
    ) : InputOrigin

    /** 子会话视角：父会话委派过来的任务，或父会话用 SendMessage 发来的话 */
    @Serializable
    @SerialName("parent")
    data class Parent(val session: SessionId) : InputOrigin

    /** hook 送来的话（Stop hook 拦下了「结束」，把理由交给模型让它接着做） */
    @Serializable
    @SerialName("hook")
    data class Hook(val event: String) : InputOrigin
}

@Serializable
enum class AgentInputKind {
    @SerialName("report") Report,
    @SerialName("message") Message,
}
