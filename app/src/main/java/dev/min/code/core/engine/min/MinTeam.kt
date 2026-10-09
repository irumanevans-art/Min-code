package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.TeamMemberStatus
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.TEAM_MESSAGE_LIMIT
import dev.min.code.core.engine.TeamControl
import dev.min.code.core.engine.TeamMemberPhase
import dev.min.code.core.engine.TeamMemberView
import dev.min.code.core.engine.TeamMessageView
import dev.min.code.core.engine.TeamTaskStatus
import dev.min.code.core.engine.TeamTaskView
import dev.min.code.core.engine.TeamView

/**
 * 根会话的团队 -> 界面的 [TeamView]（纯函数，映射器的单测直接喂数据）。没有团队返回 null。
 *
 * - 成员状态来自内核的 [TeamMemberStatus]（Queued 与 Stalled 要看内存里的排队队伍，不能只从状态推），
 *   模型取自成员自己的会话（可以和负责人不同），线程 id 取自委派摘要（点成员行跳过去）。
 * - 共享清单直接是团队的任务记录；“被阻塞”是依赖里还有没完成的。
 * - 消息流是各成员（和负责人）收件箱里 `team_message` 折叠出的 [SessionState.teamMail] 的并集，收件人是收件那一方的名字。
 *   广播会在每个收件人那里各有一份，合并成一条“发给全体”；按消息编号（`tm_<序号>`）排先后，只留最近 [TEAM_MESSAGE_LIMIT] 条。
 */
fun minTeamView(
    root: SessionId,
    nodes: Map<SessionId, SessionState>,
    members: List<TeamMemberStatus>,
    threads: List<AgentThread>,
): TeamView? {
    val team = nodes[root]?.team ?: return null
    val phases = members.associateBy { it.name }
    val threadOfSession = threads.mapNotNull { t -> t.childRef?.id?.let { it to t.threadId } }.toMap()
    val taskById = team.tasks.associateBy { it.id }
    val memberViews = team.members.map { m ->
        val session = m.session
        val status = phases[m.name]
        val mine = team.tasks.filter { it.owner == m.name && it.status != TaskStatus.Completed }
        TeamMemberView(
            name = m.name,
            agentType = m.agentType,
            phase = when (status?.phase) {
                TeamMemberStatus.Phase.Queued -> TeamMemberPhase.Queued
                TeamMemberStatus.Phase.Stalled -> TeamMemberPhase.Stalled
                TeamMemberStatus.Phase.Running -> TeamMemberPhase.Running
                TeamMemberStatus.Phase.Idle -> TeamMemberPhase.Idle
                TeamMemberStatus.Phase.NotJoined, null -> if (session == null) TeamMemberPhase.NotJoined else TeamMemberPhase.Idle
            },
            model = session?.let { nodes[it]?.settings?.model?.modelId },
            currentTask = (mine.firstOrNull { it.status == TaskStatus.InProgress } ?: mine.firstOrNull())?.let { it.activeForm ?: it.subject },
            openTaskIds = mine.map { it.id },
            threadId = session?.let { threadOfSession[it.value] },
        )
    }
    return TeamView(
        teamId = team.teamId,
        active = team.active,
        disbandNote = team.disbanded?.note?.takeIf { it.isNotBlank() },
        members = memberViews,
        tasks = team.tasks.map { it.toView(taskById) },
        messages = teamMessages(team.leader, team.members.mapNotNull { m -> m.session?.let { it to m.name } }, nodes),
    )
}

private fun TaskRecord.toView(all: Map<String, TaskRecord>) = TeamTaskView(
    id = id,
    subject = subject,
    status = when (status) {
        TaskStatus.Pending -> TeamTaskStatus.Pending
        TaskStatus.InProgress -> TeamTaskStatus.InProgress
        TaskStatus.Completed -> TeamTaskStatus.Completed
    },
    owner = owner,
    blockedBy = blockedBy,
    // 依赖的任务被删了（找不到）不算还在等：编号不复用，删掉就是不再需要
    waitingOn = blockedBy.filter { all[it]?.status.let { s -> s != null && s != TaskStatus.Completed } },
)

private fun teamMessages(leader: SessionId, members: List<Pair<SessionId, String>>, nodes: Map<SessionId, SessionState>): List<TeamMessageView> {
    val recipients = listOf(leader to TEAM_LEAD) + members
    class Mail(val seq: Long, val from: String, val to: String, val text: String, val broadcast: Boolean)
    val all = recipients.flatMap { (session, name) ->
        nodes[session]?.teamMail.orEmpty().map { Mail(mailSeq(it.messageId), it.from, name, it.text, it.broadcast) }
    }.sortedBy { it.seq }
    // 广播：同一个发件人、同一段话、编号连着发出的那几份合成一条。一对一的消息不合并（同一句话发给两个人就是两条）
    val merged = ArrayList<TeamMessageView>()
    var lastBroadcast: Mail? = null
    for (mail in all) {
        if (mail.broadcast) {
            val prev = lastBroadcast
            if (prev != null && prev.from == mail.from && prev.text == mail.text && mail.seq - prev.seq <= BROADCAST_ID_SPREAD) {
                lastBroadcast = mail
                continue
            }
            lastBroadcast = mail
            merged += TeamMessageView(mail.from, null, mail.text)
        } else {
            merged += TeamMessageView(mail.from, mail.to, mail.text)
        }
    }
    return merged.takeLast(TEAM_MESSAGE_LIMIT)
}

/** `tm_12` -> 12；不是这个形状的排在最前（旧日志里的别种编号不该把新消息顶乱） */
internal fun mailSeq(messageId: String): Long = messageId.substringAfterLast('_', "").toLongOrNull() ?: 0L

/**
 * 一次广播给 N 个收件人，编号是连着分配的（循环里每人取一个，中间不会插进别的编号来源），
 * 所以同一份广播的编号差不会超过团队人数；放宽到 64，宁可把极端情况下紧挨着的两次相同广播并成一条，也不让同一次广播显示成多条
 */
private const val BROADCAST_ID_SPREAD = 64L

/** 团队页的两个动作接到 Hub 上 */
class MinTeamControl(private val hub: SessionHub, private val root: SessionId) : TeamControl {
    override suspend fun releaseStalled(member: String?): Int = hub.resumeTeamMembers(root, member)
    override suspend fun disband(): String? = hub.disbandTeam(root)
}
