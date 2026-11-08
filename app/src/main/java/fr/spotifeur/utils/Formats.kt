package fr.spotifeur.utils


fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"

    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatSongCount(count: Int): String {
    val french = java.util.Locale.getDefault().language == "fr"
    return when {
        french -> if (count > 1) "$count titres" else "$count titre"
        count == 1 -> "1 track"
        else -> "$count tracks"
    }
}

fun formatRadioTitle(name: String): String =
    if (java.util.Locale.getDefault().language == "fr") "Radio de $name" else "$name Radio"

private val storedTrackCount = Regex("""^(\d+) (titres?|tracks?)$""")

/** Track counts are stored as display text; show them in the current language. */
fun localizeTrackCount(subtitle: String): String =
    storedTrackCount.matchEntire(subtitle)?.let { formatSongCount(it.groupValues[1].toInt()) } ?: subtitle
