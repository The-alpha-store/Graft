package dev.graft.app.transfer

import java.util.Locale

/** "0 B", "812 B", "1.5 KB", "12.4 MB", "1.20 GB". Binary units, labelled the way people expect. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1023.5) return String.format(Locale.US, if (kb < 10) "%.1f KB" else "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1023.5) return String.format(Locale.US, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

/** "8s left", "2m 5s left", "1h 3m left". */
fun formatEta(seconds: Long): String = when {
    seconds < 0 -> ""
    seconds < 60 -> "${seconds}s left"
    seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s left"
    else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m left"
}

/** The one-line status under a transfer's title: phase, size, speed, time left - whatever's known. */
fun TransferTask.statusLine(): String {
    val parts = ArrayList<String>(4)
    when (state) {
        TransferState.QUEUED -> return "Waiting…"
        TransferState.SUCCEEDED -> return message ?: "Done"
        TransferState.FAILED -> return message ?: "Failed"
        TransferState.CANCELLED -> return "Cancelled"
        TransferState.RUNNING -> Unit
    }
    if (phase.isNotBlank()) parts.add(phase.removePrefix("remote: "))
    if (bytes > 0) parts.add(formatBytes(bytes))
    if (bytesPerSec > 0) parts.add(formatSpeed(bytesPerSec))
    else if (unitsTotal > 0) parts.add("$unitsDone / $unitsTotal")
    val eta = formatEta(etaSeconds)
    if (eta.isNotEmpty()) parts.add(eta)
    return if (parts.isEmpty()) "Starting…" else parts.joinToString(" · ")
}
