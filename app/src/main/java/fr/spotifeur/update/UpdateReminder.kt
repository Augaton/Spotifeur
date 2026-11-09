package fr.spotifeur.update

/** A dismissal defers automatic reminders for this version for 24 hours. */
internal object UpdateReminder {
    const val DELAY_MS = 24L * 60 * 60 * 1000

    fun isDue(tag: String, dismissedTag: String?, dismissedAt: Long, now: Long): Boolean {
        val release = Version.parse(tag)
        val dismissed = dismissedTag?.let(Version::parse)
        if (release == null || dismissed == null || release.compareTo(dismissed) != 0) return true
        // A clock correction must not defer an update indefinitely.
        if (dismissedAt <= 0 || now < dismissedAt) return true
        return now - dismissedAt >= DELAY_MS
    }
}
