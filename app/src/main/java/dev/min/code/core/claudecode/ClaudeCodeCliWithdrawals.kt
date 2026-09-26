package dev.min.code.core.claudecode

/*
 * CLI 撤回它发给 Min 的 control_request（`control_cancel_request`）之后，Min 这边的两件事：
 * 记住哪些 request_id 已经不用答了、在对话流里怎么说一句。
 * 什么时候撤卡、写不写 stdin 仍由 ClaudeCodeManager 调度。
 */

/**
 * 被 CLI 撤回的 request_id。
 *
 * 为什么光撤卡不够：权限卡的「取走」和撤回是两条路，用户的点按可能已经在路上
 * （AskUserQuestion 的应答是先读后清，托管 Bash 要等服务出结论才回 deny）。
 * 应答写 stdin 之前最后查一次这里，撤回过的就不写 —— CLI 对撤回后的应答本来就忽略，
 * 但写过去只会在它的日志里多一条「unknown request_id」，也让「答了没有」这件事在两边对不上。
 *
 * request_id 是 CLI 生成的 UUID，不会复用；只留最近 [CAPACITY] 条，够覆盖一轮里挂过的请求。
 */
internal class WithdrawnCliRequests {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun add(requestId: String) {
        ids.remove(requestId)
        ids.add(requestId)
        while (ids.size > CAPACITY) ids.remove(ids.first())
    }

    @Synchronized
    operator fun contains(requestId: String): Boolean = requestId in ids

    companion object {
        /** 一轮里 CLI 同时挂着的请求通常就一两条；留宽一点，防止连续打断时早撤的被挤掉 */
        const val CAPACITY = 64
    }
}

/**
 * 撤回时对话流里留的那一句；返回 null 表示不用说。
 *
 * - [byOurInterrupt]：是用户自己按了停止，CLI 顺手撤掉挂着的请求 —— 随后的「已中断」已经说明了，
 *   再补一句是噪声（夹具 interrupt_during_permission 就是这一路）。
 * - 其余情况 CLI 不给原因（帧里只有 request_id），不猜是哪一种，把可能的几种说出来：
 *   无头 stdio 下常见的是 PermissionRequest 钩子先做了决定，或别的客户端先答了。
 *   注意 2.1.281 那个「危险 rm 两分钟没人答自动拒」的计时只挂在终端界面的对话框上，
 *   stdio 的 can_use_tool 不走它（js283：autoDenyWindow 只在交互对话框的竞速里消费），
 *   所以这里不说「超时」。
 */
internal fun cliWithdrawalNote(
    withdrawn: ClaudeCodeEvent.PermissionRequest,
    byOurInterrupt: Boolean,
): String? {
    if (byOurInterrupt) return null
    val what = if (withdrawn.toolName == ASK_USER_QUESTION_TOOL) {
        "这次提问"
    } else {
        "「${withdrawn.toolName.ifBlank { "工具" }}」的权限请求"
    }
    return "CLI 撤回了$what，不用再回答了（可能是钩子或别处已经先作了决定，或这一轮被中止）"
}
