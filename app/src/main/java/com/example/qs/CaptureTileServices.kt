package com.example.qs

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.R
import com.example.capture.CaptureActivity
import com.example.capture.CaptureState
import com.example.capture.RecordingMode
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object TileVisibilityManager {
  private val tileClasses =
    listOf(
      PhotoTileService::class.java,
      VideoTileService::class.java,
      AudioTileService::class.java,
    )

  fun setTilesEnabled(context: Context, enabled: Boolean) {
    val state =
      if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
      } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
      }
    val pm = context.packageManager
    for (cls in tileClasses) {
      pm.setComponentEnabledSetting(
        ComponentName(context, cls),
        state,
        PackageManager.DONT_KILL_APP,
      )
    }
  }

  fun requestTilesRefresh(context: Context) {
    for (cls in tileClasses) {
      runCatching { TileService.requestListeningState(context, ComponentName(context, cls)) }
    }
  }
}

abstract class BaseCaptureTileService : TileService() {

  protected val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  protected fun launchCaptureActivity(action: String, requestCode: Int) {
    val intent = CaptureActivity.createIntent(this, action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pi =
        PendingIntent.getActivity(
          this,
          requestCode,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      startActivityAndCollapse(pi)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }
}

class PhotoTileService : BaseCaptureTileService() {
  override fun onStartListening() {
    val tile = qsTile ?: return
    serviceScope.launch {
      val settings = SettingsRepository(applicationContext).snapshot()
      val videoActive = CaptureState.recordingMode.value == RecordingMode.VIDEO
      tile.state = if (videoActive) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
      tile.subtitle = settings.lens.label
      tile.updateTile()
    }
  }

  override fun onClick() {
    launchCaptureActivity(CaptureActivity.ACTION_PHOTO, 201)
  }
}

class VideoTileService : BaseCaptureTileService() {
  override fun onStartListening() {
    val tile = qsTile ?: return
    serviceScope.launch {
      val settings = SettingsRepository(applicationContext).snapshot()
      val mode = CaptureState.recordingMode.value
      tile.state =
        when (mode) {
          RecordingMode.VIDEO -> Tile.STATE_ACTIVE
          RecordingMode.AUDIO -> Tile.STATE_UNAVAILABLE
          RecordingMode.NONE -> Tile.STATE_INACTIVE
        }
      tile.subtitle =
        if (mode == RecordingMode.VIDEO) {
          getString(R.string.qs_state_recording)
        } else {
          "${settings.lens.label} · ${settings.videoQuality.label}"
        }
      tile.updateTile()
    }
  }

  override fun onClick() {
    val action =
      if (CaptureState.recordingMode.value == RecordingMode.VIDEO) {
        CaptureActivity.ACTION_STOP
      } else {
        CaptureActivity.ACTION_VIDEO
      }
    launchCaptureActivity(action, 202)
  }
}

class AudioTileService : BaseCaptureTileService() {
  override fun onStartListening() {
    val tile = qsTile ?: return
    val mode = CaptureState.recordingMode.value
    tile.state =
      when (mode) {
        RecordingMode.AUDIO -> Tile.STATE_ACTIVE
        RecordingMode.VIDEO -> Tile.STATE_UNAVAILABLE
        RecordingMode.NONE -> Tile.STATE_INACTIVE
      }
    tile.subtitle =
      if (mode == RecordingMode.AUDIO) {
        getString(R.string.qs_state_recording)
      } else {
        getString(R.string.qs_audio_subtitle)
      }
    tile.updateTile()
  }

  override fun onClick() {
    val action =
      if (CaptureState.recordingMode.value == RecordingMode.AUDIO) {
        CaptureActivity.ACTION_STOP
      } else {
        CaptureActivity.ACTION_AUDIO
      }
    launchCaptureActivity(action, 203)
  }
}
