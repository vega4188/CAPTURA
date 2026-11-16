package com.example.privilege

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.PrivilegeMode

data class PrivilegeApplyResult(
  val modeOk: Boolean,
  val statusMessage: String,
  val needsRuntimeFallback: Boolean,
)

class PrivilegeSetupManager(private val context: Context) {

  private val rootExecutor = RootExecutor()
  private val shizukuExecutor = ShizukuExecutor(context)

  fun evaluateQuickStatus(mode: PrivilegeMode): String {
    val permsOk = missingRuntimePermissions(context).isEmpty()
    return when (mode) {
      PrivilegeMode.ROOT -> if (permsOk) "Mode OK" else "Root selected — tap Apply permissions"
      PrivilegeMode.SHIZUKU ->
        when (val state = shizukuExecutor.checkState()) {
          ShizukuState.READY -> if (permsOk) "Mode OK" else "Shizuku ready — tap Apply permissions"
          ShizukuState.NOT_INSTALLED ->
            if (permsOk) "Shizuku not installed (runtime permissions active)"
            else "Shizuku not installed"
          ShizukuState.NOT_RUNNING ->
            if (permsOk) "Shizuku not running (runtime permissions active)"
            else "Shizuku not running"
          ShizukuState.PERMISSION_REQUIRED -> "Shizuku permission not granted"
          ShizukuState.UNSUPPORTED_VERSION -> "Shizuku v11+ required"
        }
    }
  }

  suspend fun applyPermissions(mode: PrivilegeMode): PrivilegeApplyResult {
    if (mode == PrivilegeMode.SHIZUKU && shizukuExecutor.checkState() == ShizukuState.PERMISSION_REQUIRED) {
      val granted = shizukuExecutor.requestPermission()
      if (!granted) {
        return PrivilegeApplyResult(
          modeOk = false,
          statusMessage = "Shizuku permission not granted — falling back to runtime dialogs",
          needsRuntimeFallback = true,
        )
      }
    }

    val executor: PrivilegedExecutor =
      when (mode) {
        PrivilegeMode.ROOT -> rootExecutor
        PrivilegeMode.SHIZUKU -> shizukuExecutor
      }

    val pkg = context.packageName
    val commands = buildList {
      add("pm grant $pkg ${Manifest.permission.CAMERA}")
      add("pm grant $pkg ${Manifest.permission.RECORD_AUDIO}")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
      }
      add("cmd appops set $pkg CAMERA allow")
      add("cmd appops set $pkg RECORD_AUDIO allow")
      add("cmd deviceidle whitelist +$pkg")
    }

    for (cmd in commands) {
      val res = executor.run(cmd)
      if (res.isFailure) {
        val err = res.exceptionOrNull()?.message ?: "Privileged setup failed"
        return PrivilegeApplyResult(
          modeOk = false,
          statusMessage = "$err — falling back to runtime permissions",
          needsRuntimeFallback = true,
        )
      }
    }

    return PrivilegeApplyResult(
      modeOk = true,
      statusMessage = "Mode OK",
      needsRuntimeFallback = false,
    )
  }

  companion object {
    fun requiredRuntimePermissions(): Array<String> =
      buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }.toTypedArray()

    fun missingRuntimePermissions(context: Context): Array<String> =
      requiredRuntimePermissions()
        .filter {
          ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        .toTypedArray()

    fun hasCameraPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    fun hasAudioPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
  }
}
