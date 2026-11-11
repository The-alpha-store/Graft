package dev.graft.app.transfer

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any of the app's activities is on screen. Lets [TransferManager] post a "finished"
 * notification only when the person isn't already looking at the app - in the foreground, the
 * transfer row and snackbar are the feedback, and a notification on top would be noise.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0

    val isVisible: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
