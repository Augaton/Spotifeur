package fr.spotifeur.playback

import fr.spotifeur.data.Music
import fr.spotifeur.data.decodePins
import fr.spotifeur.data.encodePins
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** The user's download request, separate from shared encrypted track files. */
data class DownloadCollection(val item: Music, val tracks: List<Music>)

data class CollectionProgress(
    val collection: DownloadCollection,
    val completed: Int,
    val total: Int,
    val percent: Int,
    val remainingSeconds: Long? = null,
    val paused: Boolean = false,
    val failed: Boolean = false,
) {
    val isComplete get() = total > 0 && completed == total
    val isActive get() = !isComplete && !failed
}

/** Cancelling the remainder keeps completed songs as singles, unless another request
 * already retains them. A group removal after completion is a separate operation. */
internal fun cancelDownloadCollection(
    collections: Map<String, DownloadCollection>, id: String, completedIds: Set<String>,
): Map<String, DownloadCollection> {
    val removed = collections[id] ?: return collections
    val remaining = collections.filterKeys { it != id }.toMutableMap()
    val kept = remaining.values.flatMap { it.tracks }.mapTo(hashSetOf()) { it.id }
    removed.tracks.filter { it.id in completedIds && it.id !in kept }.distinctBy { it.id }.forEach { track ->
        remaining[track.id] = DownloadCollection(track, listOf(track))
    }
    return remaining
}

internal fun collectionProgress(
    collection: DownloadCollection, downloads: Map<String, DownloadedTrack>,
    failedIds: Set<String> = emptySet(), paused: Boolean = false,
    bytesPerSecond: Double = 0.0,
): CollectionProgress {
    val entries = collection.tracks.map { downloads[it.id] }
    val complete = entries.count { it?.isComplete == true }
    val fraction = entries.sumOf { if (it?.isComplete == true) 1.0 else ((it?.percent ?: 0f) / 100.0).coerceIn(0.0, 0.999) }
    val known = entries.filterNotNull().filter { it.totalBytes > 0 }
    val typicalSize = known.map { it.totalBytes }.average().takeIf { it.isFinite() } ?: 0.0
    val remaining = entries.sumOf { entry ->
        if (entry?.isComplete == true) 0.0 else ((entry?.totalBytes?.takeIf { it > 0 }?.toDouble() ?: typicalSize) - (entry?.bytes ?: 0)).coerceAtLeast(0.0)
    }
    return CollectionProgress(collection, complete, entries.size,
        if (entries.isEmpty()) 0 else (fraction * 100 / entries.size).toInt(),
        if (!paused && bytesPerSecond > 0 && remaining > 0) ceil(remaining / bytesPerSecond).toLong() else null,
        paused, collection.tracks.any { it.id in failedIds || downloads[it.id]?.failed == true })
}

internal fun encodeDownloadCollections(items: Collection<DownloadCollection>): String = JSONArray().also { array ->
    items.forEach { entry -> array.put(JSONObject().put("item", encodePins(listOf(entry.item))).put("tracks", encodePins(entry.tracks))) }
}.toString()

internal fun decodeDownloadCollections(value: String): List<DownloadCollection> = runCatching {
    val array = JSONArray(value)
    (0 until array.length()).mapNotNull { index ->
        val row = array.getJSONObject(index)
        val item = decodePins(row.getString("item")).firstOrNull() ?: return@mapNotNull null
        DownloadCollection(item, decodePins(row.getString("tracks")))
    }
}.getOrDefault(emptyList())
