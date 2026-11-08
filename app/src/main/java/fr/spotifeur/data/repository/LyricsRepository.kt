package fr.spotifeur.data.repository

import android.content.Context
import fr.spotifeur.data.SpotifyFailure
import fr.spotifeur.data.SpotifyLyricsSource
import fr.spotifeur.data.model.Lyrics
import fr.spotifeur.data.model.Song
import fr.spotifeur.utils.LyricsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A candidate from LRCLIB, in the shape the lyrics picker displays. */
data class LrcLibRecord(
    val id: Int,
    val name: String,
    val artistName: String,
    val albumName: String,
    val duration: Double,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)

data class LyricsSearchResult(val record: LrcLibRecord, val lyrics: Lyrics, val rawLyrics: String)

/**
 * Spotify's own lyrics first when the track comes from Spotify, then LRCLIB.
 * LRCLIB only ever receives a title, an artist, an album and a duration.
 */
class LyricsRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (!fr.spotifeur.data.NetworkMonitor.online.value) throw java.io.IOException("No connection")
            chain.proceed(chain.request())
        }.build()

    private fun cacheFile(songId: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(songId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$name.lrc")
    }

    suspend fun cached(songId: String): Lyrics? = withContext(Dispatchers.IO) {
        cacheFile(songId).takeIf(File::exists)?.readText()?.let(LyricsUtils::parseLyrics)?.takeIf(::hasContent)
    }

    suspend fun store(songId: String, raw: String) = withContext(Dispatchers.IO) { cacheFile(songId).writeText(raw) }
    suspend fun forget(songId: String) = withContext(Dispatchers.IO) { cacheFile(songId).delete(); Unit }
    suspend fun clear() = withContext(Dispatchers.IO) { directory.listFiles()?.forEach(File::delete); Unit }

    suspend fun fromSpotify(song: Song, source: SpotifyLyricsSource?): Lyrics? {
        if (song.isLocal || source == null) return null
        return try { source.fetch(song.id)?.also { store(song.id, it.toStoredText()) } } catch (_: SpotifyFailure) { null }
    }

    /** The exact LRCLIB lookup first, then its text search, keeping results close in duration. */
    suspend fun fromLrcLib(song: Song): Lyrics? {
        val seconds = (song.duration / 1000).toInt()
        val exact = runCatching {
            get("https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", song.title).addQueryParameter("artist_name", song.artist)
                .addQueryParameter("album_name", song.album).addQueryParameter("duration", seconds.toString()).build().toString())
        }.getOrNull()?.let { decodeRecord(JSONObject(it)) }
        val record = exact?.takeIf { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            ?: search(song.title, song.artist).firstOrNull { kotlin.math.abs(it.record.duration - seconds) <= 5 }?.record
            ?: return null
        return (record.syncedLyrics?.takeIf(String::isNotBlank) ?: record.plainLyrics)?.let(LyricsUtils::parseLyrics)?.takeIf(::hasContent)
    }

    suspend fun search(title: String, artist: String?): List<LyricsSearchResult> {
        val builder = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("track_name", title)
        if (!artist.isNullOrBlank()) builder.addQueryParameter("artist_name", artist)
        val body = try { get(builder.build().toString()) } catch (_: IOException) { return emptyList() }
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { index ->
            val record = decodeRecord(array.getJSONObject(index))
            val raw = record.syncedLyrics?.takeIf(String::isNotBlank) ?: record.plainLyrics?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            LyricsSearchResult(record, LyricsUtils.parseLyrics(raw), raw)
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifeur").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty")
        }
    }

    private fun decodeRecord(json: JSONObject) = LrcLibRecord(
        id = json.optInt("id"), name = json.optString("trackName", json.optString("name")),
        artistName = json.optString("artistName"), albumName = json.optString("albumName"),
        duration = json.optDouble("duration", 0.0),
        plainLyrics = json.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" },
        syncedLyrics = json.optString("syncedLyrics").takeUnless { it.isBlank() || it == "null" },
    )

    private fun hasContent(lyrics: Lyrics) = !lyrics.synced.isNullOrEmpty() || !lyrics.plain.isNullOrEmpty()
}

private fun Lyrics.toStoredText(): String = synced?.joinToString("\n") { line ->
    val minutes = line.time / 60_000
    val seconds = line.time / 1000 % 60
    val hundredths = line.time % 1000 / 10
    String.format(java.util.Locale.ROOT, "[%02d:%02d.%02d]%s", minutes, seconds, hundredths, line.line)
} ?: plain.orEmpty().joinToString("\n")
