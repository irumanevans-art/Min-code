package dev.min.code.core.session

import kotlinx.serialization.json.JsonObject

/**
 * 聊天流里的一条，**与具体引擎无关**。
 *
 * ## 为什么从 ClaudeCodeManager 里搬出来
 *
 * 这套模型本来嵌在 `ClaudeCodeManager` 里，于是 `ui/session/` 那整套渲染
 * （ClaudeCodeTranscript、ClaudeCodeToolViews、SessionSearch）都 when 在
 * `ClaudeCodeManager.ChatItem` 上 —— 想让 Codex 也有一样的会话界面，就只剩
 * 「照抄一份渲染层」这一条路，往后两边的气泡样式、工具卡、搜索高亮会各改各的。
 *
 * 其实这几个变体里没有任何一条是 Claude 特有的：用户说了什么、模型答了什么、
 * 想了什么、调了哪个工具、进程往 stderr 写了什么 —— Codex 的 app-server 协议
 * 一样能填满。所以把它提到引擎之上，两个引擎各自把自己的协议事件折成 [ChatItem]，
 * 渲染层只认这一种形状。
 *
 * 加变体前先问一句：Codex 那边填得出来吗？填不出来的东西属于引擎自己的状态
 * （Claude 的 permission_denials、Codex 的 execpolicy 修正案），
 * 应该待在各自的 SessionState 里，不要挤进这里。
 */
sealed interface ChatItem {
    val id: String

    /**
     * @param queued 生成中追加、还在排队等下一轮的消息。它还**没有**写进引擎，
     *   所以按 Esc 可以原样撤回输入框。
     */
    data class UserText(
        override val id: String,
        val text: String,
        val queued: Boolean = false,
    ) : ChatItem

    data class AssistantText(
        override val id: String,
        val text: String,
        /** 该条生成消息所属轮次的完成耗时与输出量。只在一轮结束后填充。 */
        val durationMs: Long? = null,
        val outputTokens: Int? = null,
    ) : ChatItem

    data class Thinking(override val id: String, val text: String) : ChatItem

    data class Note(override val id: String, val text: String, val isError: Boolean = false) : ChatItem

    /**
     * 引擎进程写到 stderr 的原样输出，连续的行并成一条。
     *
     * 存在的理由是「官方终端能看到的，这里也要能看到」：CLI 跑在真终端里时
     * stderr 就混在滚屏里，而这边是无头管道，stderr 从来没有出口。以前只有
     * 认得出的错误行会被提升成红字，其余**直接丢掉** —— Node 和 proot
     * 那些 deprecation 警告确实是噪声，但真东西偶尔就混在里面，丢掉之后连
     * 「它到底说了什么」都无从查起。
     *
     * 现在全部留下，但默认折叠、不算错误：可见 ≠ 报警。红字的提升规则没变。
     *
     * [dropped] 是因为超出上限而被丢掉的最旧行数（刷屏时不能把会话撑爆）。
     */
    data class ProcessOutput(
        override val id: String,
        val lines: List<String>,
        val dropped: Int = 0,
    ) : ChatItem

    data class ToolCall(
        override val id: String,
        val toolUseId: String,
        val name: String,
        val input: JsonObject,
        /** Running -> Done/Error；权限等待中也是 Running，由各引擎的 pendingPermission 表达 */
        val status: Status,
        val result: String? = null,
        val isError: Boolean = false,
        /**
         * 改文件后的 unified diff。和 [result]（stdout）分开存，
         * 展开态可以画 DiffView 而不是塞进纯文本。
         */
        val editDiff: String? = null,
        /**
         * 这次调用如果派生了子 agent，子 agent 干的活挂在这里。展开工具卡就能看
         * 子任务的完整过程 —— 官方终端只给一个折叠的计数行和最终报告，看不到里面。
         */
        val subItems: List<ChatItem> = emptyList(),
    ) : ChatItem {
        enum class Status { Running, Done, Error }
    }
}

/** 一条引擎会话的生命周期。两个引擎的取值一致，顶栏和列表按它上色。 */
enum class SessionStatus { Idle, Starting, Running, Closed, Failed }
