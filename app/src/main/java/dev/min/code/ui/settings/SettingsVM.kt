package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.settings.AppLanguage
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

    /** [insecureAck]：地址是明文 http 时，用户已经在确认框上点过「仍然使用」 */
    fun addProfile(label: String, token: String, baseUrl: String, insecureAck: Boolean = false) =
        viewModelScope.launch { store.addProfile(label, token, baseUrl, insecureAck) }

    fun updateProfile(id: String, label: String, token: String, baseUrl: String, insecureAck: Boolean = false) =
        viewModelScope.launch { store.updateProfile(id, label, token, baseUrl, insecureAck) }

    fun deleteProfile(id: String) = viewModelScope.launch { store.deleteProfile(id) }

    /** 用户明确放弃那串打不开的密文，腾位置重填 */
    fun discardUnreadableCredentials() = viewModelScope.launch { store.discardUnreadableCredentials() }

    fun setActiveProfile(id: String, acknowledgeInsecure: Boolean = false) =
        viewModelScope.launch { store.setActiveProfile(id, acknowledgeInsecure) }
    fun setUseNpmMirror(enabled: Boolean) = viewModelScope.launch { store.setUseNpmMirror(enabled) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch { store.setAppLanguage(language) }
}
