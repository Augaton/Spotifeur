package fr.spotifeur.data.preferences

private const val MIN_NAV_BAR_CORNER_RADIUS = 0
private const val MAX_NAV_BAR_CORNER_RADIUS = 50

internal fun sanitizeNavBarCornerRadius(radius: Int): Int =
    radius.coerceIn(MIN_NAV_BAR_CORNER_RADIUS, MAX_NAV_BAR_CORNER_RADIUS)

object ThemePreference {
    const val DEFAULT = "default"
    const val DYNAMIC = "dynamic"
    const val ALBUM_ART = "album_art"
    const val GLOBAL = "global"
}

enum class AlbumArtQuality(val maxSize: Int, val label: String) {
    LOW(256, "Low (256px) - Better performance"),
    MEDIUM(512, "Medium (512px) - Balanced"),
    HIGH(800, "High (800px) - Best quality"),
    ORIGINAL(0, "Original - Maximum quality")
}
