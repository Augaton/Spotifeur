package fr.spotifeur.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import java.util.UUID

internal class SpotifySearch(private val transport: SpotifyTransport, private val session: SpotifySession) {
    suspend fun search(query: String, locale: Locale, pageToken: String?): CatalogPage {
        val url = "https://spclient.wg.spotify.com/searchview/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("locale", locale.toLanguageTag())
            .addQueryParameter("entity_types", "album,artist,playlist,track")
            .addQueryParameter("limit", "20")
            .addQueryParameter("page_token", pageToken.orEmpty())
            .addQueryParameter("album_states", "live,prerelease")
            .addQueryParameter("request_id", UUID.randomUUID().toString())
            .addQueryParameter("timestamp", System.currentTimeMillis().toString())
            .build()
        suspend fun request(headers: SpotifySession.Headers): ByteArray {
            val request = Request.Builder().url(url)
                .header("Accept", "application/protobuf")
                .header("Authorization", "Bearer ${headers.user}")
                .header("client-token", headers.client)
            return transport.send(request.build(), "Spotify catalog")
        }
        val headers = session.headers(url.host)
        val response = try {
            request(headers)
        } catch (failure: SpotifyFailure) {
            if (failure.status != 401) throw failure
            request(session.headers(url.host, rejected = headers))
        }
        return decodeSearch(response)
    }
}

internal data class CatalogPage(val items: List<Music>, val nextToken: String?)

internal fun decodeSearch(response: ByteArray): CatalogPage {
    // MainViewResponse.hits contains Entity messages with a projection per URI type.
    val message = Proto(response)
    val items = message.messages(1).mapNotNull { entity ->
        val uri = entity.text(1) ?: return@mapNotNull null
        val title = entity.text(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val kind = when {
            uri.startsWith("spotify:artist:") -> MusicKind.Artist
            uri.startsWith("spotify:track:") -> MusicKind.Track
            uri.startsWith("spotify:album:") -> MusicKind.Album
            uri.startsWith("spotify:playlist:") -> MusicKind.Playlist
            else -> return@mapNotNull null
        }
        val track = if (kind == MusicKind.Track) entity.message(5) else null
        // Track.type 3 and 4 represent video entries, including ones mapped to an audio track.
        if (track?.number(14) in 3..4) return@mapNotNull null
        val subtitle = when (kind) {
            MusicKind.Track -> track?.messages(4)?.mapNotNull { it.text(2) }?.joinToString(", ")
            MusicKind.Album -> entity.message(6)?.texts(1)?.joinToString(", ")
            MusicKind.Playlist -> entity.message(7)?.text(5)
            else -> null
        }
        Music(
            id = uri,
            title = title,
            subtitle = subtitle.orEmpty(),
            kind = kind,
            artwork = Math.floorMod(uri.hashCode(), 6),
            imageUrl = largestSpotifyImage(coverUrl(entity.text(3))),
            durationMs = ((track?.message(18)?.number(1) ?: 0).coerceIn(0, 2_000_000) * 1000),
        )
    }.distinctBy(Music::id)
    return CatalogPage(items, message.text(4)?.takeIf(String::isNotBlank))
}

private val spotifyImage = Regex("spotify:image:([a-fA-F0-9]{40})")

private fun coverUrl(value: String?): String? {
    val image = value?.let(spotifyImage::matchEntire)?.groupValues?.get(1)
    if (image != null) return "https://i.scdn.co/image/$image"
    val url = value?.let { runCatching { it.toHttpUrl() }.getOrNull() } ?: return null
    return url.takeIf {
        it.isHttps && it.host in setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com") &&
            it.username.isEmpty() && it.password.isEmpty() && it.port == 443
    }?.toString()
}
