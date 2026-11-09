package fr.spotifeur.data

/**
 * Audio quality presets. On the HLS path the service offers two AAC variants per track,
 * 128 and 256 kbit/s, so these are the levels a preset can select.
 */
enum class AudioQuality(val key: String) {
    /** 256 kbit/s on an unmetered connection, 128 kbit/s on a metered one. */
    AUTO("auto"),
    NORMAL("normal"),
    VERY_HIGH("very_high");

    fun bandwidth(metered: Boolean): Long = when (this) {
        AUTO -> if (metered) NORMAL_BANDWIDTH else VERY_HIGH_BANDWIDTH
        NORMAL -> NORMAL_BANDWIDTH
        VERY_HIGH -> VERY_HIGH_BANDWIDTH
    }

    companion object {
        const val NORMAL_BANDWIDTH = 128_000L
        const val VERY_HIGH_BANDWIDTH = 256_000L
        fun of(key: String): AudioQuality = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
