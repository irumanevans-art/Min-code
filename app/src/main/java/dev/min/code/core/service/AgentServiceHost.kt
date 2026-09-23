package dev.min.code.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * 会话管理器托管后台 Bash 时，对进程表的全部需求：起一个服务、看它的状态。
 *
 * [LocalServiceRegistry] 是唯一的实现。抽成接口只为了特征测试能换上假的进程表，
 * 控制服务「多久出结论、死没死」—— 真的进程表要起 proot，单测里起不来。
 */
interface AgentServiceHost {
    val services: StateFlow<List<LocalService>>

    /** 同 command+cwd+port 已在 Starting/Running 则复用 id，不重起 */
    suspend fun startFromAgent(
        command: String,
        cwdGuest: String = "/workspace",
        port: Int? = null,
        label: String? = null,
        sourceSessionKey: String? = null,
    ): Result<String>
}
