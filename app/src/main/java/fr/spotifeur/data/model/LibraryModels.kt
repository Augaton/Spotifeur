package fr.spotifeur.data.model

import androidx.compose.runtime.Immutable
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind

// UI models. Every entity carries its Spotify URI: the Long ids the UI uses are a stable hash
// that the registry maps back.

@Immutable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: Long = -1L,
    val artists: List<ArtistRef> = emptyList(),
    val album: String = "",
    val albumId: Long = -1L,
    val albumArtist: String? = null,
    val albumArtUriString: String? = null,
    val duration: Long = 0L,
    val isFavorite: Boolean = false,
    val trackNumber: Int = 0,
    val discNumber: Int? = null,
    val year: Int = 0,
    val dateAdded: Long = 0L,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val mediaUri: String? = null,
    val quality: String = "",
) {
    val isLocal: Boolean get() = id.startsWith("local:track:")
    val contentUriString: String get() = id
    val displayArtist: String
        get() = if (artists.isNotEmpty()) artists.sortedByDescending { it.isPrimary }.joinToString(", ") { it.name } else artist
    val primaryArtist: ArtistRef
        get() = artists.find { it.isPrimary } ?: artists.firstOrNull() ?: ArtistRef(artistId, artist, true)

    companion object {
        fun emptySong() = Song(id = "-1", title = "", artist = "")
    }
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int = 0,
    val dateAdded: Long = 0L,
    val albumArtUriString: String? = null,
    val songCount: Int = 0,
    val albumArtist: String? = null,
    val uri: String = "",
) {
    companion object { fun empty() = Album(id = -1, title = "", artist = "") }
}

@Immutable
data class Artist(
    val id: Long,
    val name: String,
    val songCount: Int = 0,
    val imageUrl: String? = null,
    val customImageUri: String? = null,
    val uri: String = "",
) {
    val effectiveImageUrl: String?
        get() = customImageUri?.takeIf { it.isNotBlank() } ?: imageUrl?.takeIf { it.isNotBlank() }
    companion object { fun empty() = Artist(id = -1, name = "") }
}

@Immutable
data class ArtistRef(val id: Long, val name: String, val isPrimary: Boolean = false)

@Immutable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList(),
    val description: String = "",
    val coverImageUri: String? = null,
    val songCount: Int = 0,
)

/** Stable 64-bit id for a Spotify URI (FNV-1a), used where the UI expects a Long id. */
fun spotifyLongId(uri: String): Long {
    var hash = -3750763034362895579L
    uri.forEach { character -> hash = (hash xor character.code.toLong()) * 1099511628211L }
    return hash and Long.MAX_VALUE
}

fun Music.toSong(favorite: Boolean = false): Song = Song(
    id = id, title = title, artist = subtitle, album = album, albumArtUriString = fr.spotifeur.data.largestSpotifyImage(imageUrl),
    duration = durationMs.toLong(), isFavorite = favorite, mediaUri = mediaUri, quality = quality,
)

fun Music.toAlbum(): Album = Album(
    id = spotifyLongId(id), title = title, artist = subtitle, albumArtUriString = fr.spotifeur.data.largestSpotifyImage(imageUrl),
    year = note.toIntOrNull() ?: 0, songCount = tracks.size, uri = id,
)

fun Music.toArtist(): Artist = Artist(id = spotifyLongId(id), name = title, imageUrl = imageUrl, uri = id)

fun Music.toPlaylist(): Playlist = Playlist(
    id = id, name = title, songIds = tracks, description = subtitle, coverImageUri = imageUrl, songCount = tracks.size,
)

fun Song.toMusic(): Music = Music(
    id = id, title = title, subtitle = displayArtist, kind = MusicKind.Track,
    imageUrl = albumArtUriString, durationMs = duration.toInt(),
    album = album, mediaUri = mediaUri, quality = quality,
)
