package fr.spotifeur.data

import fr.spotifeur.data.youtube.YouTubeMusic
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** A Spotify playlist id from an open.spotify.com link or a `spotify:playlist:` URI. */
fun spotifyPlaylistId(input: String): String? {
    val text = input.trim()
    canonicalMusicUri(text)?.takeIf { it.startsWith("spotify:playlist:") }?.let { return it.substringAfterLast(':') }
    val url = text.toHttpUrlOrNull() ?: return null
    if (!url.host.endsWith("spotify.com") && url.host != "spotify.link") return null
    val segments = url.pathSegments
    val id = segments.getOrNull(segments.indexOf("playlist") + 1).takeIf { segments.contains("playlist") } ?: return null
    return id.takeIf { canonicalMusicUri("spotify:playlist:$it") != null }
}

/**
 * Reads a public Spotify playlist without an account, from the page Spotify serves for embedding it.
 * That page lists the first 100 tracks; with an account, the whole playlist is read natively.
 */
internal object SpotifyPublicPlaylist {
    data class Content(val name: String, val imageUrl: String?, val tracks: List<Music>)

    fun read(id: String): Content {
        val request = Request.Builder().url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        val html = YouTubeMusic.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Spotify answered ${response.code}.")
            response.body?.string().orEmpty()
        }
        return parse(html) ?: throw IOException("This Spotify playlist could not be read.")
    }

    internal fun parse(html: String): Content? {
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val entity = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val list = entity.optJSONArray("trackList") ?: return null
        val tracks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { row ->
            val uri = row.optString("uri")
            spotifyLocalTrack(uri) ?: canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:track:") }?.let { id ->
                Music(id = id, title = row.optString("title"), subtitle = row.optString("subtitle"), kind = MusicKind.Track,
                    durationMs = row.optInt("duration"))
            }
        }
        val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
        val image = sources?.optJSONObject(0)?.optString("url")?.takeIf(String::isNotBlank)
        return Content(entity.optString("name").ifBlank { entity.optString("title") }, image, tracks)
    }
}
