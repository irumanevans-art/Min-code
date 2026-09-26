package dev.min.code.core.claudecode

/**
 * 追加消息的调度台：一条用户消息在「被模型看见」之前会经过两格。
 *
 * - **held**：还攥在我们手里，一个字节都没出去。只有两种情况会停在这里 ——
 *   会话还没 Running（启动中打的字），或者刚被 Esc 从 CLI 的队列里要回来。
 * - **handedOff**：帧已经写进 stdin，等 CLI 在**下一次发请求之前**把它插进当前这一轮。
 *
 * 为什么是交棒而不是攥到本轮结束：2026-09-16 用 `tools/probe_queue.py` 对 CLI 2.1.272 实测，
 * 生成中写进 stdin 的 user 帧会在下一个 agent 循环边界被插进同一轮（第一个 Write 的
 * tool_result 刚回来，模型下一句就在回应那条追加消息），整轮只有一个 `result`。
 * 这就是官方终端的手感；1.1.7 之前攥到 `result` 才发，慢的正是这一段。
 *
 * 副本留到 CLI 确认吃下（`system/status status=requesting`）为止，是为了 Esc：
 * interrupt 帧带 `cancel_queued`，CLI 侧队列会被清空，那批只能由我们原样重发。
 *
 * UI 线程（send / interrupt）和 readLoop 线程（result / status）都会碰它，所以全程上锁。
 */
internal class ClaudeCodeSendQueue<T> {

    private val lock = Any()
    private val held = ArrayDeque<T>()
    private val handedOff = ArrayDeque<T>()

    /** 还有没有没被模型看见的消息。`busy` 能不能落地看的就是它 */
    fun hasWork(): Boolean = synchronized(lock) { held.isNotEmpty() || handedOff.isNotEmpty() }

    /** 攥住一条：会话还没起来，或者打断之后要回来的 */
    fun hold(item: T) {
        synchronized(lock) { held.addLast(item) }
    }

    /**
     * 交棒：登记 + 写出去。
     *
     * [write] **在锁内**调用是有意的 —— 登记顺序必须和 stdin 上的字节顺序一致，
     * 否则两条紧挨着发出的追加消息在 CLI 那边的先后可能和对话流里显示的相反。
     */
    fun handOff(item: T, write: (T) -> Unit) {
        synchronized(lock) {
            handedOff.addLast(item)
            write(item)
        }
    }

    /** 把攥着的全部取出来（调用方负责发）。取出即清空 */
    fun drainHeld(): List<T> = synchronized(lock) {
        if (held.isEmpty()) return emptyList()
        val all = held.toList()
        held.clear()
        all
    }

    /**
     * CLI 要发请求了 —— 它的输入队列正是在那一刻排空的，所以此前交棒的都已经进了上下文。
     * 返回这一批，调用方据此摘掉界面上的「排队中」。
     */
    fun confirm(): List<T> = synchronized(lock) {
        if (handedOff.isEmpty()) return emptyList()
        val all = handedOff.toList()
        handedOff.clear()
        all
    }

    /**
     * 把已交棒、还没被确认的要回来，排到 held 的**最前面**。
     *
     * 排在最前是因为它们本来就该比任何后来攥住的消息先跑；倒着 addFirst 才保得住原次序。
     *
     * 两个调用点：Esc（CLI 侧队列被 `cancel_queued` 清了）和换档重启（旧进程已经死透，
     * 还留在这一格的就是它至死没看过的）。两处都靠同一条判据 —— **没被 [confirm] 收走
     * 就等于没被模型看见**，所以原样重发既不会丢也不会重。
     */
    fun reclaim() {
        synchronized(lock) {
            while (handedOff.isNotEmpty()) held.addFirst(handedOff.removeLast())
        }
    }

    /**
     * 取出全部并清空：held 在前、handedOff 在后，各自保序。
     *
     * 给 teardown 用。[clear] 是直接丢掉，而进程被停掉时这两格里的话用户都打过、
     * 模型都还没看见，该交给落盘兜底而不是蒸发。
     */
    fun drain(): List<T> = synchronized(lock) {
        val all = held.toList() + handedOff.toList()
        held.clear()
        handedOff.clear()
        all
    }

    /** 进程没了，队列跟着作废 */
    fun clear() {
        synchronized(lock) {
            held.clear()
            handedOff.clear()
        }
    }
}
