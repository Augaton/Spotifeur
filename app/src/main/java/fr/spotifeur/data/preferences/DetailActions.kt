package fr.spotifeur.data.preferences

import org.json.JSONArray
import org.json.JSONObject

/** The buttons of an album, artist or playlist page. */
enum class DetailAction(val key: String) {
    DOWNLOAD("download"), ADD_TRACKS("add_tracks"), SAVE("save"), PIN("pin"), RADIO("radio"), SHARE("share"),
    SORT("sort"), REORDER("reorder"), EDIT("edit"), EXPORT("export"), DELETE("delete"),
}

/** Which page a layout applies to. Artist pages use the album layout. */
enum class DetailPage(val actions: List<DetailAction>, val defaultShown: Set<DetailAction>) {
    ALBUM(
        listOf(DetailAction.DOWNLOAD, DetailAction.SAVE, DetailAction.PIN, DetailAction.RADIO, DetailAction.SHARE),
        setOf(DetailAction.DOWNLOAD, DetailAction.SAVE, DetailAction.PIN),
    ),
    PLAYLIST(
        listOf(
            DetailAction.DOWNLOAD, DetailAction.ADD_TRACKS, DetailAction.SAVE, DetailAction.PIN, DetailAction.SORT,
            DetailAction.REORDER, DetailAction.EDIT, DetailAction.RADIO, DetailAction.SHARE, DetailAction.EXPORT, DetailAction.DELETE,
        ),
        setOf(DetailAction.DOWNLOAD, DetailAction.ADD_TRACKS, DetailAction.SAVE, DetailAction.PIN),
    ),
}

/** [shown] buttons sit in the bar, in this order; the others are in the ⋮ menu. */
data class ActionSlot(val action: DetailAction, val shown: Boolean)

object DetailActions {
    fun defaults(page: DetailPage): List<ActionSlot> = page.actions.map { ActionSlot(it, it in page.defaultShown) }

    fun decode(page: DetailPage, encoded: String): List<ActionSlot> {
        if (encoded.isBlank()) return defaults(page)
        val read = try {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val action = DetailAction.entries.firstOrNull { it.key == entry.optString("action") }?.takeIf { it in page.actions }
                    ?: return@mapNotNull null
                ActionSlot(action, entry.optBoolean("shown", action in page.defaultShown))
            }.distinctBy { it.action }
        } catch (_: org.json.JSONException) { return defaults(page) }
        return read + defaults(page).filter { default -> read.none { it.action == default.action } }
    }

    fun encode(slots: List<ActionSlot>): String = JSONArray().apply {
        slots.forEach { put(JSONObject().put("action", it.action.key).put("shown", it.shown)) }
    }.toString()
}
