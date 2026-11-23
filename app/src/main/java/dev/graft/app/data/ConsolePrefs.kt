package dev.graft.app.data

import android.content.Context

/** Console preferences. Just one: whether commands the device can't run go to the cloud without asking. */
object ConsolePrefs {
    private const val PREFS = "graft_console_prefs"
    private const val KEY_AUTO_CLOUD = "auto_cloud"

    fun autoCloud(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CLOUD, false)

    fun setAutoCloud(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CLOUD, enabled).apply()
    }
}
