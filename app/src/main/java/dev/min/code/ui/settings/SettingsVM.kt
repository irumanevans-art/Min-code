package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsVM(private val store: SettingsStore) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setToken(token: String) = viewModelScope.launch { store.setToken(token) }
    fun setBaseUrl(url: String) = viewModelScope.launch { store.setBaseUrl(url) }
    fun setUseNpmMirror(enabled: Boolean) = viewModelScope.launch { store.setUseNpmMirror(enabled) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
}
