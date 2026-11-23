package dev.graft.app.git

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Cancellation is real: the wait loop polls with a cancellable `delay`, and cancellation/finally
 * immediately kills the process and any child processes without blocking on native stream reads.
 */
object ShellRunner {
    private const val MAX_OUTPUT_CHARS = 200_000

    @OptIn(DelicateCoroutinesApi::class)
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

            // Kill immediately when this coroutine is cancelled.
            val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) killProcessTree(process)
            }

            try {
                val collected = StringBuilder()
                // Run outside coroutineScope so cancellation is never blocked by a stuck native read().
                val reader = GlobalScope.async(Dispatchers.IO) {
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
                        killProcessTree(process)
                        break
                    }
                    delay(50)
                }

                if (!timedOut) {
                    withTimeoutOrNull(500) { reader.await() }
                } else {
                    reader.cancel()
                }

                val code = if (timedOut) 124 else runCatching { process.exitValue() }.getOrDefault(-1)
                ShellResult(code, collected.toString().trimEnd(), timedOut)
            } finally {
                cancelHook?.dispose()
                killProcessTree(process)
            }
        }

    private fun killProcessTree(process: Process) {
        // Kill descendants first so orphaned child processes (like `sleep`) don't keep stdout open.
        runCatching {
            val toHandle = Process::class.java.getMethod("toHandle")
            val handle = toHandle.invoke(process)
            val descendants = handle.javaClass.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>
            val destroyForcibly = Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly")
            descendants.forEach { child -> runCatching { destroyForcibly.invoke(child) } }
        }
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
