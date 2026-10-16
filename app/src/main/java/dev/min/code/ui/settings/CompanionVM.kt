package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.companion.CompanionKeeper
import dev.min.code.core.companion.CompanionKind
import dev.min.code.core.companion.CompanionMemory
import dev.min.code.core.companion.CompanionStore
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.StartChoices
import dev.min.code.core.settings.CompanionSettings
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置 →「情感特供」那一页：开关、提取用的模型、记忆的查看与手改 */
class CompanionVM(
    private val settings: SettingsStore,
    private val store: CompanionStore,
    private val keeper: CompanionKeeper,
    registry: AgentSessionRegistry,
) : ViewModel() {
    val config: StateFlow<CompanionSettings> = settings.settings.map { it.companion }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionSettings())

    val memory: StateFlow<CompanionMemory> = store.memory

    /**
     * 能选的供应商档与模型：借 Min 开始面板那份表（有 key 的 API 档 + 各自的模型），
     * 它不看 Min 引擎开没开，所以只用 Claude Code 的人也能选。订阅登录没有 key，不在表里
     */
    val choices: StateFlow<StartChoices?> = registry.engine(EngineKind.MinAgent).startChoices

    init {
        viewModelScope.launch { store.load() }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setCompanionEnabled(enabled) }
    fun setModel(profileId: String, modelId: String) = viewModelScope.launch { settings.setCompanionModel(profileId, modelId.trim()) }
    fun editNote(id: String, text: String) = viewModelScope.launch { store.editNote(id, text, System.currentTimeMillis()) }
    fun addNote(kind: CompanionKind, text: String) = viewModelScope.launch { store.addNote(kind, text, System.currentTimeMillis()) }
    fun deleteNote(id: String) = viewModelScope.launch { store.deleteNote(id) }
    fun clear() = viewModelScope.launch { store.clear() }
    fun flushNow() = keeper.flushNow()
}
