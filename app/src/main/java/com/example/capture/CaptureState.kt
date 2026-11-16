package com.example.capture

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingMode {
  NONE,
  VIDEO,
  AUDIO,
}

object CaptureState {
  private val _recordingMode = MutableStateFlow(RecordingMode.NONE)
  val recordingMode: StateFlow<RecordingMode> = _recordingMode.asStateFlow()

  val isPhotoCapturing = AtomicBoolean(false)

  fun setRecordingMode(mode: RecordingMode) {
    _recordingMode.value = mode
  }
}
