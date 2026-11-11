package dev.graft.app.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notifications for transfers: one ongoing, low-priority progress notification while anything runs
 * (this is also the foreground service's notification), and a normal-priority one when a transfer
 * finishes while the app isn't on screen.
 *
 * Progress is deliberately silent and updates in place - a chime every time a bar moves would be
 * unbearable. Only the result is allowed to make a sound.
 */
object TransferNotifier {
    const val CHANNEL_PROGRESS = "graft_transfers"
    const val CHANNEL_RESULT = "graft_transfer_results"
    const val ID_PROGRESS = 4100
    private const val ID_RESULT_BASE = 4200

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Transfers in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of clones, pulls and pushes running in the background"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a clone, pull or push finishes or fails"
            },
        )
    }

    private fun openTransfersIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("graft://transfers")).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAllIntent(context: Context): PendingIntent {
        val intent = Intent(context, TransferService::class.java).setAction(TransferService.ACTION_CANCEL_ALL)
        return PendingIntent.getService(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The ongoing notification for the currently [active] tasks (may be empty while winding down). */
    fun buildProgress(context: Context, active: List<TransferTask>): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openTransfersIntent(context))

        when {
            active.isEmpty() -> builder.setContentTitle("Finishing up…").setProgress(0, 0, true)
            active.size == 1 -> {
                val task = active.first()
                builder.setContentTitle("${task.kind.doing} ${task.title}")
                    .setContentText(task.statusLine())
                if (task.state == TransferState.RUNNING && !task.indeterminate) {
                    builder.setProgress(100, (task.progress * 100).toInt().coerceIn(0, 100), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
            else -> {
                val running = active.filter { it.state == TransferState.RUNNING }
                val average = if (running.isEmpty()) 0f else running.map { it.progress }.average().toFloat()
                builder.setContentTitle("${active.size} transfers")
                    .setContentText("${running.size} running, ${active.size - running.size} waiting")
                    .setStyle(
                        NotificationCompat.InboxStyle().also { style ->
                            active.take(5).forEach { style.addLine("${it.title} · ${it.statusLine()}") }
                        },
                    )
                if (running.isNotEmpty()) {
                    builder.setProgress(100, (average * 100).toInt().coerceIn(0, 100), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
        }
        if (active.isNotEmpty()) builder.addAction(0, "Cancel", cancelAllIntent(context))
        return builder.build()
    }

    /** A finished/failed notification. No-ops without the notification permission. */
    fun notifyResult(context: Context, task: TransferTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val ok = task.state == TransferState.SUCCEEDED
        val title = if (ok) "${task.kind.label} finished" else "${task.kind.label} failed"
        val text = "${task.title}: ${task.message ?: if (ok) "Done" else "Something went wrong"}"
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openTransfersIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_RESULT_BASE + (task.id % 100).toInt(), notification)
        }
    }
}
