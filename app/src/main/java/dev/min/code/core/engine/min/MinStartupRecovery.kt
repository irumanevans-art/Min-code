package dev.min.code.core.engine.min

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.SessionStore
import kotlinx.coroutines.CancellationException

/**
 * App 进程启动后的扫描（设计 6.3「启动扫描」）：找出上次进程死在回合中途的根会话，让内核把它们合法地收尾。
 *
 * 做法：读索引（对不上日志的会重建，重建是只读折叠）里 `turnOpen` 且**不在线**的会话，经 [recover] 走一遍 `hub.open`——
 * 它提交 `planRecovery` 的收尾事件（悬空调用补结果、`TurnEnded(ProcessDeath)`）。这样抽屉里这些会话带「中断」标记，
 * 打开后就是 ▶。**不自动续跑，也不发通知**：进程刚启动，通常就是用户自己打开了 App，界面上的提示已经够了（02 §5）。
 *
 * 不在启动时全量打开所有会话，是因为那会让启动时间随历史会话数增长；只处理索引说「没收尾」的那几个。
 *
 * @param recover 恢复一个会话：打开（提交收尾事件）、刷新索引、再卸下，不留常驻运行时
 */
class MinStartupRecovery(
    private val store: SessionStore,
    private val index: MinSessionIndex,
    private val isOnline: (String) -> Boolean,
    private val recover: suspend (SessionId) -> Unit,
) {
    /** 返回恢复了哪些会话。单个会话恢复失败（日志坏了）不影响其余，只是它留在原样 */
    suspend fun run(): List<String> {
        val open = index.list(store).filter { it.turnOpen && !isOnline(it.id) }.map { it.id }
        val recovered = mutableListOf<String>()
        for (id in open) {
            try {
                recover(SessionId(id))
                recovered += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 坏日志打不开：留着，用户打开它时会看到明确的错误；不能让它挡住别的会话恢复
            }
        }
        return recovered
    }
}
