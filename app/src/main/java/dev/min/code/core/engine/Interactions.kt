package dev.min.code.core.engine

import dev.min.code.core.session.PathScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 会话停下来等人的三种事：审批、提问、计划审批。合成一个类型，界面只有一处分发（`InteractionSheet`），
 * 三张卡片仍是原来的组件。
 *
 * 三个引擎怎么填：
 *
 * | 引擎 | Approval 的选项 | Question | PlanReview |
 * |---|---|---|---|
 * | Claude Code | 允许；每条 `PermissionSuggestion` 一个「始终允许」；拒绝并停止；拒绝并说明 | `AskUserQuestion` | `ExitPlanMode` |
 * | Codex | 按 `availableDecisions` 映射 | 无 | 无 |
 * | Min Agent | 允许；拒绝；拒绝并停止 | `AskedQuestion` | `ExitPlanMode` 的审批 |
 */
sealed interface PendingInteraction {
    /**
     * 会话内唯一，答复时原样带回（[SessionCommand.Answer.interactionId]）。CC：requestId；Codex：JSON-RPC id；
     * Min：`"<sessionId>/<approvalId>"`（[InteractionIds]），子会话上浮的交互也不会撞号。
     */
    val id: String
    val origin: InteractionOrigin
    val toolUseId: String?

    data class Approval(
        override val id: String,
        override val origin: InteractionOrigin,
        override val toolUseId: String?,
        val toolName: String,
        val input: JsonObject,
        /** 卡片标题 */
        val summary: String,
        /** 引擎给的说明原文（CC：`description`）；没有就是 null。[summary] 没有它时退回工具名，界面要分开说就读这个 */
        val description: String? = null,
        /** 这次调用要碰的路径与档位（Workspace / Rootfs / Device），Device 用朱标：它改的是手机上的真实文件 */
        val paths: List<PathView>,
        val commands: List<String>,
        val domains: List<String>,
        val reason: String?,
        val blockedPath: String?,
        val options: List<ApprovalOption>,
    ) : PendingInteraction {
        /** 通知上能放的按钮：只放 [ApprovalOption.notificationSafe] 的（允许、拒绝），其余必须回 App 里看 */
        val notificationOptions: List<ApprovalOption> get() = options.filter { it.notificationSafe }
    }

    data class Question(
        override val id: String,
        override val origin: InteractionOrigin,
        override val toolUseId: String?,
        /** CC 与内核同形：question、header、options(label, description)、multiSelect */
        val questions: List<QuestionView>,
        /** 这是 MCP 服务器在向用户要信息（elicitation）而不是模型在提问：服务器名。null = 模型的 AskUserQuestion */
        val server: String? = null,
        /**
         * MCP 的 url 模式：服务器请用户去一个网址完成一件事。非空时不画选项，画一张显示服务器、完整 URL 与高亮域名的卡，
         * 用户点「打开」才由 App 打开（MCP 规范：不预取、不自动打开）。答复：打开 = [InteractionAnswer.Answer] 选 `y`，拒绝 = [InteractionAnswer.Decline]
         */
        val url: UrlRequest? = null,
    ) : PendingInteraction

    data class PlanReview(
        override val id: String,
        override val origin: InteractionOrigin,
        override val toolUseId: String?,
        /** Markdown 全文 */
        val plan: String,
        val options: List<ApprovalOption>,
        /**
         * 引擎随计划带来的「始终允许」类建议（CC：`permission_suggestions`，多半是「切换到 Accept edits」）。
         * 不放进 [options]：那是通知 / 命令层认的标准选项，计划审批不该在那里出现「始终允许」；
         * 但会话页现行为是把它们当按钮摆在计划卡下面，B6(b) 换通用类型时不能丢，所以另放一处，答复时 [Answer] 照样按 id 认。
         */
        val extraOptions: List<ApprovalOption> = emptyList(),
    ) : PendingInteraction

    val kind: InteractionKind
        get() = when (this) {
            is Approval -> InteractionKind.Approval
            is Question -> InteractionKind.Question
            is PlanReview -> InteractionKind.PlanReview
        }

    /** 同一张卡换一个来源（适配器先造卡再统一盖上来源与排队深度，见 [withQueueDepth]） */
    fun withOrigin(origin: InteractionOrigin): PendingInteraction = when (this) {
        is Approval -> copy(origin = origin)
        is Question -> copy(origin = origin)
        is PlanReview -> copy(origin = origin)
    }
}

enum class InteractionKind { Approval, Question, PlanReview }

/** 提问卡 / 计划审批卡对应的内置工具名（CC 与 Min 内核同名） */
const val ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion"
const val EXIT_PLAN_MODE_TOOL_NAME = "ExitPlanMode"

/**
 * 这张卡在等的是哪个工具。会话流里「这次调用卡在等人批准」的标记靠它回退匹配：引擎没给 [PendingInteraction.toolUseId] 时
 * （CC 的 `can_use_tool` 里它可选），按工具名找最后一条还在跑的同名调用。B6(b) 补，原来页面直接读请求的 `toolName`
 */
val PendingInteraction.awaitedToolName: String
    get() = when (this) {
        is PendingInteraction.Approval -> toolName
        is PendingInteraction.Question -> ASK_USER_QUESTION_TOOL_NAME
        is PendingInteraction.PlanReview -> EXIT_PLAN_MODE_TOOL_NAME
    }

/**
 * 给队列里每张卡盖上「后面还有几张」：队头 = 总数 − 1，队尾 = 0。
 * 顶行文案「Explore · 查找入口（后台）请求 · 后面还有 2 张」读的就是它。三个适配器共用这一份。
 */
fun List<PendingInteraction>.withQueueDepth(): List<PendingInteraction> =
    mapIndexed { index, card -> card.withOrigin(card.origin.copy(queuedAfter = size - 1 - index)) }

/**
 * 交互从哪来。[path] 为空 = 主会话；非空 = 从根到发起者的子 agent 路径。
 * @param queuedAfter 队列里排在它后面的卡数，见 [withQueueDepth]
 */
data class InteractionOrigin(val path: List<AgentLabel> = emptyList(), val queuedAfter: Int = 0) {
    val isMain: Boolean get() = path.isEmpty()

    /** 发起者本人（路径末端）；主会话为 null */
    val agent: AgentLabel? get() = path.lastOrNull()

    companion object {
        val Main = InteractionOrigin()
    }
}

/** 一个子 agent 的名牌：通知标题写「Explore（后台）请求批准：Bash」用的就是它 */
data class AgentLabel(
    val threadId: String,
    val agentType: String,
    val name: String?,
    val description: String,
    val background: Boolean,
    /** 分叉子 agent（CC 的 `fork`：继承父会话完整上下文、一律后台跑）。卡上说「分身」而不是「子 agent」。B6(b) 补 */
    val fork: Boolean = false,
)

/** MCP url 模式的请求内容：[message] 是服务器写给人看的理由，[domain] 是内核从 URL 里解出来的主机名 */
data class UrlRequest(val url: String, val domain: String, val message: String)

data class PathView(val path: String, val scope: PathScope)

data class QuestionView(
    val question: String,
    val header: String?,
    val options: List<QuestionOptionView>,
    val multiSelect: Boolean,
)

data class QuestionOptionView(
    val label: String,
    val description: String?,
    /** 选项预览（代码片段 / 版式草图），等宽排版、保留换行；仅单选时可能出现。B6(b) 补：设计 §3.6 漏了它，渲染不出来就废掉了这个工具最有用的形态 */
    val preview: String? = null,
)

/**
 * 审批卡 / 计划审批卡上的一个按钮。
 *
 * @param acceptsNote 点它时可以附一句说明（CC 的「拒绝并说明」：说明递给模型，本轮继续）
 * @param notificationSafe 可以放进通知按钮：点一下不需要读更多内容就能安心做出的选择（允许 / 拒绝）。
 *   「始终允许」「拒绝并停止」不放：前者要看清规则，后者会打断整轮
 */
data class ApprovalOption(
    val id: String,
    val label: String,
    val kind: Kind,
    val acceptsNote: Boolean,
    val notificationSafe: Boolean,
) {
    enum class Kind {
        Allow,

        /** 始终允许（CC 的 PermissionSuggestion、Codex 的本会话允许）；Min 等 K14 的设置层写入落地后再开 */
        AllowAlways,

        /** 拒绝，本轮继续（通知上的「拒绝」就是它，带说明） */
        Deny,

        /** 拒绝并停止：不带说明，打断本轮（界面里的「拒绝」现行为） */
        DenyAndStop,
    }

    companion object {
        /** 三个引擎共用的稳定 id，通知按钮的 PendingIntent 里存的就是它们 */
        const val ALLOW = "allow"
        const val DENY = "deny"
        const val DENY_AND_STOP = "deny-stop"

        /** 「始终允许」的 id 前缀，后面接引擎自己的建议序号 / 规则标识：`always:0` */
        const val ALWAYS_PREFIX = "always:"
    }
}

@Serializable
sealed interface InteractionAnswer {
    /** 选一个按钮。[note] 只在 [ApprovalOption.acceptsNote] 为真时有意义 */
    @Serializable @SerialName("choose")
    data class Choose(val optionId: String, val note: String? = null) : InteractionAnswer

    /** 提问卡的答案，每题一项，顺序与 [PendingInteraction.Question.questions] 一致 */
    @Serializable @SerialName("answer")
    data class Answer(val answers: List<QuestionAnswerInput>) : InteractionAnswer

    /** 关掉提问卡，不回答 */
    @Serializable @SerialName("decline")
    data object Decline : InteractionAnswer
}

/** 一题的答案：选中的选项 label（多选可以有几个）加「其它」里手填的话 */
@Serializable
data class QuestionAnswerInput(val question: String, val selected: List<String>, val other: String? = null) {
    /**
     * CC 的约定（`ClaudeCodeManager` 里 AskUserQuestion 的回填）：Map<问题原文, 选中的 label 用「, 」连接>，
     * 手填的话排在最后。Min 内核的 `answerQuestion` 要自己的形状，由 Min 适配器转，别共用这个。
     */
    val joined: String get() = (selected + listOfNotNull(other?.takeIf { it.isNotBlank() })).joinToString(", ")
}

/**
 * Min 的 interaction id 是 `"<sessionId>/<approvalId>"`：根会话的待处理交互里混着子会话上浮的，
 * 答复时按前缀路由回子运行时。拆合都放这里，不让适配器各自拼字符串。
 */
object InteractionIds {
    private const val SEP = '/'

    fun scoped(sessionId: String, localId: String): String {
        require(SEP !in sessionId) { "sessionId must not contain '$SEP': $sessionId" }
        return "$sessionId$SEP$localId"
    }

    /** 拆回 (sessionId, localId)；不是 scoped 形状（没有分隔符）返回 null。localId 里可以再含分隔符 */
    fun split(id: String): Pair<String, String>? {
        val at = id.indexOf(SEP)
        if (at <= 0 || at == id.length - 1) return null
        return id.substring(0, at) to id.substring(at + 1)
    }
}
