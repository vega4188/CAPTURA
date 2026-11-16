package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CameraLens
import com.example.data.CapturaSettings
import com.example.data.PrivilegeMode
import com.example.data.SettingsRepository
import com.example.data.VideoQuality
import com.example.privilege.PrivilegeSetupManager
import com.example.qs.TileVisibilityManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class SettingsUiState(
  val settings: CapturaSettings = CapturaSettings(),
  val statusLine: String = "Checking mode…",
  val isModeOk: Boolean = false,
  val isApplying: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = SettingsRepository(application)
  private val setupManager = PrivilegeSetupManager(application)

  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  private val _runtimePermissionRequest = MutableSharedFlow<Array<String>>(extraBufferCapacity = 1)
  val runtimePermissionRequest: SharedFlow<Array<String>> = _runtimePermissionRequest.asSharedFlow()

  private val binderReceivedListener =
    Shizuku.OnBinderReceivedListener { refreshStatus() }
  private val binderDeadListener =
    Shizuku.OnBinderDeadListener { refreshStatus() }

  init {
    runCatching {
      Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
      Shizuku.addBinderDeadListener(binderDeadListener)
    }

    viewModelScope.launch {
      repository.settingsFlow.collect { settings ->
        val quickStatus = setupManager.evaluateQuickStatus(settings.privilegeMode)
        val displayStatus =
          when {
            settings.lastStatus == "Mode OK" -> quickStatus
            settings.lastStatus.isNotBlank() -> settings.lastStatus
            else -> quickStatus
          }
        _uiState.update {
          it.copy(
            settings = settings,
            statusLine = displayStatus,
            isModeOk = displayStatus == "Mode OK",
          )
        }
      }
    }
  }

  fun refreshStatus() {
    val currentMode = _uiState.value.settings.privilegeMode
    val status = setupManager.evaluateQuickStatus(currentMode)
    _uiState.update { it.copy(statusLine = status, isModeOk = status == "Mode OK") }
  }

  fun selectPrivilegeMode(mode: PrivilegeMode) {
    viewModelScope.launch {
      repository.setLastStatus("")
      repository.setPrivilegeMode(mode)
    }
  }

  fun setLens(lens: CameraLens) {
    viewModelScope.launch {
      repository.setLens(lens)
      TileVisibilityManager.requestTilesRefresh(getApplication())
    }
  }

  fun setVideoQuality(quality: VideoQuality) {
    viewModelScope.launch {
      repository.setVideoQuality(quality)
      TileVisibilityManager.requestTilesRefresh(getApplication())
    }
  }

  fun setShowQuickSettingsTile(show: Boolean) {
    viewModelScope.launch {
      repository.setShowQuickSettingsTile(show)
      TileVisibilityManager.setTilesEnabled(getApplication(), show)
      if (show) {
        TileVisibilityManager.requestTilesRefresh(getApplication())
      }
    }
  }

  fun applyPermissions() {
    if (_uiState.value.isApplying) return
    viewModelScope.launch {
      _uiState.update { it.copy(isApplying = true) }
      val mode = _uiState.value.settings.privilegeMode
      val result = setupManager.applyPermissions(mode)

      if (result.modeOk) {
        repository.setLastStatus("Mode OK")
        _uiState.update {
          it.copy(isApplying = false, statusLine = "Mode OK", isModeOk = true)
        }
      } else {
        repository.setLastStatus(result.statusMessage)
        _uiState.update {
          it.copy(isApplying = false, statusLine = result.statusMessage, isModeOk = false)
        }
        if (result.needsRuntimeFallback) {
          val missing = PrivilegeSetupManager.missingRuntimePermissions(getApplication())
          if (missing.isNotEmpty()) {
            _runtimePermissionRequest.tryEmit(missing)
          } else {
            val fallbackStatus = "${result.statusMessage} (runtime permissions already granted)"
            repository.setLastStatus(fallbackStatus)
            _uiState.update { it.copy(statusLine = fallbackStatus) }
          }
        }
      }
    }
  }

  fun onRuntimePermissionsResult(results: Map<String, Boolean>) {
    viewModelScope.launch {
      val missing = PrivilegeSetupManager.missingRuntimePermissions(getApplication())
      val previous = _uiState.value.statusLine.substringBefore(" (runtime").trim()
      val updated =
        if (missing.isEmpty()) {
          "$previous (runtime permissions granted)"
        } else {
          val deniedNames = missing.joinToString(", ") { it.substringAfterLast('.') }
          "$previous (denied: $deniedNames)"
        }
      repository.setLastStatus(updated)
      _uiState.update { it.copy(statusLine = updated, isModeOk = false) }
    }
  }

  override fun onCleared() {
    runCatching {
      Shizuku.removeBinderReceivedListener(binderReceivedListener)
      Shizuku.removeBinderDeadListener(binderDeadListener)
    }
    super.onCleared()
  }
}
