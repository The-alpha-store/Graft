package dev.graft.app.ui.components

/**
 * "just now", "5m ago", "3h ago", "yesterday", "4d ago", then a plain month/day for anything older
 * than a month. Pure and clock-injectable so it's trivially unit-testable.
 */
fun relativeTimeMillis(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "never"
    val seconds = ((nowMillis - epochMillis) / 1000L).coerceAtLeast(0L)
    return formatRelativeSeconds(seconds)
}

fun formatRelativeSeconds(seconds: Long): String = when {
    seconds < 60 -> "just now"
    seconds < 3_600 -> "${seconds / 60}m ago"
    seconds < 86_400 -> "${seconds / 3_600}h ago"
    seconds < 172_800 -> "yesterday"
    seconds < 2_592_000 -> "${seconds / 86_400}d ago"
    seconds < 31_536_000 -> "${seconds / 2_592_000}mo ago"
    else -> "${seconds / 31_536_000}y ago"
}
