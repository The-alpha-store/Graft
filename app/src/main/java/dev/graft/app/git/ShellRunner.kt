package dev.graft.app.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
    /** `sh` exits 127 when the program doesn't exist - the signal the console uses to offer the cloud. */
    val commandNotFound: Boolean get() = exitCode == 127

    val succeeded: Boolean get() = exitCode == 0 && !timedOut
}

object ShellRunner {
    private const val MAX_OUTPUT_CHARS = 200_000

    suspend fun run(command: String, workDir: File, timeoutSec: Long = 120): ShellResult =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder("sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
            builder.environment()["TERM"] = "dumb"

            val process = try {
                builder.start()
            } catch (e: IOException) {
                return@withContext ShellResult(127, "sh: couldn't start a shell on this device (${e.message})")
            }

            // Kill immediately when this coroutine is cancelled, BEFORE coroutineScope waits for children.
            val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) killProcess(process)
            }

            try {
                coroutineScope {
                    val collected = StringBuilder()
                    val reader = async(Dispatchers.IO) {
                        runCatching {
                            process.inputStream.bufferedReader().use { r ->
                                val buf = CharArray(4096)
                                while (true) {
                                    val n = r.read(buf)
                                    if (n < 0) break
                                    if (collected.length < MAX_OUTPUT_CHARS) collected.append(buf, 0, n)
                                }
                            }
                        }
                    }

                    val deadline = System.currentTimeMillis() + timeoutSec * 1000
                    var timedOut = false
                    while (isAlive(process)) {
                        if (System.currentTimeMillis() > deadline) {
                            timedOut = true
                            killProcess(process)
                            break
                        }
                        delay(50)
                    }
                    reader.await()
                    val code = if (timedOut) 124 else runCatching { process.exitValue() }.getOrDefault(-1)
                    ShellResult(code, collected.toString().trimEnd(), timedOut)
                }
            } finally {
                cancelHook?.dispose()
                killProcess(process)
            }
        }

    private fun killProcess(process: Process) {
        if (isAlive(process)) {
            runCatching { process.destroyForcibly() }.onFailure { process.destroy() }
        }
        runCatching { process.inputStream.close() }
    }

    /** `Process.isAlive` needs API 26; the app supports 24. */
    private fun isAlive(process: Process): Boolean =
        try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
}
