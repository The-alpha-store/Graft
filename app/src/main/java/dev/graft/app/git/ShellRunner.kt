package dev.graft.app.git

import kotlinx.coroutines.Dispatchers
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

/**
 * Runs a command through the device's own `sh` inside a repo folder.
 *
 * What this *is*: Android ships a real (toybox) userland - `ls cat grep find sed head tail wc sort cp mv rm
 * mkdir touch diff...` - so everyday file inspection and editing from the console genuinely works.
 *
 * What it is *not*: a way to run toolchains. There's no `cargo`, `npm`, `python`, `gradle`... on a stock
 * Android system, and an app can neither borrow Termux's binaries (separate sandbox) nor execute
 * downloaded ones (blocked since Android 10). Those exit 127 here, which the console turns into an offer to
 * run the command in the cloud instead.
 *
 * Cancellation is real: the wait loop polls with a cancellable `delay`, and the `finally` always kills the
 * process, so the Stop button works and nothing is left running if the screen goes away.
 */
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
                // Cancelled (Stop pressed / screen gone) or finished: never leave the process behind.
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
