package com.example.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.CameraLens
import com.example.data.SettingsRepository
import com.example.privilege.PrivilegeSetupManager
import kotlinx.coroutines.launch

class CaptureActivity : ComponentActivity() {

  private lateinit var settingsRepository: SettingsRepository
  private val mainHandler = Handler(Looper.getMainLooper())
  private var cameraProvider: ProcessCameraProvider? = null
  private var pendingAction: String? = null

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      val action = pendingAction
      pendingAction = null
      val allNeededGranted =
        when (action) {
          ACTION_PHOTO -> PrivilegeSetupManager.hasCameraPermission(this)
          ACTION_VIDEO -> PrivilegeSetupManager.hasCameraPermission(this)
          ACTION_AUDIO -> PrivilegeSetupManager.hasAudioPermission(this)
          else -> results.values.all { it }
        }
      if (allNeededGranted && action != null) {
        dispatchAction(action, allowPrompt = false)
      } else {
        lifecycleScope.launch { settingsRepository.setLastStatus("Permission denied") }
        StorageHelper.vibrateError(this)
        finishCleanly()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settingsRepository = SettingsRepository(applicationContext)
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    val action = intent?.action ?: ACTION_PHOTO
    dispatchAction(action, allowPrompt = true)
  }

  private fun dispatchAction(action: String, allowPrompt: Boolean) {
    when (action) {
      ACTION_STOP -> {
        startService(RecorderService.stopIntent(this))
        finishCleanly()
      }
      ACTION_PHOTO -> handlePhoto(allowPrompt)
      ACTION_VIDEO -> handleVideo(allowPrompt)
      ACTION_AUDIO -> handleAudio(allowPrompt)
      else -> finishCleanly()
    }
  }

  private fun handlePhoto(allowPrompt: Boolean) {
    // Photo and video share camera hardware; keep photo allowed while audio records.
    if (CaptureState.recordingMode.value == RecordingMode.VIDEO) {
      StorageHelper.vibrateError(this)
      finishCleanly()
      return
    }
    if (!PrivilegeSetupManager.hasCameraPermission(this)) {
      if (allowPrompt) {
        pendingAction = ACTION_PHOTO
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
      } else {
        finishCleanly()
      }
      return
    }
    if (!StorageHelper.hasSufficientStorage(StorageHelper.MIN_PHOTO_OR_AUDIO_BYTES)) {
      lifecycleScope.launch { settingsRepository.setLastStatus("Low storage") }
      StorageHelper.vibrateError(this)
      finishCleanly()
      return
    }
    if (!CaptureState.isPhotoCapturing.compareAndSet(false, true)) {
      finishCleanly()
      return
    }

    // Safety guard so a stalled camera HAL never leaves the translucent activity alive.
    mainHandler.postDelayed(
      {
        if (!isFinishing) {
          CaptureState.isPhotoCapturing.set(false)
          finishCleanly()
        }
      },
      3_500L,
    )

    val mainExecutor = ContextCompat.getMainExecutor(this)
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          cameraProvider = provider
          lifecycleScope.launch {
            val settings = settingsRepository.snapshot()
            takePhotoNow(provider, settings.lens, mainExecutor)
          }
        } catch (t: Throwable) {
          onPhotoFailure(t.message ?: "Camera unavailable")
        }
      },
      mainExecutor,
    )
  }

  private fun takePhotoNow(
    provider: ProcessCameraProvider,
    lens: CameraLens,
    mainExecutor: java.util.concurrent.Executor,
  ) {
    try {
      val preferred =
        if (lens == CameraLens.FRONT) {
          CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
          CameraSelector.DEFAULT_BACK_CAMERA
        }
      val selector =
        when {
          provider.hasCamera(preferred) -> preferred
          provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
          provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
          else -> {
            onPhotoFailure("No camera available")
            return
          }
        }

      val imageCapture =
        ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .build()

      provider.unbindAll()
      provider.bindToLifecycle(this, selector, imageCapture)

      val outputOptions = StorageHelper.createPhotoOutputOptions(contentResolver)
      imageCapture.takePicture(
        outputOptions,
        mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
          override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
            StorageHelper.vibrateSaved(this@CaptureActivity)
            releaseCameraAndFinish()
          }

          override fun onError(exception: ImageCaptureException) {
            onPhotoFailure(exception.message ?: "Photo capture failed")
          }
        },
      )
    } catch (t: Throwable) {
      onPhotoFailure(t.message ?: "Camera in use")
    }
  }

  private fun onPhotoFailure(message: String) {
    lifecycleScope.launch { settingsRepository.setLastStatus(message) }
    StorageHelper.vibrateError(this)
    releaseCameraAndFinish()
  }

  private fun releaseCameraAndFinish() {
    runCatching { cameraProvider?.unbindAll() }
    cameraProvider = null
    CaptureState.isPhotoCapturing.set(false)
    finishCleanly()
  }

  private fun handleVideo(allowPrompt: Boolean) {
    when (CaptureState.recordingMode.value) {
      RecordingMode.VIDEO -> {
        startService(RecorderService.stopIntent(this))
        finishCleanly()
        return
      }
      RecordingMode.AUDIO -> {
        StorageHelper.vibrateError(this)
        finishCleanly()
        return
      }
      RecordingMode.NONE -> Unit
    }

    val missing =
      buildList {
        if (!PrivilegeSetupManager.hasCameraPermission(this@CaptureActivity)) {
          add(Manifest.permission.CAMERA)
        }
        if (!PrivilegeSetupManager.hasAudioPermission(this@CaptureActivity)) {
          add(Manifest.permission.RECORD_AUDIO)
        }
      }
    if (missing.isNotEmpty() && !PrivilegeSetupManager.hasCameraPermission(this)) {
      if (allowPrompt) {
        pendingAction = ACTION_VIDEO
        permissionLauncher.launch(missing.toTypedArray())
      } else {
        finishCleanly()
      }
      return
    }
    if (!StorageHelper.hasSufficientStorage(StorageHelper.MIN_VIDEO_BYTES)) {
      lifecycleScope.launch { settingsRepository.setLastStatus("Low storage") }
      StorageHelper.vibrateError(this)
      finishCleanly()
      return
    }

    ContextCompat.startForegroundService(this, RecorderService.startVideoIntent(this))
    finishCleanly()
  }

  private fun handleAudio(allowPrompt: Boolean) {
    when (CaptureState.recordingMode.value) {
      RecordingMode.AUDIO -> {
        startService(RecorderService.stopIntent(this))
        finishCleanly()
        return
      }
      RecordingMode.VIDEO -> {
        StorageHelper.vibrateError(this)
        finishCleanly()
        return
      }
      RecordingMode.NONE -> Unit
    }

    if (!PrivilegeSetupManager.hasAudioPermission(this)) {
      if (allowPrompt) {
        pendingAction = ACTION_AUDIO
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
      } else {
        finishCleanly()
      }
      return
    }
    if (!StorageHelper.hasSufficientStorage(StorageHelper.MIN_PHOTO_OR_AUDIO_BYTES)) {
      lifecycleScope.launch { settingsRepository.setLastStatus("Low storage") }
      StorageHelper.vibrateError(this)
      finishCleanly()
      return
    }

    ContextCompat.startForegroundService(this, RecorderService.startAudioIntent(this))
    finishCleanly()
  }

  private fun finishCleanly() {
    mainHandler.removeCallbacksAndMessages(null)
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
  }

  override fun onDestroy() {
    mainHandler.removeCallbacksAndMessages(null)
    runCatching { cameraProvider?.unbindAll() }
    CaptureState.isPhotoCapturing.set(false)
    super.onDestroy()
  }

  companion object {
    const val ACTION_PHOTO = "com.example.capture.PHOTO"
    const val ACTION_VIDEO = "com.example.capture.VIDEO"
    const val ACTION_AUDIO = "com.example.capture.AUDIO"
    const val ACTION_STOP = "com.example.capture.STOP"

    fun createIntent(context: Context, action: String): Intent =
      Intent(context, CaptureActivity::class.java).apply {
        this.action = action
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
      }
  }
}
