package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsVM(private val store: SettingsStore) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * 用户明确放弃那串打不开的密文，腾位置重填。
     *
     * 连接表的增删改查搬去了 `ProvidersVM`，但这一条留在设置页：它不是「管某一条
     * 供应商」，而是整份凭据存储的状态 —— 密文打不开时供应商页整页是只读的，
     * 那个页面自己没法把自己解开。
     */
    fun discardUnreadableCredentials() = viewModelScope.launch { store.discardUnreadableCredentials() }

    fun setUseNpmMirror(enabled: Boolean) = viewModelScope.launch { store.setUseNpmMirror(enabled) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
    fun setSkin(style: SkinStyle) = viewModelScope.launch { store.setSkin(style) }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch { store.setAppLanguage(language) }
}
