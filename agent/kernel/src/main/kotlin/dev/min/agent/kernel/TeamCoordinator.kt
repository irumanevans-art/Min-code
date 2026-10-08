package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TEAM_ALL
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamMessage
import dev.min.agent.spi.TeamOp
import dev.min.agent.spi.TeamTaskClaimed
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskDone
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 团队同时运行的成员数：默认值、提示线、以及设置项的校验（设计 M5 总览 §四 决定 2） */
object TeamLimits {
    const val DEFAULT_MAX_CONCURRENT = 3

    /** 超过它的设置要用户明确确认：手机的耗电、发热、额度都吃不消，误设代价大 */
    const val CONFIRM_ABOVE = 8

    /** 设置项 `teamMaxConcurrent` 的一次输入的结论 */
    sealed interface Setting {
        data class Accepted(val value: Int) : Setting

        /** 超过 [CONFIRM_ABOVE]：界面问过用户、用户确认后带 `confirmedAboveCap = true` 再来一次 */
        data class NeedsConfirmation(val value: Int) : Setting
        data class Invalid(val reason: String) : Setting
    }

    fun resolve(requested: Int?, confirmedAboveCap: Boolean = false): Setting = when {
        requested == null -> Setting.Accepted(DEFAULT_MAX_CONCURRENT)
        requested < 1 -> Setting.Invalid("团队同时运行的成员数至少是 1。")
        requested > CONFIRM_ABOVE && !confirmedAboveCap -> Setting.NeedsConfirmation(requested)
        else -> Setting.Accepted(requested)
    }
}

/** 界面读成员状态用的一行（日志 + 成员会话的折叠状态推出来的，不是另存的状态） */
data class TeamMemberStatus(
    val name: String,
    val session: SessionId?,
    val phase: Phase,
    /** 认领着、还没完成的团队任务编号 */
    val openTasks: List<String>,
) {
    enum class Phase {
        /** 只有规格，子会话还没建 */
        NotJoined,

        /** 开场任务在收件箱里、还没开过回合：等名额，或进程重启后等人决定 */
        Queued,
        Running,

        /** 回合结束了（报告已交给负责人） */
        Idle,
    }
}

/**
 * 团队的运行时（SessionHub 持有一个）：建团队、任务清单操作、邮箱、并发名额。
 *
 * 团队**状态**不在这里——在负责人日志的折叠里（[Teams.kt]）。这里只有两样内存东西：
 *   1. 一把锁，把对负责人日志的团队写入串行化（任务编号、校验与落盘在同一把锁里，两个成员同时建任务不会拿到同一个编号）；
 *   2. 并发名额：谁在占名额、谁在排队。排队顺序是调度顺序，不是状态——排队的成员在日志里是「收件箱有任务、没开过回合」，
 *      进程重启后不自动开跑（崩溃后不自动续跑，02 §5）。
 */
internal class TeamCoordinator(
    private val hub: SessionHub,
    private val deps: KernelDeps,
    private val scope: CoroutineScope,
    private val maxConcurrent: Int,
) {
    private val teamLock = Mutex()

    private val slotLock = Mutex()
    private val active = LinkedHashSet<SessionId>()
    private val queue = ArrayDeque<SessionId>()

    /** 刚因为「收尾时又来了新输入」被重新排队的成员：它这次开跑后不再因同样的原因重排，免得起不来时空转 */
    private val leftoverRequeued = HashSet<SessionId>()

    // ---- 入口 ----

    suspend fun handle(caller: SessionRuntime, turnId: TurnId, callId: CallId, op: TeamOp): ToolResult {
        val rootId = caller.state.value.origin?.root ?: caller.sessionId
        if (op is TeamOp.Create) return create(caller, turnId, callId, op.members)
        val root = hub.open(rootId) ?: return failure("找不到团队所属的根会话。")
        val team = root.state.value.team ?: return failure("还没有团队。先用 TeamCreate 建一个。")
        val actor = if (caller.sessionId == rootId) TEAM_LEAD else team.nameOf(caller.sessionId)
            ?: return failure("你不是这个团队的成员，不能操作团队。")
        return when (op) {
            is TeamOp.Create -> failure("建团队在 handle 开头已经处理，不会走到这里。")
            is TeamOp.Disband -> disband(root, team, actor, op)
            is TeamOp.TaskCreate -> commitTeamEvent(root, actor, "已创建团队任务") { t ->
                TeamTaskCreated(t.teamId, t.nextTaskId(), op.title.trim(), op.description.trim(), actor)
            }
            is TeamOp.TaskClaim -> commitTeamEvent(root, actor, "已认领") { t ->
                TeamTaskClaimed(t.teamId, op.taskId.trim().removePrefix("#"), op.member?.trim()?.takeIf { it.isNotEmpty() } ?: actor, actor)
            }
            is TeamOp.TaskDone -> commitTeamEvent(root, actor, "已完成") { t ->
                TeamTaskDone(t.teamId, op.taskId.trim().removePrefix("#"), actor, op.summary?.trim()?.takeIf { it.isNotEmpty() })
            }
            TeamOp.Status -> success(statusText(root.state.value.team ?: team))
        }
    }

    // ---- 建团队 ----

    private suspend fun create(leader: SessionRuntime, turnId: TurnId, callId: CallId, specs: List<TeamMemberSpec>): ToolResult {
        val teamId = deps.ids.next("team")
        val created = TeamCreated(teamId, leader.sessionId, specs)
        val rejected = teamLock.withLock {
            val problem = leader.state.value.teamProblem(created)
            if (problem != null) return@withLock problem
            if (leader.commit(listOf(created), Durability.Sync).isEmpty()) "会话已关闭，团队没建成。" else null
        }
        if (rejected != null) return failure(rejected)

        val names = specs.map { it.name }
        for (spec in specs) {
            val request = DelegateRequest(
                description = "团队成员 ${spec.name}",
                prompt = memberBrief(teamId, spec, names),
                agentType = spec.agentType,
                background = true,
                name = spec.name,
            )
            val started = hub.spawn(leader, turnId, callId, request, teamMember = true)
            if (started !is DelegateResult.Started) {
                val why = (started as? DelegateResult.Rejected)?.messageForModel ?: "子会话没建成"
                // 已建好的成员不会开跑（开跑在所有成员都入队之后）；团队以出错收场，免得留一个缺人的半成品
                disbandTeam(leader, TeamDisbanded(teamId, TeamDisbandReason.Error, "成员「${spec.name}」没建成：$why"))
                return failure("团队没建成：成员「${spec.name}」创建失败（$why）。已建好的成员不会开跑。")
            }
            teamLock.withLock { leader.commit(listOf(TeamMemberJoined(teamId, spec.name, started.child)), Durability.Sync) }
        }
        // 所有成员都入队了才开跑：开跑顺序 = 规格顺序，超出名额的排队
        val joined = leader.state.value.team!!.members.mapNotNull { it.session }
        joined.forEach { requestStart(it) }
        return success(
            "团队 $teamId 已建立。\n" + statusText(leader.state.value.team!!) +
                "\n成员完成后报告会交给你（负责人）；在那之前不要假设它们的结果。可以用 TeamTask 管理任务清单，用 SendMessage 给成员发消息。",
        )
    }

    /** 成员的开场输入：身份、队友、怎么协作，然后才是负责人给的任务。成员看不到负责人的对话，所以要给齐 */
    private fun memberBrief(teamId: String, spec: TeamMemberSpec, all: List<String>): String {
        val mates = (all - spec.name).joinToString("、").ifEmpty { "（没有其他成员）" }
        return "你是 agent 团队 $teamId 的成员「${spec.name}」，负责人叫 $TEAM_LEAD。队友：$mates。\n" +
            "协作方式：用 TeamTask 工具查看、认领、完成团队任务（只能改自己认领的任务）；用 SendMessage 给队友（to=名字）、负责人（to=$TEAM_LEAD）发消息，to=$TEAM_ALL 广播给全体。" +
            "你看不到负责人的对话，需要的信息向负责人要。做完后直接给出报告，报告会交给负责人，由他决定要不要转给用户。\n\n" +
            "你的任务：\n${spec.prompt}"
    }

    // ---- 任务清单 ----

    /**
     * 在负责人日志里落一条团队事件：锁内取状态、造事件、校验、落盘。违规的不落事件，只记一条给人看的说明。
     * [build] 拿到的是锁内最新的团队状态（任务编号因此不会重复）
     */
    private suspend fun commitTeamEvent(root: SessionRuntime, actor: String, doneText: String, build: (TeamState) -> SessionEvent): ToolResult =
        teamLock.withLock {
            val state = root.state.value
            val team = state.team ?: return@withLock failure("还没有团队。")
            val event = build(team)
            val problem = state.teamProblem(event)
            if (problem != null) {
                root.commit(listOf(Note(NoteLevel.Warning, "团队操作被拒（发起人 $actor）：$problem")), Durability.Batched)
                return@withLock failure(problem)
            }
            if (root.commit(listOf(event), Durability.Sync).isEmpty()) return@withLock failure("会话已关闭，没有记下。")
            val after = root.state.value.team!!
            success(
                when (event) {
                    is TeamTaskCreated -> "$doneText #${event.taskId}：${event.title}"
                    is TeamTaskClaimed -> "$doneText 任务 #${event.taskId}（认领人：${event.member}）"
                    is TeamTaskDone -> "$doneText 任务 #${event.taskId}"
                    else -> doneText
                } + "\n" + tasksText(after),
            )
        }

    // ---- 解散 ----

    private suspend fun disband(root: SessionRuntime, team: TeamState, actor: String, op: TeamOp.Disband): ToolResult {
        if (actor != TEAM_LEAD) {
            val reason = "只有负责人能解散团队。"
            root.commit(listOf(Note(NoteLevel.Warning, "团队操作被拒（发起人 $actor）：$reason")), Durability.Batched)
            return failure(reason)
        }
        val problem = disbandTeam(root, TeamDisbanded(team.teamId, op.reason, op.note)) ?: return success("团队 ${team.teamId} 已解散，成员已停下；它们已有的报告照常交给你。")
        return failure(problem)
    }

    /** 写解散事件、停成员、清名额。返回 null = 成功，否则是原因 */
    private suspend fun disbandTeam(root: SessionRuntime, event: TeamDisbanded): String? {
        val members = teamLock.withLock {
            val problem = root.state.value.teamProblem(event)
            if (problem != null) return problem
            root.commit(listOf(event), Durability.Sync)
            root.state.value.team!!.members.mapNotNull { it.session }
        }
        slotLock.withLock {
            queue.removeAll(members.toSet())
            leftoverRequeued.removeAll(members.toSet())
        }
        members.forEach { hub.stopTree(it) }
        return null
    }

    // ---- 邮箱 ----

    /**
     * 团队邮件：发件人在团队里、收件人也在时走这里，返回给模型的结论；不适用（发件人不在团队、收件人不是队友）返回 null，
     * 调用方照旧走 SendMessage 的子 agent 路径。邮件进收件人收件箱用的就是 [SessionRuntime.receive]——
     * 与 SendMessage 同一条路，team_message 只是同一次提交里多带的一条来历记录。
     */
    suspend fun sendMail(from: SessionRuntime, to: String, text: String): ToolResult? {
        val rootId = from.state.value.origin?.root ?: from.sessionId
        val root = hub.open(rootId) ?: return null
        val team = root.state.value.team?.takeIf { it.active } ?: return null
        val sender = team.nameOf(from.sessionId) ?: return null
        val target = to.trim()
        val recipients: List<String> = when {
            target == TEAM_ALL || target == "*" -> (listOf(TEAM_LEAD) + team.members.filter { it.session != null }.map { it.name }) - sender
            else -> listOf(resolveName(team, target) ?: return null)
        }
        if (target != TEAM_ALL && target != "*" && recipients.single() == sender) return failure("不能给自己发消息。")
        val broadcast = target == TEAM_ALL || target == "*"
        if (recipients.isEmpty()) return failure("团队里没有别的人可以收这条广播。")

        val notes = mutableListOf<String>()
        for (name in recipients) {
            val toLeader = name == TEAM_LEAD
            val sessionId = if (toLeader) team.leader else team.member(name)?.session ?: continue
            val runtime = hub.open(sessionId) ?: run { notes += "$name 的会话打不开了。"; continue }
            val messageId = deps.ids.next("tm")
            val heading = "[团队邮件 · 来自 $sender" + (if (broadcast) " · 广播" else "") + "]\n"
            runtime.receive(
                ClientMsgId("team:${team.teamId}:$messageId"),
                listOf(TextPart(heading + text)),
                Delivery.Steer,
                InputOrigin.Agent(from.sessionId, AgentInputKind.Message),
                // 负责人不占成员名额，直接唤醒；成员的开跑要过名额
                startTurn = if (toLeader) TurnTrigger.Notification else null,
                extra = listOf(TeamMessage(team.teamId, messageId, sender, name, text, broadcast)),
            )
            if (!toLeader) requestStart(sessionId)
            notes += "$name：" + deliveryNote(runtime, sessionId)
        }
        return success("已发出。" + notes.joinToString("；", prefix = "（", postfix = "）"))
    }

    private suspend fun deliveryNote(runtime: SessionRuntime, id: SessionId): String = when {
        runtime.state.value.turn != null -> "它在跑，会在当前这批工具结束后看到"
        slotLock.withLock { id in queue } -> "它在排队等名额，轮到时会先看到"
        else -> "已送达"
    }

    /** 团队里叫 [target] 的人：负责人的几种叫法（lead / main / 会话 id），成员的名字或会话 id */
    private fun resolveName(team: TeamState, target: String): String? = when {
        target == TEAM_LEAD || target == "main" || target == team.leader.value -> TEAM_LEAD
        team.member(target)?.session != null -> target
        else -> team.members.firstOrNull { it.session?.value == target }?.name
    }

    // ---- 并发名额 ----

    /**
     * 让成员开跑：有名额就开，没有就排队（先来先得）。成员已在跑、或已在排队时什么都不做——
     * 新消息作为插话进它的收件箱，已经够了。所有从团队路径起的回合都过这里，所以同时运行的成员数不会超过上限
     */
    suspend fun requestStart(member: SessionId) {
        val runtime = hub.open(member) ?: return
        slotLock.withLock {
            if (member in active || member in queue) return
            if (!runtime.isTurnIdle()) return // 已经在跑，不是通过团队起的（比如用户直接在成员会话里打字）：不占名额
            if (!hasRoom()) {
                queue.addLast(member)
                return
            }
            startLocked(runtime)
        }
    }

    private fun hasRoom(): Boolean = active.size < maxConcurrent

    private fun startLocked(runtime: SessionRuntime) {
        val id = runtime.sessionId
        active += id
        val allowRetry = leftoverRequeued.remove(id).not()
        runtime.runInbox()
        scope.launch {
            // awaitIdle 等的是整个回合 Job：回合正常结束后若收件箱还有排队的输入，同一个 Job 里接着开下一回合，
            // 所以这里等到的才是「真的没事干了」，不会在两个回合之间错放名额
            runtime.awaitIdle()
            release(id, allowRetry)
        }
    }

    private suspend fun release(id: SessionId, allowRetry: Boolean) {
        slotLock.withLock {
            active -= id
            val runtime = hub.get(id)
            // 收尾的瞬间又来了新输入（最后一次检查收件箱之后、Job 结束之前）：它还躺在收件箱里，重新排队，别丢
            if (allowRetry && runtime != null && runtime.isTurnIdle() && runtime.state.value.inbox.isNotEmpty() && id !in queue) {
                leftoverRequeued += id
                queue.addLast(id)
            }
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        while (hasRoom()) {
            val next = queue.removeFirstOrNull() ?: return
            val runtime = hub.get(next) ?: continue
            if (!runtime.isTurnIdle() || runtime.state.value.inbox.isEmpty()) continue
            startLocked(runtime)
        }
    }

    /** 会话卸下时：不再占名额也不再排队 */
    fun forget(id: SessionId) {
        scope.launch {
            slotLock.withLock {
                queue.remove(id)
                active -= id
                leftoverRequeued -= id
                pumpLocked()
            }
        }
    }

    /** 当前占名额的成员数（测试与界面用） */
    suspend fun runningCount(): Int = slotLock.withLock { active.size }

    // ---- 只读视图 ----

    fun members(root: SessionRuntime): List<TeamMemberStatus> {
        val team = root.state.value.team ?: return emptyList()
        return team.members.map { m ->
            val child = m.session?.let { hub.get(it) }?.state?.value
            val phase = when {
                m.session == null -> TeamMemberStatus.Phase.NotJoined
                child == null -> TeamMemberStatus.Phase.Idle // 不在线：看不到它的状态，按空闲算（界面可以去打开它）
                child.turn != null -> TeamMemberStatus.Phase.Running
                child.lastTurn == null && child.inbox.isNotEmpty() -> TeamMemberStatus.Phase.Queued
                else -> TeamMemberStatus.Phase.Idle
            }
            TeamMemberStatus(m.name, m.session, phase, team.tasks.filter { it.owner == m.name && it.status == TeamTaskStatus.Claimed }.map { it.id })
        }
    }

    private fun statusText(team: TeamState): String {
        val root = hub.get(team.leader)
        val phases = root?.let { members(it) }.orEmpty().associateBy { it.name }
        val roster = team.members.joinToString("\n") { m ->
            val phase = when (phases[m.name]?.phase) {
                TeamMemberStatus.Phase.Running -> "运行中"
                TeamMemberStatus.Phase.Queued -> "排队等名额"
                TeamMemberStatus.Phase.Idle -> "空闲"
                TeamMemberStatus.Phase.NotJoined, null -> "未入队"
            }
            "- ${m.name}（${m.agentType ?: "general-purpose"}）：$phase"
        }
        val header = if (team.active) "团队 ${team.teamId}（负责人 $TEAM_LEAD）" else "团队 ${team.teamId}（已解散：${team.disbanded!!.reason.name}）"
        return "$header\n成员：\n$roster\n" + tasksText(team)
    }

    private fun tasksText(team: TeamState): String {
        if (team.tasks.isEmpty()) return "任务清单：（空）"
        return "任务清单：\n" + team.tasks.joinToString("\n") { t ->
            val state = when (t.status) {
                TeamTaskStatus.Pending -> "待认领"
                TeamTaskStatus.Claimed -> "进行中 · ${t.owner}"
                TeamTaskStatus.Done -> "已完成 · ${t.doneBy}" + (t.summary?.let { "：$it" } ?: "")
            }
            "- #${t.id} ${t.title}（$state）"
        }
    }

    private fun success(text: String) = ToolResult(listOf(TextPart(text)))
    private fun failure(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
}
