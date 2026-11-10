package fr.spotifeur.data.preferences

import org.json.JSONArray
import org.json.JSONObject

/** The shelves the home screen can show. */
enum class HomeShelf(val key: String, val styles: List<ShelfStyle>, val counts: Boolean = true) {
    PINS("pins", listOf(ShelfStyle.COLLAGE, ShelfStyle.PILLS, ShelfStyle.CAROUSEL, ShelfStyle.GRID, ShelfStyle.LIST)),
    RECENT("recent", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    DOWNLOADS("downloads", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    LIKED("liked", listOf(ShelfStyle.CARD), counts = false),
    PLAYLISTS("playlists", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    MULTI_SERVICE("multi_service", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    SPOTIFY_PLAYLISTS("spotify_playlists", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    YOUTUBE_PLAYLISTS("youtube_playlists", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    ALBUMS("albums", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    ARTISTS("artists", listOf(ShelfStyle.CAROUSEL, ShelfStyle.LARGE, ShelfStyle.GRID, ShelfStyle.LIST, ShelfStyle.PILLS)),
    LOCAL_FILES("local_files", listOf(ShelfStyle.LIST, ShelfStyle.CAROUSEL, ShelfStyle.GRID, ShelfStyle.PILLS)),
}

/** How a shelf lays out its items. */
enum class ShelfStyle(val key: String) {
    /** Covers in a horizontal row, title under each cover. */
    CAROUSEL("carousel"),
    /** The same row with larger covers. */
    LARGE("large"),
    /** Two columns of covers. */
    GRID("grid"),
    /** Rows with a small cover, one item per line. */
    LIST("list"),
    /** Two columns of rounded pills tinted from each cover. */
    PILLS("pills"),
    /** Pins only: the artwork collage, then the pills. */
    COLLAGE("collage"),
    /** Liked songs only: the card with its play button. */
    CARD("card"),
}

data class ShelfConfig(val shelf: HomeShelf, val visible: Boolean, val style: ShelfStyle, val count: Int, val showTitle: Boolean = true)

/** Home screen layout: shelves in display order. Unknown or missing shelves get their defaults. */
object HomeLayout {
    val COUNTS = listOf(4, 6, 10, 20, 50)

    val DEFAULT: List<ShelfConfig> = listOf(
        ShelfConfig(HomeShelf.PINS, true, ShelfStyle.COLLAGE, 50),
        ShelfConfig(HomeShelf.RECENT, false, ShelfStyle.CAROUSEL, 10),
        ShelfConfig(HomeShelf.DOWNLOADS, true, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.LIKED, true, ShelfStyle.CARD, 1),
        ShelfConfig(HomeShelf.PLAYLISTS, true, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.MULTI_SERVICE, false, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.SPOTIFY_PLAYLISTS, false, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.YOUTUBE_PLAYLISTS, false, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.ALBUMS, true, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.ARTISTS, true, ShelfStyle.CAROUSEL, 20),
        ShelfConfig(HomeShelf.LOCAL_FILES, false, ShelfStyle.LIST, 6),
    )

    fun decode(encoded: String): List<ShelfConfig> {
        if (encoded.isBlank()) return DEFAULT
        val read = try {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val shelf = HomeShelf.entries.firstOrNull { it.key == entry.optString("shelf") } ?: return@mapNotNull null
                val fallback = DEFAULT.first { it.shelf == shelf }
                val style = ShelfStyle.entries.firstOrNull { it.key == entry.optString("style") }?.takeIf { it in shelf.styles } ?: fallback.style
                ShelfConfig(shelf, entry.optBoolean("visible", fallback.visible), style,
                    entry.optInt("count", fallback.count).coerceIn(1, 50), entry.optBoolean("title", true))
            }.distinctBy { it.shelf }
        } catch (_: org.json.JSONException) { return DEFAULT }
        return read + DEFAULT.filter { default -> read.none { it.shelf == default.shelf } }
    }

    fun encode(shelves: List<ShelfConfig>): String = JSONArray().apply {
        shelves.forEach { put(JSONObject().put("shelf", it.shelf.key).put("visible", it.visible).put("style", it.style.key).put("count", it.count).put("title", it.showTitle)) }
    }.toString()
}
