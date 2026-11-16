package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class PrivilegeMode(val label: String) {
  ROOT("Root"),
  SHIZUKU("Shizuku"),
}

enum class CameraLens(val label: String) {
  BACK("Back"),
  FRONT("Front"),
}

enum class VideoQuality(val label: String) {
  HD_720("720p"),
  FHD_1080("1080p"),
}

data class CapturaSettings(
  val privilegeMode: PrivilegeMode = PrivilegeMode.SHIZUKU,
  val lens: CameraLens = CameraLens.BACK,
  val videoQuality: VideoQuality = VideoQuality.FHD_1080,
  val showQuickSettingsTile: Boolean = true,
  val lastStatus: String = "",
)

private val Context.capturaDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "captura_settings"
)

class SettingsRepository(private val context: Context) {

  private object Keys {
    val PRIVILEGE_MODE = stringPreferencesKey("privilege_mode")
    val CAMERA_LENS = stringPreferencesKey("camera_lens")
    val VIDEO_QUALITY = stringPreferencesKey("video_quality")
    val SHOW_QS_TILE = booleanPreferencesKey("show_qs_tile")
    val LAST_STATUS = stringPreferencesKey("last_status")
  }

  val settingsFlow: Flow<CapturaSettings> =
    context.capturaDataStore.data.map { prefs ->
      CapturaSettings(
        privilegeMode =
          prefs[Keys.PRIVILEGE_MODE]?.let { runCatching { PrivilegeMode.valueOf(it) }.getOrNull() }
            ?: PrivilegeMode.SHIZUKU,
        lens =
          prefs[Keys.CAMERA_LENS]?.let { runCatching { CameraLens.valueOf(it) }.getOrNull() }
            ?: CameraLens.BACK,
        videoQuality =
          prefs[Keys.VIDEO_QUALITY]?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() }
            ?: VideoQuality.FHD_1080,
        showQuickSettingsTile = prefs[Keys.SHOW_QS_TILE] ?: true,
        lastStatus = prefs[Keys.LAST_STATUS] ?: "",
      )
    }

  suspend fun snapshot(): CapturaSettings = settingsFlow.first()

  suspend fun setPrivilegeMode(mode: PrivilegeMode) {
    context.capturaDataStore.edit { it[Keys.PRIVILEGE_MODE] = mode.name }
  }

  suspend fun setLens(lens: CameraLens) {
    context.capturaDataStore.edit { it[Keys.CAMERA_LENS] = lens.name }
  }

  suspend fun setVideoQuality(quality: VideoQuality) {
    context.capturaDataStore.edit { it[Keys.VIDEO_QUALITY] = quality.name }
  }

  suspend fun setShowQuickSettingsTile(show: Boolean) {
    context.capturaDataStore.edit { it[Keys.SHOW_QS_TILE] = show }
  }

  suspend fun setLastStatus(status: String) {
    context.capturaDataStore.edit { it[Keys.LAST_STATUS] = status }
  }
}
