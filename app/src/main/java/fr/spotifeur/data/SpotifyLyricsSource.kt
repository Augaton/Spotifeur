package fr.spotifeur.data

import fr.spotifeur.data.model.Lyrics
import fr.spotifeur.data.model.SyncedLine
import org.json.JSONException
import org.json.JSONObject

/** The service behind the official client's lyrics screen, called with the account's own session. */
class SpotifyLyricsSource internal constructor(
    private val transport: SpotifyTransport,
    private val session: SpotifySession,
) {
    suspend fun fetch(trackUri: String): Lyrics? {
        val id = canonicalMusicUri(trackUri)?.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':') ?: return null
        val url = spotifyUrl("color-lyrics/v2/track/$id").newBuilder()
            .addQueryParameter("format", "json").addQueryParameter("vocalRemoval", "false")
            .addQueryParameter("market", "from_token").build()
        val bytes = try {
            authenticatedSpotifyRequest(transport, session, url, "Spotify lyrics", contentType = "application/json")
        } catch (failure: SpotifyFailure) {
            if (failure.status == 404) return null
            throw failure
        }
        return decodeSpotifyLyrics(bytes.decodeToString())
    }
}

/** Instrumental markers are dropped: an empty line is a pause, not text to display. */
internal fun decodeSpotifyLyrics(body: String): Lyrics? = try {
    val lyrics = JSONObject(body).getJSONObject("lyrics")
    val lines = lyrics.getJSONArray("lines")
    val synced = lyrics.optString("syncType") in setOf("LINE_SYNCED", "SYLLABLE_SYNCED")
    val entries = (0 until lines.length()).map(lines::getJSONObject)
    when {
        entries.isEmpty() -> null
        synced -> Lyrics(
            synced = entries.mapNotNull { line ->
                val time = line.optString("startTimeMs").toIntOrNull() ?: return@mapNotNull null
                SyncedLine(time, line.optString("words").replace("♪", "").trim())
            },
            areFromRemote = true,
        )
        else -> Lyrics(plain = entries.map { it.optString("words").trim() }, areFromRemote = true)
    }
} catch (_: JSONException) { null }
