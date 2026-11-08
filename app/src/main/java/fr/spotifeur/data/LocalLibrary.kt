package fr.spotifeur.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val LOCAL_TRACK_PREFIX = "local:track:"
const val LOCAL_PLAYLIST_PREFIX = "local:playlist:"

val Music.isLocalTrack: Boolean get() = id.startsWith(LOCAL_TRACK_PREFIX)
val Music.isLocalPlaylist: Boolean get() = id.startsWith(LOCAL_PLAYLIST_PREFIX)

/** "FLAC · 44.1 kHz · 1012 kbps", leaving out whatever is unknown. */
fun audioQualityLabel(mimeType: String?, sampleRateHz: Int?, bitrateBps: Int?): String = listOfNotNull(
    mimeType?.substringAfter('/')?.substringBefore(';')?.takeIf { it.isNotBlank() }?.let(::audioFormatName),
    sampleRateHz?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f kHz", it / 1000.0) },
    bitrateBps?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
).joinToString(" · ")

private fun audioFormatName(subtype: String): String = when (subtype.lowercase()) {
    "mpeg", "mp3" -> "MP3"
    "mp4a-latm", "aac", "mp4" -> "AAC"
    "x-flac", "flac" -> "FLAC"
    "ogg", "vorbis" -> "OGG"
    "opus" -> "OPUS"
    "x-wav", "wav", "raw" -> "WAV"
    else -> subtype.uppercase()
}

/** Reads the device's music from MediaStore. Nothing is copied. */
class LocalLibrary(context: Context) {
    private val app = context.applicationContext

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(app, PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun load(): List<Music> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM); add(MediaStore.Audio.Media.ALBUM_ID); add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.BITRATE)
        }.toTypedArray()
        val out = ArrayList<Music>()
        app.contentResolver.query(
            collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val bitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                out += Music(
                    id = "$LOCAL_TRACK_PREFIX$id",
                    title = cursor.getString(1).orEmpty().ifBlank { "#$id" },
                    subtitle = cursor.getString(2).orEmpty().takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    kind = MusicKind.Track,
                    imageUrl = ContentUris.withAppendedId(ALBUM_ART, cursor.getLong(4)).toString(),
                    durationMs = cursor.getInt(5),
                    album = cursor.getString(3).orEmpty(),
                    mediaUri = ContentUris.withAppendedId(collection, id).toString(),
                    quality = audioQualityLabel(cursor.getString(6), null, if (bitrate >= 0) cursor.getInt(bitrate) else null),
                )
            }
        }
        out
    }

    companion object {
        private val ALBUM_ART = "content://media/external/audio/albumart".toUri()
        val PERMISSION: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

data class LocalPlaylist(val id: String, val name: String, val tracks: List<Music>) {
    fun asMusic(): Music = Music(
        id = id, title = name, kind = MusicKind.Playlist,
        subtitle = fr.spotifeur.utils.formatSongCount(tracks.size),
        imageUrl = playlistMosaic(tracks) ?: tracks.firstNotNullOfOrNull(Music::imageUrl), tracks = tracks.map(Music::id),
    )
}

/** Playlists that exist only on this device. They can mix Spotify and local tracks. */
class LocalPlaylistStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("local_playlists", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(decodeLocalPlaylists(preferences.getString(KEY, null)))
    val playlists: StateFlow<List<LocalPlaylist>> = mutable.asStateFlow()

    fun get(id: String): LocalPlaylist? = mutable.value.firstOrNull { it.id == id }
    fun create(name: String, tracks: List<Music> = emptyList()): LocalPlaylist {
        val playlist = LocalPlaylist(LOCAL_PLAYLIST_PREFIX + UUID.randomUUID(), name.trim(), tracks)
        write(listOf(playlist) + mutable.value)
        return playlist
    }
    fun rename(id: String, name: String) = change(id) { it.copy(name = name.trim()) }
    fun delete(id: String) = write(mutable.value.filterNot { it.id == id })
    fun add(id: String, tracks: List<Music>) = change(id) { it.copy(tracks = it.tracks + tracks) }
    fun removeAt(id: String, index: Int) = change(id) { playlist ->
        playlist.copy(tracks = playlist.tracks.filterIndexed { position, _ -> position != index })
    }
    fun move(id: String, from: Int, to: Int) = change(id) { playlist ->
        if (from !in playlist.tracks.indices || to !in playlist.tracks.indices) playlist
        else playlist.copy(tracks = playlist.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }
    fun replaceAll(items: List<LocalPlaylist>) = write(items)

    private fun change(id: String, update: (LocalPlaylist) -> LocalPlaylist) =
        write(mutable.value.map { if (it.id == id) update(it) else it })
    private fun write(items: List<LocalPlaylist>) {
        mutable.value = items
        preferences.edit { putString(KEY, encodeLocalPlaylists(items)) }
    }
    private companion object { const val KEY = "items" }
}

internal fun encodeLocalPlaylists(items: List<LocalPlaylist>): String = JSONArray().apply {
    items.forEach { playlist ->
        put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("tracks", JSONArray(encodePins(playlist.tracks))))
    }
}.toString()

internal fun decodeLocalPlaylists(encoded: String?): List<LocalPlaylist> {
    if (encoded.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(encoded)
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            LocalPlaylist(entry.getString("id"), entry.getString("name"), decodePins(entry.getJSONArray("tracks").toString()))
        }.filter { it.id.startsWith(LOCAL_PLAYLIST_PREFIX) }
    } catch (_: org.json.JSONException) { emptyList() }
}
