package com.example.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.CameraLens
import com.example.data.SettingsRepository
import com.example.data.VideoQuality
import com.example.privilege.PrivilegeSetupManager
import com.example.qs.TileVisibilityManager
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecorderService : LifecycleService() {

  private lateinit var settingsRepository: SettingsRepository
  private var cameraProvider: ProcessCameraProvider? = null
  private var activeRecording: Recording? = null
  private var mediaRecorder: MediaRecorder? = null
  private var audioPfd: ParcelFileDescriptor? = null
  private var audioUri: Uri? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var isStopping = false
  private var audioStartMillis = 0L

  override fun onCreate() {
    super.onCreate()
    settingsRepository = SettingsRepository(applicationContext)
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    when (intent?.action) {
      ACTION_START_VIDEO -> {
        promoteForeground(includeCamera = true, includeMic = PrivilegeSetupManager.hasAudioPermission(this))
        if (CaptureState.recordingMode.value == RecordingMode.NONE) {
          isStopping = false
          acquireWakeLock()
          startVideoRecording()
        }
      }
      ACTION_START_AUDIO -> {
        promoteForeground(includeCamera = false, includeMic = true)
        if (CaptureState.recordingMode.value == RecordingMode.NONE) {
          isStopping = false
          acquireWakeLock()
          startAudioRecording()
        }
      }
      ACTION_STOP -> stopActiveRecording()
    }
    return START_NOT_STICKY
  }

  private fun promoteForeground(includeCamera: Boolean, includeMic: Boolean) {
    val type =
      when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
          var flags = 0
          if (includeCamera) flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
          if (includeMic) flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
          flags
        }
        else -> 0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildStopNotification(), type)
  }

  private fun ensureNotificationChannel() {
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
          }
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildStopNotification(): Notification {
    val stopIntent =
      Intent(this, CaptureActivity::class.java).apply {
        action = CaptureActivity.ACTION_STOP
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
    val stopPendingIntent =
      PendingIntent.getActivity(
        this,
        101,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_capture)
      .setContentTitle(getString(R.string.notif_stop_recording))
      .setOngoing(true)
      .setSilent(true)
      .setShowWhen(false)
      .setOnlyAlertOnce(true)
      .setContentIntent(stopPendingIntent)
      .addAction(0, getString(R.string.notif_stop_recording), stopPendingIntent)
      .build()
  }

  @SuppressLint("MissingPermission")
  private fun startVideoRecording() {
    if (!PrivilegeSetupManager.hasCameraPermission(this)) {
      failAndStop("Camera permission denied")
      return
    }
    if (!StorageHelper.hasSufficientStorage(StorageHelper.MIN_VIDEO_BYTES)) {
      failAndStop("Low storage")
      return
    }

    CaptureState.setRecordingMode(RecordingMode.VIDEO)
    TileVisibilityManager.requestTilesRefresh(this)

    val mainExecutor: Executor = ContextCompat.getMainExecutor(this)
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          cameraProvider = provider
          lifecycleScope.launch {
            val settings = settingsRepository.snapshot()
            bindAndStartVideo(provider, settings.lens, settings.videoQuality, mainExecutor)
          }
        } catch (t: Throwable) {
          failAndStop(t.message ?: "Camera unavailable")
        }
      },
      mainExecutor,
    )
  }

  @SuppressLint("MissingPermission")
  private fun bindAndStartVideo(
    provider: ProcessCameraProvider,
    lens: CameraLens,
    quality: VideoQuality,
    mainExecutor: Executor,
  ) {
    try {
      val preferredSelector =
        if (lens == CameraLens.FRONT) {
          CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
          CameraSelector.DEFAULT_BACK_CAMERA
        }
      val selector =
        when {
          provider.hasCamera(preferredSelector) -> preferredSelector
          provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
          provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
          else -> {
            failAndStop("No camera available")
            return
          }
        }

      val targetQuality =
        if (quality == VideoQuality.FHD_1080) Quality.FHD else Quality.HD
      val qualitySelector =
        QualitySelector.from(
          targetQuality,
          FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
      val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
      val videoCapture = VideoCapture.withOutput(recorder)

      provider.unbindAll()
      provider.bindToLifecycle(this, selector, videoCapture)

      val outputOptions = StorageHelper.createVideoOutputOptions(contentResolver)
      val pendingRecording =
        videoCapture.output.prepareRecording(this, outputOptions).apply {
          if (PrivilegeSetupManager.hasAudioPermission(this@RecorderService)) {
            withAudioEnabled()
          }
        }

      activeRecording =
        pendingRecording.start(mainExecutor) { event ->
          if (event is VideoRecordEvent.Finalize) {
            activeRecording = null
            val hasOutput = event.outputResults.outputUri != Uri.EMPTY
            if (!event.hasError()) {
              StorageHelper.vibrateSaved(this@RecorderService)
            } else if (
              event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
            ) {
              recordStatusAsync(
                if (hasOutput) "Low storage — video saved partially" else "Low storage"
              )
              StorageHelper.vibrateError(this@RecorderService)
            } else if (hasOutput && isStopping) {
              StorageHelper.vibrateSaved(this@RecorderService)
            } else {
              recordStatusAsync("Video capture failed (${event.error})")
              StorageHelper.vibrateError(this@RecorderService)
            }
            cleanupAndStopSelf()
          }
        }
    } catch (t: Throwable) {
      failAndStop(t.message ?: "Camera in use")
    }
  }

  private fun startAudioRecording() {
    if (!PrivilegeSetupManager.hasAudioPermission(this)) {
      failAndStop("Microphone permission denied")
      return
    }
    if (!StorageHelper.hasSufficientStorage(StorageHelper.MIN_PHOTO_OR_AUDIO_BYTES)) {
      failAndStop("Low storage")
      return
    }

    CaptureState.setRecordingMode(RecordingMode.AUDIO)
    TileVisibilityManager.requestTilesRefresh(this)

    try {
      val uri =
        StorageHelper.createAudioMediaStoreEntry(contentResolver)
          ?: run {
            failAndStop("Cannot create audio file")
            return
          }
      val pfd =
        contentResolver.openFileDescriptor(uri, "rw")
          ?: run {
            StorageHelper.deleteMediaStoreEntry(contentResolver, uri)
            failAndStop("Cannot open audio output")
            return
          }

      audioUri = uri
      audioPfd = pfd

      val recorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          MediaRecorder(this)
        } else {
          @Suppress("DEPRECATION")
          MediaRecorder()
        }

      recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(128_000)
        setOutputFile(pfd.fileDescriptor)
        setOnErrorListener { _, _, _ ->
          recordStatusAsync("Audio recorder error")
          StorageHelper.vibrateError(this@RecorderService)
          finalizeAudioRecorder(deleteIfEmpty = false)
          cleanupAndStopSelf()
        }
        setOnInfoListener { _, what, _ ->
          if (
            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
              what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
          ) {
            finalizeAudioRecorder(deleteIfEmpty = false)
            StorageHelper.vibrateSaved(this@RecorderService)
            cleanupAndStopSelf()
          }
        }
        prepare()
        start()
      }

      mediaRecorder = recorder
      audioStartMillis = System.currentTimeMillis()
    } catch (t: Throwable) {
      finalizeAudioRecorder(deleteIfEmpty = true)
      failAndStop(t.message ?: "Microphone in use")
    }
  }

  private fun stopActiveRecording() {
    if (isStopping) return
    isStopping = true

    when (CaptureState.recordingMode.value) {
      RecordingMode.VIDEO -> {
        val rec = activeRecording
        if (rec != null) {
          rec.stop()
        } else {
          cleanupAndStopSelf()
        }
      }
      RecordingMode.AUDIO -> {
        val saved = finalizeAudioRecorder(deleteIfEmpty = false)
        if (saved) {
          StorageHelper.vibrateSaved(this)
        } else {
          StorageHelper.vibrateError(this)
        }
        cleanupAndStopSelf()
      }
      RecordingMode.NONE -> cleanupAndStopSelf()
    }
  }

  private fun finalizeAudioRecorder(deleteIfEmpty: Boolean): Boolean {
    val recorder = mediaRecorder
    mediaRecorder = null
    var stoppedCleanly = false

    if (recorder != null) {
      stoppedCleanly =
        runCatching {
          recorder.stop()
          true
        }.getOrDefault(false)
      runCatching { recorder.reset() }
      runCatching { recorder.release() }
    }

    runCatching { audioPfd?.close() }
    audioPfd = null

    val uri = audioUri
    audioUri = null

    val elapsed = System.currentTimeMillis() - audioStartMillis
    return if (uri != null && (!deleteIfEmpty && (stoppedCleanly || elapsed > 300L))) {
      StorageHelper.finalizeMediaStoreEntry(contentResolver, uri)
      true
    } else {
      StorageHelper.deleteMediaStoreEntry(contentResolver, uri)
      false
    }
  }

  private fun failAndStop(reason: String) {
    recordStatusAsync(reason)
    StorageHelper.vibrateError(this)
    cleanupAndStopSelf()
  }

  private fun recordStatusAsync(status: String) {
    lifecycleScope.launch {
      withContext(Dispatchers.IO) { settingsRepository.setLastStatus(status) }
    }
  }

  private fun cleanupAndStopSelf() {
    runCatching { cameraProvider?.unbindAll() }
    cameraProvider = null
    CaptureState.setRecordingMode(RecordingMode.NONE)
    TileVisibilityManager.requestTilesRefresh(this)
    releaseWakeLock()
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(PowerManager::class.java) ?: return
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RecorderWakeLock").apply {
        setReferenceCounted(false)
        acquire(4L * 60L * 60L * 1000L)
      }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    }
    wakeLock = null
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Ensure partial files are finalized if the task is swiped or killed mid-recording.
    finalizeBeforeDestroy()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    finalizeBeforeDestroy()
    super.onDestroy()
  }

  private fun finalizeBeforeDestroy() {
    isStopping = true
    activeRecording?.let {
      runCatching { it.stop() }
      activeRecording = null
    }
    if (mediaRecorder != null || audioUri != null) {
      finalizeAudioRecorder(deleteIfEmpty = false)
    }
    runCatching { cameraProvider?.unbindAll() }
    cameraProvider = null
    CaptureState.setRecordingMode(RecordingMode.NONE)
    TileVisibilityManager.requestTilesRefresh(this)
    releaseWakeLock()
  }

  companion object {
    const val ACTION_START_VIDEO = "com.example.action.START_VIDEO"
    const val ACTION_START_AUDIO = "com.example.action.START_AUDIO"
    const val ACTION_STOP = "com.example.action.STOP_RECORDING"

    private const val CHANNEL_ID = "captura_recording"
    private const val NOTIFICATION_ID = 4001

    fun startVideoIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_START_VIDEO }

    fun startAudioIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_START_AUDIO }

    fun stopIntent(context: Context): Intent =
      Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
  }
}
