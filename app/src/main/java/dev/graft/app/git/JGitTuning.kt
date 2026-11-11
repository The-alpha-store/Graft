package dev.graft.app.git

import android.util.Log
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.WindowCacheConfig
import java.io.File

/**
 * Startup-time JGit tuning. Two independent wins, both invisible to the rest of the app:
 *
 * **1. A realistic file-window cache.** JGit reads pack files through a shared cache whose defaults
 * come from a desktop-era configuration: 8 KiB windows and a 10 MiB budget. Every delta resolution,
 * `status`, `log` and checkout on a non-trivial repo goes through it, and with 8 KiB windows that means
 * a great many tiny reads. Larger windows and budget cut that overhead substantially. The numbers are
 * deliberately modest - this runs on phones - and all are far below an Android app's heap limit.
 *
 * **2. A warm-up.** JGit's first use in a process pays for a pile of one-time work: class loading,
 * `SystemReader`/`FS` detection, config parsing, service lookups for commit signing. That cost lands
 * on whatever the user does first, which is why the first "create repo" or "commit" felt slow. A
 * throwaway init/add/commit/status in a temp directory, on a low-priority background thread right at
 * launch, moves that cost off the user's critical path.
 */
object JGitTuning {
    private const val TAG = "JGitTuning"

    fun install(cacheDir: File) {
        runCatching {
            val config = WindowCacheConfig()
            config.setPackedGitWindowSize(128 * 1024)       // must be a power of two
            config.setPackedGitLimit(48L * 1024 * 1024)
            config.setDeltaBaseCacheLimit(24 * 1024 * 1024)
            config.install()
        }.onFailure { Log.w(TAG, "window cache tuning failed", it) }

        Thread({ warmUp(cacheDir) }, "jgit-warmup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun warmUp(cacheDir: File) {
        val dir = File(cacheDir, "jgit-warmup-${System.nanoTime()}")
        var git: Git? = null
        try {
            dir.mkdirs()
            git = Git.init().setDirectory(dir).call()
            File(dir, "warmup.txt").writeText("warm\n")
            git.add().addFilepattern("warmup.txt").call()
            val id = PersonIdent("warmup", "warmup@localhost")
            git.commit().setMessage("warmup").setAuthor(id).setCommitter(id).call()
            git.status().call()
        } catch (t: Throwable) {
            Log.w(TAG, "warm-up failed (harmless)", t)
        } finally {
            runCatching { git?.close() }
            runCatching { dir.deleteRecursively() }
        }
    }
}
