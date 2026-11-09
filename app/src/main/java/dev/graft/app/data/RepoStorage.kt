package dev.graft.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Where cloned repos (and downloaded build APKs) live on disk.
 *
 * Two modes, chosen in Settings:
 *
 * - [Mode.APP] (default) - the app's own external files directory. Works the moment the app is
 *   installed, with no permission prompt. JGit needs a real `java.io.File`, and this is the
 *   simplest place to get one safely.
 * - [Mode.PUBLIC] - `/storage/emulated/0/.Graft/`. Visible to file managers, Termux and a PC
 *   over USB, so you can patch files with other tools. The cost is the "All files access" special
 *   permission on Android 11+ (a manual toggle in system Settings - there is no normal popup).
 *
 * Existing repos keep working across a mode switch: each repo's absolute path is stored on its row,
 * not recomputed from the root. Only *new* clones, the local-folder scan and the APK downloads
 * folder follow the current mode.
 */
object RepoStorage {
    enum class Mode { APP, PUBLIC }

    private const val PREFS_NAME = "graft_storage_prefs"
    private const val KEY_MODE = "mode"
    private const val PUBLIC_FOLDER = ".Graft"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString(KEY_MODE, null) ?: "") }.getOrDefault(Mode.APP)

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** The overall root - parent of both `repos/` and `apk-downloads/`. Not created by merely asking. */
    fun rootDir(context: Context): File = when (mode(context)) {
        Mode.APP -> context.getExternalFilesDir(null) ?: context.filesDir
        Mode.PUBLIC -> File(Environment.getExternalStorageDirectory(), PUBLIC_FOLDER)
    }

    /** Root folder for all cloned repos. Creates it if needed. */
    fun reposRootDir(context: Context): File =
        File(rootDir(context), "repos").also { it.mkdirs() }

    /** Parent of every per-repo APK subfolder. Not created by merely asking. */
    fun apkDownloadsRootDir(context: Context): File = File(rootDir(context), "apk-downloads")

    /**
     * Where an extracted build APK is saved: `apk-downloads/<repoName>/`, deliberately *outside* the
     * repo's working copy - a file dropped into the working tree would show up as an untracked
     * change forever. Disposable: each install overwrites the previous one.
     */
    fun apkDownloadsDir(context: Context, repoName: String): File =
        File(apkDownloadsRootDir(context), repoName.ifBlank { "unknown-repo" }).also { it.mkdirs() }

    fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** True when the current mode's folder is actually readable/writable right now. */
    fun hasStorageAccess(context: Context): Boolean =
        mode(context) == Mode.APP || hasPublicAccess(context)

    /** Whether the special/runtime permission the PUBLIC mode needs has been granted. */
    fun hasPublicAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Intent for the All-files-access settings page (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
