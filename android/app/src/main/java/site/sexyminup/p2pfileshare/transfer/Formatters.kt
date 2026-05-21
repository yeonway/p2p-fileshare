package site.sexyminup.p2pfileshare.transfer

import java.util.Locale
import kotlin.math.roundToLong

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.roundToLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

fun formatSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond.roundToLong())}/s"

fun formatEta(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "-"
    val total = seconds.roundToLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val rest = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%02d:%02d".format(minutes, rest)
    }
}

fun isAllowedServerUrl(value: String): Boolean {
    return value.trim().trimEnd('/') == "https://files.dcout.site"
}

fun chunkCount(fileSize: Long, chunkSize: Int): Long {
    require(chunkSize > 0) { "chunkSize must be positive" }
    if (fileSize == 0L) return 0
    return ((fileSize - 1) / chunkSize) + 1
}
