package fr.spotifeur.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Pins stay on this device: they never reach Spotify, and only hold what the UI needs to draw them. */
class PinnedStore(context: Context) {
    private val preferences = context.getSharedPreferences("pinned_items", Context.MODE_PRIVATE)
    private val mutablePins = MutableStateFlow(read())
    val pins: StateFlow<List<Music>> = mutablePins.asStateFlow()

    fun toggle(item: Music) = update { current ->
        if (current.any { it.id == item.id }) current.filterNot { it.id == item.id } else listOf(item) + current
    }

    fun move(from: Int, to: Int) = update { current ->
        if (from !in current.indices || to !in current.indices) current
        else current.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun update(change: (List<Music>) -> List<Music>) {
        val next = change(mutablePins.value)
        mutablePins.value = next
        preferences.edit { putString(KEY, encodePins(next)) }
    }

    /** Replaces every pin, for a restored backup. */
    fun replaceAll(items: List<Music>) = update { items }

    private fun read(): List<Music> = decodePins(preferences.getString(KEY, null))

    private companion object { const val KEY = "items" }
}

internal fun encodePins(items: List<Music>): String = JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().apply {
            put("id", item.id)
            put("title", item.title)
            put("subtitle", item.subtitle)
            put("kind", item.kind.name)
            put("imageUrl", item.imageUrl ?: JSONObject.NULL)
            put("durationMs", item.durationMs)
            if (item.album.isNotEmpty()) put("album", item.album)
            if (item.mediaUri != null) put("mediaUri", item.mediaUri)
            if (item.quality.isNotEmpty()) put("quality", item.quality)
            item.ownerUsername?.let { put("ownerUsername", it) }
            if (item.note.isNotEmpty()) put("note", item.note)
            if (item.genre.isNotEmpty()) put("genre", item.genre)
            if (item.artwork != 0) put("artwork", item.artwork)
            item.audio?.let { put("audio", it) }
            if (item.tracks.isNotEmpty()) put("tracks", JSONArray(item.tracks))
        })
    }
}.toString()

internal fun decodePins(encoded: String?): List<Music> {
    if (encoded.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(encoded)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            val kind = MusicKind.entries.firstOrNull { it.name == entry.optString("kind") } ?: return@mapNotNull null
            Music(
                id = entry.getString("id"), title = entry.getString("title"),
                subtitle = fr.spotifeur.utils.localizeTrackCount(entry.optString("subtitle")), kind = kind,
                imageUrl = if (entry.isNull("imageUrl")) null else entry.getString("imageUrl"),
                durationMs = entry.optInt("durationMs"),
                album = entry.optString("album"),
                mediaUri = if (entry.has("mediaUri")) entry.getString("mediaUri") else null,
                quality = entry.optString("quality"),
                ownerUsername = entry.optString("ownerUsername").takeIf(String::isNotBlank),
                note = entry.optString("note"),
                genre = entry.optString("genre"),
                artwork = entry.optInt("artwork"),
                audio = if (entry.has("audio") && !entry.isNull("audio")) entry.getInt("audio") else null,
                tracks = entry.optJSONArray("tracks")?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty(),
            )
        }
    } catch (_: org.json.JSONException) { emptyList() }
}
