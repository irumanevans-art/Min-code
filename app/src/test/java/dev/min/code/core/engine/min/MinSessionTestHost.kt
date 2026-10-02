package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionState
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** 会话的假宿主：真 Hub，其余只记录调用 */
class FakeHost(val kernel: TestKernel, override val lookup: MinModelLookup = FakeLookup()) : MinSessionHost {
    override val hub: SessionHub = kernel.hub
    override val text = FakeText()
    override val tasks = MinBackgroundTasks()
    override val settingsTick: Flow<Unit> = flowOf(Unit)
    override val maxRetryAttempts = 3
    val titles = MutableStateFlow<String?>(null)
    val renames = mutableListOf<String>()
    val defaults = mutableListOf<Pair<String, String>>()
    val killed = mutableListOf<String>()
    val drafts = mutableListOf<Pair<SessionRef, UserInput>>()
    val indexed = mutableListOf<SessionState>()
    val closed = mutableListOf<CloseReason>()

    override fun titleOverride(sessionId: String): Flow<String?> = titles
    override fun renameSession(sessionId: String, title: String) { renames += title }
    override fun rememberDefaultModel(profileId: String, modelId: String) { defaults += profileId to modelId }
    override suspend fun killTask(taskId: String) { killed += taskId }
    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = null
    override suspend fun searchFiles(cwd: String, query: String, limit: Int): List<String> = emptyList()
    override fun saveWithdrawnDraft(ref: SessionRef, input: UserInput) { drafts += ref to input }
    override suspend fun updateIndex(state: SessionState) { indexed += state }
    override suspend fun closeSession(session: MinAgentSession, reason: CloseReason) { closed += reason }
}
