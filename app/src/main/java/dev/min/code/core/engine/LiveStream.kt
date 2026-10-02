package dev.min.code.core.engine

/**
 * 生成中的那一块：逐字增量单独走一条流，和结构化的 [AgentSessionState] 分开。
 *
 * 为什么分开：增量一秒几十次，如果它在 state 里，每个 token 都会让 `items` 所在的整页重新比对；
 * 分开之后只有「正在长的那条回答」在重组，历史条目的引用不变，Compose 直接跳过。
 *
 * 适配器在 Engine 的作用域里订阅内核 / CLI 的瞬时流，与界面生命周期无关：转屏、VM 重建都不丢。
 * 瞬时流本身没有 replay，所以必须尽早订阅；界面接上时若缓冲是空的，而 state 里已有落盘的片段，
 * 就先显示片段（Min：`turn.openRequest.segments`）。
 *
 * @param turnId 增量属于哪个回合。界面据此在回合切换时丢掉旧缓冲，防止上一轮的尾巴挂到下一轮头上
 * @param text 生成中那条回答的正文（落盘的片段 + 后续增量）
 * @param thinking 生成中的思考
 * @param toolDrafts 模型正在写参数的工具调用
 * @param toolOutput toolUseId → 运行中工具输出的尾巴，截断规则同 `ClippedOutput`（留头、记总长）
 */
data class LiveStream(
    val turnId: String?,
    val text: String,
    val thinking: String,
    val toolDrafts: List<ToolDraft>,
    val toolOutput: Map<String, String>,
) {
    /** 没有任何增量可画。界面据此决定要不要给「正在生成」留一行 */
    val isEmpty: Boolean
        get() = text.isEmpty() && thinking.isEmpty() && toolDrafts.isEmpty() && toolOutput.isEmpty()

    companion object {
        val Empty = LiveStream(null, "", "", emptyList(), emptyMap())
    }
}

/**
 * 模型正在写参数、还没写完的一次工具调用（`partialJson` 是半截 JSON，不要去解析它，只当文本显示）。
 * [toolUseId] 在调用 id 到达之前为 null（CC 的 content_block_start 才带 id，按 [index] 对号）。
 */
data class ToolDraft(val index: Int, val toolUseId: String?, val name: String, val partialJson: String)

/** 一次性事件：错过了就是错过了，所以是 `replay = 0` 的 SharedFlow，不进 state */
sealed interface SessionSignal {
    /**
     * 撤回 / 停止时退回输入框的话。[input] 与「再按一次发送」会发出去的东西完全一致（正文已合成好，
     * 图片原样），所以输入框原样接住即可，不必还原成几个 chip。
     *
     * 设计草稿写的是 `Withdrawn(draft: ComposerDraft)`，但 `ComposerDraft` 在 `core/claudecode` 里，
     * 通用层不许引用，这里换成通用的 [UserInput]；界面把它转成自己的草稿类型。
     * 没人收的话由适配器自己落盘（CC 现状：`emitWithdrawn` 的磁盘兜底），不要指望这条流有订阅者。
     */
    data class Withdrawn(val input: UserInput) : SessionSignal

    data class PreviewUrl(val url: String) : SessionSignal

    data class Notice(val text: String, val isError: Boolean) : SessionSignal
}
