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
    override val workspaceFs: dev.min.agent.spi.WorkspaceFs = kernel.deps.host.fs
    override val mcpLoginGate = McpLoginGate()
    override val snapshotRepos: dev.min.agent.host.CasWorkspaceSnapshots? = null
    override val settingsTick: Flow<Unit> = flowOf(Unit)
    override val maxRetryAttempts = 3
    val titles = MutableStateFlow<String?>(null)
    val defaults = mutableListOf<Pair<String, String>>()
    val killed = mutableListOf<String>()
    val drafts = mutableListOf<Pair<SessionRef, UserInput>>()
    val indexed = mutableListOf<SessionState>()
    val closed = mutableListOf<CloseReason>()
    val persisted = mutableListOf<Triple<RuleLayer, String, String>>()
    var persistResult: PersistRule = PersistRule.Written

    override fun titleOverride(sessionId: String): Flow<String?> = titles
    override fun rememberDefaultModel(profileId: String, modelId: String) { defaults += profileId to modelId }
    override suspend fun killTask(taskId: String) { killed += taskId }
    override suspend fun persistAllowRule(layer: RuleLayer, rule: String, cwd: String): PersistRule {
        persisted += Triple(layer, rule, cwd)
        return persistResult
    }
    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = null
    override suspend fun searchFiles(cwd: String, query: String, limit: Int): List<String> = emptyList()
    override fun saveWithdrawnDraft(ref: SessionRef, input: UserInput) { drafts += ref to input }
    override suspend fun updateIndex(state: SessionState) { indexed += state }
    override suspend fun closeSession(session: MinAgentSession, reason: CloseReason) { closed += reason }

    /** 分支走真 Hub；`/fork` 在后台接上的会话只记下来（id、提示词） */
    val forked = mutableListOf<Pair<String, String?>>()
    override suspend fun branchSession(id: String): dev.min.code.core.engine.BranchOutcome = try {
        dev.min.code.core.engine.BranchOutcome.Branched(kernel.hub.branch(dev.min.agent.protocol.SessionId(id)).sessionId.value)
    } catch (e: dev.min.agent.kernel.SessionBranchRefused) {
        dev.min.code.core.engine.BranchOutcome.Refused(e.message)
    }
    override suspend fun renameSession(id: String, title: String): Boolean =
        kernel.hub.get(dev.min.agent.protocol.SessionId(id))?.rename(title) != null
    override suspend fun startForked(id: String, prompt: String?): Boolean { forked += id to prompt; return true }
}
