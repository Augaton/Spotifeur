package fr.spotifeur.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONException
import org.json.JSONObject

/**
 * The native RadioFormatListService maps a seed to a generated playlist before reading its tracks.
 *
 * Spotify 9.2.0.26 evidence:
 * - classes2.dex, p.en80.a: GET inspiredby-mix/v2/seed_to_playlist/{seed}?response-format=json
 * - classes9.dex, p.s241.a (DEX 0x610240): invokes that interface
 * - p.xoz0 serializes RadioFormatListSourceModel with `total` and `mediaItems`
 * - p.nkx0.apply reads mediaItems[0]["uri"] from that model
 *
 * The same native route is implemented by librespot-core's get_radio_for_track. The generated
 * playlist is read through Playlist4 and Extended Metadata, with the existing account session.
 */
internal class SpotifyRadio(
    private val transport: SpotifyTransport,
    private val session: SpotifySession,
) {
    private val catalog = SpotifyCatalog(transport, session)

    suspend fun station(seed: Music): CatalogDetail =
        if (seed.kind == MusicKind.Album) albumStation(seed) else seedStation(seed)

    /**
     * For an album seed the service answers with the radio of its artist (same generated playlist,
     * verified on Plastic Beach / Gorillaz). Tracks have their own radio: an album radio therefore
     * interleaves the radios of tracks spread across the album.
     */
    private suspend fun albumStation(album: Music): CatalogDetail = coroutineScope {
        val seeds = spreadAlbumSeeds(catalog.detail(album).tracks)
        if (seeds.isEmpty()) throw SpotifyFailure("This album has no tracks available for a radio.")
        val stations = seeds.map { track ->
            async {
                try { seedStation(track) }
                catch (_: SpotifyFailure) { null }
            }
        }.awaitAll().filterNotNull()
        val first = stations.firstOrNull() ?: throw SpotifyFailure("Spotify did not offer a radio for this album.")
        val tracks = interleaveStations(stations.map(CatalogDetail::tracks))
        CatalogDetail(
            item = first.item.copy(
                title = fr.spotifeur.utils.formatRadioTitle(album.title), subtitle = album.subtitle,
                artwork = album.artwork, imageUrl = album.imageUrl, kind = MusicKind.Radio,
            ),
            tracks = tracks,
            total = tracks.size,
        )
    }

    private suspend fun seedStation(seed: Music): CatalogDetail {
        val uri = canonicalMusicUri(seed.id)
            ?: throw SpotifyFailure("This item cannot start a Spotify radio.")
        val url = spotifyUrl("inspiredby-mix/v2/seed_to_playlist").newBuilder()
            .addPathSegment(uri)
            .addQueryParameter("response-format", "json")
            .build()
        val bytes = authenticatedSpotifyRequest(
            transport, session, url, "Spotify radio", contentType = "application/json",
        )
        val playlistUri = decodeRadioPlaylist(bytes)
        val playlist = Music(
            id = playlistUri,
            title = fr.spotifeur.utils.formatRadioTitle(seed.title),
            subtitle = seed.subtitle,
            kind = MusicKind.Radio,
            artwork = seed.artwork,
            imageUrl = seed.imageUrl,
        )
        val detail = catalog.detail(playlist)
        if (detail.tracks.isEmpty()) throw SpotifyFailure("This Spotify radio has no available tracks.")
        return detail.copy(item = detail.item.copy(kind = MusicKind.Radio))
    }
}

private const val ALBUM_RADIO_SEEDS = 5
private const val ALBUM_RADIO_SIZE = 100

/** Tracks evenly spread across the album, so that the radio does not only reflect its opening. */
internal fun spreadAlbumSeeds(tracks: List<Music>, count: Int = ALBUM_RADIO_SEEDS): List<Music> {
    if (tracks.size <= count) return tracks
    return List(count) { index -> tracks[index * tracks.size / count] }
}

/** Takes one track from each radio in turn, without repeating a track. */
internal fun interleaveStations(stations: List<List<Music>>, limit: Int = ALBUM_RADIO_SIZE): List<Music> {
    val merged = LinkedHashMap<String, Music>()
    var depth = 0
    while (merged.size < limit && stations.any { depth < it.size }) {
        for (station in stations) {
            if (merged.size >= limit) break
            station.getOrNull(depth)?.let { merged.putIfAbsent(it.id, it) }
        }
        depth++
    }
    return merged.values.toList()
}

/** Follow the original adapter's first media item rather than treating arbitrary JSON as a URI. */
internal fun decodeRadioPlaylist(response: ByteArray): String {
    val value = try {
        JSONObject(response.decodeToString())
            .optJSONArray("mediaItems")
            ?.optJSONObject(0)
            ?.optString("uri")
    } catch (_: JSONException) {
        throw SpotifyFailure("The Spotify radio response is unreadable.")
    }
    return value?.let(::canonicalMusicUri)?.takeIf { it.startsWith("spotify:playlist:") }
        ?: throw SpotifyFailure("Spotify did not offer a radio for this item.")
}
