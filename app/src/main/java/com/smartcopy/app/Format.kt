package com.smartcopy.app

import java.util.Locale

private const val KB = 1024.0
private const val MB = 1024.0 * 1024.0
private const val GB = 1024.0 * 1024.0 * 1024.0

fun formatBytes(bytes: Long): String {
    val v = bytes.toDouble()
    return when {
        v >= GB -> String.format(Locale.FRANCE, "%.2f Go", v / GB)
        v >= MB -> String.format(Locale.FRANCE, "%.1f Mo", v / MB)
        v >= KB -> String.format(Locale.FRANCE, "%.0f Ko", v / KB)
        else -> "$bytes o"
    }
}

fun formatSpeed(bytesPerSecond: Double): String = formatBytes(bytesPerSecond.toLong()) + "/s"

fun formatMBps(mbPerSecond: Double): String = String.format(Locale.FRANCE, "%.0f Mo/s", mbPerSecond)

fun formatDuration(ms: Long): String {
    if (ms < 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.FRANCE, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.FRANCE, "%02d:%02d", m, s)
}
