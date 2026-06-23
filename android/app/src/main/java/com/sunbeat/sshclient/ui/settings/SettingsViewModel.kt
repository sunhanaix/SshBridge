package com.sunbeat.sshclient.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sunbeat.sshclient.data.update.UpdateInfo
import com.sunbeat.sshclient.data.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val currentVersion: String = "",
    val updateInfo: UpdateInfo? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val statusMessage: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val updateManager = UpdateManager(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val pkgInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        _uiState.value = _uiState.value.copy(
            currentVersion = "${pkgInfo.versionName ?: "0.0.0"} (code ${pkgInfo.versionCode})",
        )
    }

    fun checkUpdate() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checking = true, statusMessage = null)
            val info = updateManager.checkUpdate()
            _uiState.value = _uiState.value.copy(
                checking = false,
                updateInfo = info,
                statusMessage = when {
                    info.updateAvailable -> "New version ${info.latestName} available!"
                    info.error != null -> info.error
                    else -> "You are up to date."
                },
            )
        }
    }

    fun downloadAndInstall() {
        val apkUrl = _uiState.value.updateInfo?.apkUrl ?: ""
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(downloading = true, statusMessage = "Downloading…")
            val result = updateManager.downloadAndInstall(apkUrl)
            _uiState.value = _uiState.value.copy(
                downloading = false,
                statusMessage = if (result.isSuccess) "Install started. Follow system prompts."
                else "Download failed: ${result.exceptionOrNull()?.message}",
            )
        }
    }
}
