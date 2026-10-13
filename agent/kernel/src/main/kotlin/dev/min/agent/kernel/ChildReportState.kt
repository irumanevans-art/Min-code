package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * 子 agent 回合结局「谁来交报告、要不要唤醒父会话」的协调状态，由 [SessionHub] 持有。
 *
 * 盯结局的协程（[SessionHub] 的 watch）、前台等待的工具（awaitForeground）、[SessionHub.stopTree] / [SessionHub.shutdown] /
 * [SessionHub.delete] 会在不同线程上同时碰这些记录，没有一把锁罩着它们——正确性靠下面每个方法的**顺序约定**，
 * 而不是互斥。改动前先读完每个方法的说明；调用方的先后次序（见各方法的「必须」）是测试钉住的：
 * `SubagentTest` 里的 stopTree 抢跑用例与 repeat(25) 的竞态用例。
 *
 * 会话卸下时由 [forget] 清掉**本类按会话登记的全部条目**，是这些记录唯一的收口：新增一种按会话记的状态，
 * 就必须在这里登记、并让 [forget] 清它，否则就是随运行时间只增不减的泄漏。
 * [markForeground] 与 [startStopping] 登记的两种记录不归 [forget] 管：它们各自有成对的释放点
 * （awaitForeground 的 finally、stopTree 的 finally），在释放之前提前清掉会改变竞态结果
 */
internal class ChildReportState {
    /** 正被前台委派等着的子会话：它们这一回合的报告交给等待的工具，不进收件箱 */
    private val awaitedInForeground: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** 已经作为前台工具结果交出去的子回合（见 [consume]） */
    private val consumedInForeground: MutableSet<Pair<SessionId, TurnId>> = ConcurrentHashMap.newKeySet()

    /** 被人整棵停下的子回合（见 [quiet]） */
    private val quietReports: MutableSet<Pair<SessionId, TurnId>> = ConcurrentHashMap.newKeySet()

    /** [SessionHub.stopTree] 正在停的会话（见 [startStopping]） */
    private val stopping: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** 已经在盯着回合结局的子会话（每个只盯一次） */
    private val watched: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** 盯着子会话回合结局的协程（每个子会话一个） */
    private val watchJobs = ConcurrentHashMap<SessionId, Job>()

    // ---- 前台委派 ----

    /** 子会话被前台委派等着：盯结局的协程见到它就不交报告，由等待方作为工具结果交回 */
    fun markForeground(id: SessionId) {
        awaitedInForeground += id
    }

    /** 等待方不再等了（拿到结局，或被取消）。取消路径与 finally 各调一次，幂等 */
    fun releaseForeground(id: SessionId) {
        awaitedInForeground -= id
    }

    /**
     * 这个子回合的报告已经作为前台工具结果交出去了。等待方一拿到结局就会 [releaseForeground]，而盯结局的协程
     * 可能稍后才处理到同一个结局——没有这份记录，它会把报告再往收件箱里交一次。
     * 只防本进程内的这个空档；跨重启由工具结果里的 ReportDelivered 在折叠时记账。
     * 必须在 [releaseForeground] 之前调用（等待方 return 之前，finally 才释放）
     */
    fun consume(id: SessionId, turnId: TurnId) {
        consumedInForeground += id to turnId
    }

    /** 盯结局的协程问：这个结局是不是前台等待方负责交的（正在等，或已经交过），是就别再交 */
    fun isHandledInForeground(id: SessionId, turnId: TurnId): Boolean =
        id in awaitedInForeground || (id to turnId) in consumedInForeground

    // ---- 静音（人说了停，报告照交但不唤醒父会话）----

    /**
     * 这个子回合的报告照常交付，但不唤醒父会话开新回合：用户刚说了停，不该因为子 agent 交来报告又自己动起来。
     * 按 (子会话, 回合) 记，盯结局的协程什么时候处理到都认得出。
     * 在打断之前调用：结局一出现，盯结局的协程就可能去交付
     */
    fun quiet(id: SessionId, turnId: TurnId) {
        quietReports += id to turnId
    }

    /**
     * 取走（并消耗）静音记录，返回有没有。交付时**第一件事**就取：放在 && 链里会被前面的条件短路掉，
     * 留下永远不被清的条目
     */
    fun takeQuiet(id: SessionId, turnId: TurnId): Boolean = quietReports.remove(id to turnId)

    // ---- stopTree 的收尾窗口 ----

    /**
     * [SessionHub.stopTree] 开始停这些会话。盯结局的协程常常先于 stopTree 一步拿到被打断的结局并交付；
     * 那时回合号还没记进静音记录，所以交付时看到子会话在这里（[isStopping]）就一律不唤醒父会话
     */
    fun startStopping(ids: Iterable<SessionId>) {
        stopping += ids
    }

    /**
     * stopTree 收尾：必须先把结局的回合号 [quiet] 进去、**再**调这里，中间不留空档——
     * 反过来的话，交付方在两步之间既看不到 stopping 也看不到静音记录，会唤醒父会话
     */
    fun finishStopping(ids: Iterable<SessionId>) {
        stopping -= ids
    }

    fun isStopping(id: SessionId): Boolean = id in stopping

    // ---- 盯结局的协程 ----

    /** 抢占「盯这个子会话」的资格：true = 这是第一次，调用方接着 [trackWatch]；false = 已经有人在盯，什么都不做 */
    fun claimWatch(id: SessionId): Boolean = watched.add(id)

    /** 登记盯结局的协程，[forget] 时一并取消 */
    fun trackWatch(id: SessionId, job: Job) {
        watchJobs[id] = job
    }

    // ---- 卸下 ----

    /**
     * 会话卸下：取消它的盯结局协程，清掉它所有的静音与「已作前台结果交出」记录（卸下后没人再读，
     * 留着就是泄漏）。之后同一会话重开能重新盯。不动 [markForeground] / [startStopping] 的记录，见类说明
     */
    fun forget(id: SessionId) {
        watchJobs.remove(id)?.cancel()
        watched -= id
        quietReports.removeIf { it.first == id }
        consumedInForeground.removeIf { it.first == id }
    }

    /** 测试用：登记着的静音结局条数 */
    internal fun quietCount(): Int = quietReports.size

    /** 测试用：登记着的「已作前台结果交出」条数 */
    internal fun consumedCount(): Int = consumedInForeground.size

    /** 测试用：已在盯的子会话数 / 登记着的盯结局协程数 */
    internal fun watchCount(): Int = watched.size
    internal fun watchJobCount(): Int = watchJobs.size
}
