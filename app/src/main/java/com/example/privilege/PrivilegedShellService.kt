package com.example.privilege

import kotlin.system.exitProcess

class PrivilegedShellService : IPrivilegedShellService.Stub() {

  override fun runCommand(cmd: String?): String {
    val command = cmd ?: return "1\nEmpty command"
    return try {
      val process =
        ProcessBuilder("sh", "-c", command)
          .redirectErrorStream(true)
          .start()
      val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
      val exitCode = process.waitFor()
      "$exitCode\n$output"
    } catch (t: Throwable) {
      "1\n${t.message ?: t::class.java.simpleName}"
    }
  }

  override fun destroy() {
    exitProcess(0)
  }
}
