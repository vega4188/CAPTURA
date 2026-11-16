package com.example.capture

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageHelper {

  const val MIN_PHOTO_OR_AUDIO_BYTES = 20L * 1024L * 1024L
  const val MIN_VIDEO_BYTES = 50L * 1024L * 1024L

  fun timestampName(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

  fun hasSufficientStorage(minBytes: Long): Boolean {
    return try {
      val stat = StatFs(Environment.getExternalStorageDirectory().path)
      stat.availableBytes >= minBytes
    } catch (_: Throwable) {
      true
    }
  }

  fun createPhotoOutputOptions(contentResolver: ContentResolver): ImageCapture.OutputFileOptions {
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${timestampName()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Captura")
      }
    return ImageCapture.OutputFileOptions.Builder(
      contentResolver,
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      values,
    ).build()
  }

  fun createVideoOutputOptions(contentResolver: ContentResolver): MediaStoreOutputOptions {
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${timestampName()}.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Captura")
      }
    return MediaStoreOutputOptions.Builder(
      contentResolver,
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )
      .setContentValues(values)
      .build()
  }

  fun createAudioMediaStoreEntry(contentResolver: ContentResolver): Uri? {
    val name = "${timestampName()}.m4a"
    val primaryPath =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "${Environment.DIRECTORY_RECORDINGS}/Captura"
      } else {
        "Recordings/Captura"
      }

    fun tryInsert(relativePath: String): Uri? {
      val values =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, name)
          put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
          put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      return contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    }

    return try {
      tryInsert(primaryPath)
    } catch (_: IllegalArgumentException) {
      // Android 10/11 MediaStore restricts Audio RELATIVE_PATH to Music/Podcasts/etc.
      tryInsert("${Environment.DIRECTORY_MUSIC}/Captura")
    }
  }

  fun finalizeMediaStoreEntry(contentResolver: ContentResolver, uri: Uri?) {
    if (uri == null) return
    runCatching {
      val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      contentResolver.update(uri, values, null, null)
    }
  }

  fun deleteMediaStoreEntry(contentResolver: ContentResolver, uri: Uri?) {
    if (uri == null) return
    runCatching { contentResolver.delete(uri, null, null) }
  }

  fun vibrateSaved(context: Context) {
    vibrate(context, VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  fun vibrateError(context: Context) {
    vibrate(
      context,
      VibrationEffect.createWaveform(longArrayOf(0L, 35L, 45L, 35L), -1),
    )
  }

  private fun vibrate(context: Context, effect: VibrationEffect) {
    runCatching {
      val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val manager = context.getSystemService(VibratorManager::class.java)
          manager?.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
      vibrator?.vibrate(effect)
    }
  }
}
