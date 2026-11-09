package fr.spotifeur.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One play: a track and when it started. [fromSpotify] marks plays reported by Spotify for its other apps and devices. */
data class HistoryEntry(val track: Music, val playedAt: Long, val fromSpotify: Boolean = false)

/** What was played in Spotifeur, whatever the source, most recent first. Stored on the device only. */
class ListeningHistoryStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "listening-history.json"))
    private val mutable = MutableStateFlow(read())
    val entries: StateFlow<List<HistoryEntry>> = mutable.asStateFlow()

    @Synchronized fun record(track: Music, at: Long = System.currentTimeMillis()) {
        val stored = track.copy(mediaUri = if (track.source == MusicSource.Local) track.mediaUri else null)
        mutable.value = (listOf(HistoryEntry(stored, at)) + mutable.value).take(LIMIT)
        write(mutable.value)
    }

    @Synchronized fun clear() { mutable.value = emptyList(); write(emptyList()) }

    private fun read(): List<HistoryEntry> = runCatching {
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            decodePins(JSONArray().put(entry).toString()).firstOrNull()?.let { HistoryEntry(it, entry.optLong("playedAt")) }
        }
    }.getOrDefault(emptyList())

    private fun write(entries: List<HistoryEntry>) {
        val array = JSONArray(encodePins(entries.map(HistoryEntry::track)))
        entries.forEachIndexed { index, entry -> array.getJSONObject(index).put("playedAt", entry.playedAt) }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    private companion object { const val LIMIT = 2000 }
}

/** A context Spotify remembers as played, with the last track played in it. */
internal data class SpotifyRecentPlay(val context: String, val track: String, val playedAt: Long)

/** `recently-played/v3`: the albums, playlists and artists played lately on the account, from every device. */
internal suspend fun spotifyRecentPlays(session: SpotifySession, limit: Int = 50): List<SpotifyRecentPlay> {
    val url = spotifyUrl("recently-played/v3/user/${session.username()}/recently-played").newBuilder()
        .addQueryParameter("format", "json").addQueryParameter("offset", "0").addQueryParameter("limit", limit.toString())
        .addQueryParameter("filter", "default,collection-new-episodes").build()
    return decodeRecentPlays(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Listening history", contentType = "application/json").decodeToString())
}

internal fun decodeRecentPlays(json: String): List<SpotifyRecentPlay> {
    val contexts = JSONObject(json).optJSONArray("playContexts") ?: return emptyList()
    return (0 until contexts.length()).mapNotNull { index ->
        val entry = contexts.optJSONObject(index) ?: return@mapNotNull null
        val track = entry.optString("lastPlayedTrackUri").takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
        SpotifyRecentPlay(entry.optString("uri"), track, entry.optLong("lastPlayedTime"))
    }
}

/**
 * Spotifeur's own plays and Spotify's, newest first. Spotify's entry for a track also played in
 * Spotifeur within [window] ms is dropped, so one listening never appears twice.
 */
internal fun mergeHistory(local: List<HistoryEntry>, spotify: List<HistoryEntry>, window: Long = 15 * 60_000L): List<HistoryEntry> {
    val kept = spotify.filter { remote -> local.none { it.track.id == remote.track.id && kotlin.math.abs(it.playedAt - remote.playedAt) < window } }
    return (local + kept).sortedByDescending(HistoryEntry::playedAt)
}
