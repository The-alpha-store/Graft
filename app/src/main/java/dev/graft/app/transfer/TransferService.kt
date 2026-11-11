package dev.graft.app.transfer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.graft.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while transfers are active. Its job is not to do the work
 * (that's [TransferManager]'s scope) but to tell Android "this app is busy with something the user
 * asked for": keeps the process from being killed when the app is backgrounded, and carries the
 * progress notification. It stops itself the moment the last transfer finishes.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransferNotifier.createChannels(this)
        val manager = (application as App).transferManager

        if (intent?.action == ACTION_CANCEL_ALL) manager.cancelAll()

        // startForeground must happen promptly after startForegroundService(), whatever the state is.
        val active = manager.tasks.value.filter { it.state.isActive }
        startInForeground(TransferNotifier.buildProgress(this, active))

        if (observer == null) {
            observer = scope.launch {
                // conflate + delay = at most ~2 notification updates a second; the system silently
                // drops updates that come faster than that anyway.
                manager.tasks.conflate().collect { tasks ->
                    val now = tasks.filter { it.state.isActive }
                    if (now.isEmpty()) {
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        runCatching {
                            NotificationManagerCompat.from(this@TransferService)
                                .notify(TransferNotifier.ID_PROGRESS, TransferNotifier.buildProgress(this@TransferService, now))
                        }
                        delay(UPDATE_INTERVAL_MS)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching {
            ServiceCompat.startForeground(this, TransferNotifier.ID_PROGRESS, notification, type)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL_ALL = "dev.graft.app.transfer.CANCEL_ALL"
        private const val UPDATE_INTERVAL_MS = 500L
    }
}
