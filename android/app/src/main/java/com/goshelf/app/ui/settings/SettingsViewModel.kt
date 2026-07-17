package com.goshelf.app.ui.settings

import androidx.lifecycle.ViewModel
import com.goshelf.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val downloadDir: String = "",
    val saved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = SettingsUiState(
            serverUrl = settingsRepository.getServerUrl(),
            downloadDir = settingsRepository.getDownloadDirectory()
        )
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, saved = false)
    }

    fun updateDownloadDir(dir: String) {
        _uiState.value = _uiState.value.copy(downloadDir = dir, saved = false)
    }

    fun save() {
        val state = _uiState.value
        settingsRepository.setServerUrl(state.serverUrl)
        settingsRepository.setDownloadDirectory(state.downloadDir)
        _uiState.value = state.copy(saved = true)
    }
}
