package com.example.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

interface PrivilegedExecutor {
  suspend fun run(cmd: String): Result<String>
}

class RootExecutor : PrivilegedExecutor {
  override suspend fun run(cmd: String): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        val process =
          ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
          process.destroyForcibly()
          return@withContext Result.failure(IllegalStateException("Root command timed out"))
        }
        val code = process.exitValue()
        if (code == 0) {
          Result.success(output)
        } else {
          val detail = output.ifBlank { "exit code $code" }
          Result.failure(IllegalStateException("Root denied or failed: $detail"))
        }
      } catch (e: IOException) {
        Result.failure(IllegalStateException("Root not available (su not found)"))
      } catch (t: Throwable) {
        Result.failure(IllegalStateException(t.message ?: "Root execution error"))
      }
    }
}

enum class ShizukuState {
  READY,
  NOT_INSTALLED,
  NOT_RUNNING,
  PERMISSION_REQUIRED,
  UNSUPPORTED_VERSION,
}

class ShizukuExecutor(private val context: Context) : PrivilegedExecutor {

  private val mutex = Mutex()

  private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
    Shizuku.UserServiceArgs(ComponentName(context, PrivilegedShellService::class.java))
      .daemon(false)
      .processNameSuffix("shell")
      .tag("captura_shell")
      .debuggable(BuildConfig.DEBUG)
      .version(BuildConfig.VERSION_CODE)
  }

  fun checkState(): ShizukuState {
    if (!Shizuku.pingBinder()) {
      return if (isShizukuAppInstalled()) {
        ShizukuState.NOT_RUNNING
      } else {
        ShizukuState.NOT_INSTALLED
      }
    }
    if (Shizuku.isPreV11()) {
      return ShizukuState.UNSUPPORTED_VERSION
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      return ShizukuState.PERMISSION_REQUIRED
    }
    return ShizukuState.READY
  }

  suspend fun requestPermission(timeoutMs: Long = 15_000L): Boolean {
    if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true

    val result =
      withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
          val reqCode = 0x5348
          val listener =
            object : Shizuku.OnRequestPermissionResultListener {
              override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == reqCode) {
                  Shizuku.removeRequestPermissionResultListener(this)
                  if (cont.isActive) {
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                  }
                }
              }
            }
          Shizuku.addRequestPermissionResultListener(listener)
          cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
          try {
            Shizuku.requestPermission(reqCode)
          } catch (_: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
          }
        }
      }
    return result == true
  }

  override suspend fun run(cmd: String): Result<String> =
    mutex.withLock {
      when (checkState()) {
        ShizukuState.NOT_INSTALLED ->
          return@withLock Result.failure(IllegalStateException("Shizuku not installed"))
        ShizukuState.NOT_RUNNING ->
          return@withLock Result.failure(IllegalStateException("Shizuku not running"))
        ShizukuState.UNSUPPORTED_VERSION ->
          return@withLock Result.failure(IllegalStateException("Shizuku v11+ required"))
        ShizukuState.PERMISSION_REQUIRED ->
          return@withLock Result.failure(IllegalStateException("Shizuku permission not granted"))
        ShizukuState.READY -> Unit
      }

      withContext(Dispatchers.IO) {
        var connection: ServiceConnection? = null
        try {
          val boundPair =
            withTimeoutOrNull(8_000L) {
              suspendCancellableCoroutine<Pair<IPrivilegedShellService, ServiceConnection>?> { cont ->
                val conn =
                  object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                      val iface = IPrivilegedShellService.Stub.asInterface(service)
                      if (cont.isActive) {
                        cont.resume(if (iface != null) iface to this else null)
                      }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                  }
                connection = conn
                try {
                  Shizuku.bindUserService(userServiceArgs, conn)
                } catch (_: Throwable) {
                  if (cont.isActive) cont.resume(null)
                }
              }
            }

          val (service, activeConn) =
            boundPair
              ?: return@withContext Result.failure(
                IllegalStateException("Failed to bind Shizuku UserService")
              )

          val raw = service.runCommand(cmd)
          val newlineIdx = raw.indexOf('\n')
          val exitCode =
            if (newlineIdx >= 0) raw.substring(0, newlineIdx).toIntOrNull() ?: 1 else 1
          val output = if (newlineIdx >= 0) raw.substring(newlineIdx + 1).trim() else raw.trim()

          runCatching { Shizuku.unbindUserService(userServiceArgs, activeConn, true) }

          if (exitCode == 0) {
            Result.success(output)
          } else {
            val detail = output.ifBlank { "exit code $exitCode" }
            Result.failure(IllegalStateException("Shizuku command failed: $detail"))
          }
        } catch (t: Throwable) {
          connection?.let { runCatching { Shizuku.unbindUserService(userServiceArgs, it, true) } }
          Result.failure(IllegalStateException(t.message ?: "Shizuku execution error"))
        }
      }
    }

  private fun isShizukuAppInstalled(): Boolean {
    return try {
      context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }
}
