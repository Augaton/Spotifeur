package fr.spotifeur.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.util.UUID

data class HomeLibrary(
    val playlists: List<Music>,
    val albums: List<Music>,
    val artists: List<Music>,
    val savedTracks: List<Music>,
    val savedTrackCount: Int,
    val warning: String? = null,
    val savedTrackUris: List<String> = emptyList(),
    /** Number of raw collection URIs consumed, including unavailable metadata entries. */
    val savedTracksLoaded: Int = 0,
)

/** Reads the user's Playlist4 rootlist and collection-v2 sets. No Web API scopes are involved. */
internal class SpotifyLibrary(
    private val transport: SpotifyTransport,
    private val session: SpotifySession,
    private val catalog: SpotifyCatalog = SpotifyCatalog(transport, session),
) {
    suspend fun loadMore(content: HomeLibrary): HomeLibrary {
        val offset = content.savedTracksLoaded.coerceIn(0, content.savedTrackUris.size)
        val uris = content.savedTrackUris.drop(offset).take(100)
        if (uris.isEmpty()) return content.copy(savedTracksLoaded = offset)
        val tracks = catalog.hydrate(uris)
        return mergeSavedTracksPage(content, offset, uris.size, tracks)
    }

    suspend fun setSaved(item: Music, saved: Boolean) {
        val uri = canonicalMusicUri(item.id) ?: throw SpotifyFailure("Invalid Spotify ID.")
        val set = when (item.kind) {
            MusicKind.Track, MusicKind.Album -> "collection"
            MusicKind.Artist -> "artist"
            else -> throw SpotifyFailure("This item cannot be changed in your favorites.")
        }
        val username = session.username()
        val body = proto {
            writeString(1, username)
            writeString(2, set)
            writeByteArray(3, proto {
                writeString(1, uri)
                writeInt64(2, if (saved) System.currentTimeMillis() / 1000 else 0)
                writeBool(3, !saved)
            })
            writeString(4, UUID.randomUUID().toString())
        }
        authenticatedSpotifyRequest(
            transport, session, spotifyUrl("collection/v2/write"), "Spotify favorites", body,
            "application/vnd.collection-v2.spotify.proto",
        )
    }

    /** Whatever could not be read keeps its [previous] value instead of emptying the library. */
    suspend fun load(previous: HomeLibrary? = null, onUpdate: (HomeLibrary) -> Unit = {}): HomeLibrary = coroutineScope {
        val username = session.username()
        val playlists = async { component { rootlist(username) } }
        val collection = async { component { collection(username, "collection") } }
        val artists = async { component { collection(username, "artist") } }
        val root = playlists.await()
        onUpdate(HomeLibrary(root.value.orEmpty(), emptyList(), emptyList(), emptyList(), 0, root.error))
        val saved = collection.await()
        val followed = artists.await()
        val savedUris = saved.value.orEmpty()
        val trackUris = saved.value?.filter { it.startsWith("spotify:track:") } ?: previous?.savedTrackUris.orEmpty()
        val otherUris = savedUris.filterNot { it.startsWith("spotify:track:") } + followed.value.orEmpty()
        previous?.let { catalog.seed(it.albums + it.artists + it.savedTracks) }
        val hydrated = component { catalog.hydrate(otherUris + trackUris.take(INITIAL_SAVED_TRACKS)) }
        val music = hydrated.value.orEmpty().associateBy(Music::id)
        val known = previous?.savedTracks.orEmpty().associateBy(Music::id)
        val warning = listOfNotNull(root.error, saved.error, followed.error, hydrated.error)
            .distinct().joinToString("\n").takeIf(String::isNotEmpty)
        if (root.value == null && saved.value == null && followed.value == null) {
            throw SpotifyFailure(warning ?: "The Spotify library is unavailable.")
        }
        // The contiguous part already known: a saved list only grows by its head between two reads.
        var loaded = if (hydrated.value != null) minOf(INITIAL_SAVED_TRACKS, trackUris.size) else 0
        while (loaded < trackUris.size && trackUris[loaded] in known) loaded++
        HomeLibrary(
            playlists = root.value ?: previous?.playlists.orEmpty(),
            albums = if (saved.value != null && hydrated.value != null) otherUris.mapNotNull(music::get).filter { it.kind == MusicKind.Album }.distinctBy(Music::id)
                else previous?.albums.orEmpty(),
            artists = if (followed.value != null && hydrated.value != null) otherUris.mapNotNull(music::get).filter { it.kind == MusicKind.Artist }.distinctBy(Music::id)
                else previous?.artists.orEmpty(),
            savedTracks = trackUris.take(loaded).mapNotNull { music[it] ?: known[it] }.distinctBy(Music::id),
            savedTrackCount = trackUris.size,
            warning = warning,
            savedTrackUris = trackUris,
            savedTracksLoaded = loaded,
        )
    }

    private suspend fun rootlist(username: String): List<Music> {
        val items = mutableListOf<Music>()
        var from = 0
        while (true) {
            val url = spotifyUrl("playlist/v2/user").newBuilder().addPathSegment(username)
                .addPathSegment("rootlist")
                .addQueryParameter("decorate", "revision,attributes,length,owner,capabilities,status_code")
                .addQueryParameter("from", from.toString())
                .addQueryParameter("length", "300").build()
            val bytes = authenticatedSpotifyRequest(transport, session, url, "Saved playlists")
            val decoded = decodeRootlistPage(bytes)
            items += decoded.items
            from += decoded.rawCount
            if (!decoded.hasMore || decoded.rawCount == 0) break
        }
        return items.distinctBy(Music::id)
    }

    private suspend fun collection(username: String, set: String): List<String> {
        val found = linkedMapOf<String, Int>()
        var token = ""
        val seen = mutableSetOf<String>()
        while (true) {
            if (!seen.add(token)) throw SpotifyFailure("Spotify library pagination is stuck.")
            val body = proto {
                writeString(1, username)
                writeString(2, set)
                writeString(3, token)
                writeInt32(4, 300)
            }
            val response = authenticatedSpotifyRequest(
                transport, session, spotifyUrl("collection/v2/paging"), "Saved items", body,
                "application/vnd.collection-v2.spotify.proto",
            )
            val page = decodeCollectionPage(response)
            page.removedUris.forEach(found::remove)
            page.items.forEach { (uri, added) -> found[uri] = added }
            token = page.nextPage.orEmpty()
            if (token.isEmpty()) break
        }
        return found.entries.sortedByDescending { it.value }.map { it.key }
    }

    private data class Component<T>(val value: T?, val error: String?)
    private suspend fun <T> component(block: suspend () -> T): Component<T> = try {
        Component(block(), null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (auth: SpotifyAuthRequired) {
        throw auth
    } catch (failure: IOException) {
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("library", "part ${failure.javaClass.simpleName} \"${failure.message}\"")
        Component(null, failure.message ?: "Part of the Spotify library is unavailable.")
    }
}

private const val INITIAL_SAVED_TRACKS = 120

/** A like or unlike the service has not reflected yet, whatever a library read says in the meantime. */
internal data class FavoriteChange(val item: Music, val saved: Boolean, val confirmedAtMs: Long? = null)

internal fun applyFavoriteChanges(content: HomeLibrary, changes: Collection<FavoriteChange>): HomeLibrary =
    changes.fold(content) { library, change ->
        val item = change.item
        when (item.kind) {
            MusicKind.Track -> {
                val present = item.id in library.savedTrackUris
                when {
                    change.saved && !present -> library.copy(
                        savedTracks = listOf(item) + library.savedTracks.filterNot { it.id == item.id },
                        savedTrackUris = listOf(item.id) + library.savedTrackUris,
                        savedTrackCount = library.savedTrackCount + 1,
                        savedTracksLoaded = library.savedTracksLoaded + 1,
                    )
                    !change.saved && present -> library.copy(
                        savedTracks = library.savedTracks.filterNot { it.id == item.id },
                        savedTrackUris = library.savedTrackUris.filterNot { it == item.id },
                        savedTrackCount = (library.savedTrackCount - 1).coerceAtLeast(0),
                        savedTracksLoaded = if (library.savedTrackUris.indexOf(item.id) < library.savedTracksLoaded)
                            (library.savedTracksLoaded - 1).coerceAtLeast(0) else library.savedTracksLoaded,
                    )
                    else -> library
                }
            }
            MusicKind.Album -> library.copy(albums = changedList(library.albums, change))
            MusicKind.Artist -> library.copy(artists = changedList(library.artists, change))
            else -> library
        }
    }

private fun changedList(items: List<Music>, change: FavoriteChange): List<Music> {
    val present = items.any { it.id == change.item.id }
    return when {
        change.saved && !present -> listOf(change.item) + items
        !change.saved && present -> items.filterNot { it.id == change.item.id }
        else -> items
    }
}

internal fun mergeSavedTracksPage(
    content: HomeLibrary,
    offset: Int,
    rawCount: Int,
    tracks: List<Music>,
): HomeLibrary {
    val valid = content.savedTrackUris.toHashSet()
    return content.copy(
        savedTracks = (content.savedTracks + tracks.filter { it.kind == MusicKind.Track && it.id in valid })
            .distinctBy(Music::id),
        savedTracksLoaded = (offset.toLong() + rawCount).coerceIn(0, content.savedTrackUris.size.toLong()).toInt(),
    )
}

internal data class CollectionPage(
    val items: List<Pair<String, Int>>, val nextPage: String?, val removedUris: List<String> = emptyList(),
)

internal fun decodeCollectionPage(bytes: ByteArray): CollectionPage {
    val response = Proto(bytes)
    val records = linkedMapOf<String, Int?>()
    response.messages(1).forEach { item ->
        val uri = item.text(1)?.let(::canonicalMusicUri) ?: return@forEach
        records[uri] = if (item.number(3) == 1) null else item.number(2) ?: 0
    }
    return CollectionPage(
        records.entries.mapNotNull { (uri, added) -> added?.let { uri to it } },
        response.text(2)?.takeIf(String::isNotEmpty),
        records.filterValues { it == null }.keys.toList(),
    )
}

internal data class RootlistPage(val items: List<Music>, val rawCount: Int, val hasMore: Boolean)

internal fun decodeRootlistPage(bytes: ByteArray): RootlistPage {
    val response = Proto(bytes)
    val contents = response.message(5)
    val items = contents?.messages(3).orEmpty()
    val metadata = contents?.messages(4).orEmpty()
    val music = items.mapIndexedNotNull { index, item ->
        val uri = item.text(1)?.let(::canonicalMusicUri) ?: return@mapIndexedNotNull null
        if (!uri.startsWith("spotify:playlist:")) return@mapIndexedNotNull null
        val info = metadata.getOrNull(index)
        val status = info?.number(9)?.let(::unZigZag) ?: 0
        if (status in 400..599) return@mapIndexedNotNull null
        val attrs = info?.message(2)
        val title = attrs?.text(1)?.takeIf(String::isNotBlank) ?: "Playlist"
        Music(
            id = uri, title = title, subtitle = info?.number(3)?.let { fr.spotifeur.utils.formatSongCount(it) } ?: "Playlist", kind = MusicKind.Playlist,
            artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = playlistImage(attrs),
            note = attrs?.text(2).orEmpty(), ownerUsername = info?.text(5),
        )
    }
    val from = contents?.number(1) ?: 0
    val total = response.number(2) ?: items.size
    return RootlistPage(music, items.size, contents?.number(2) == 1 || from + items.size < total)
}
