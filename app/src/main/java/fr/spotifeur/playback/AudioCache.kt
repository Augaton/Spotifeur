package fr.spotifeur.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import fr.spotifeur.data.AudioIndex
import fr.spotifeur.data.Music
import fr.spotifeur.data.audioFileHex
import fr.spotifeur.data.preferences.SettingsStore
import java.io.File
import java.util.TreeSet

/** A track of the recent audio cache, as the settings list shows it. */
data class CachedAudio(val track: Music, val sizeBytes: Long, val expectedBytes: Long, val lastPlayedMs: Long) {
    val isComplete: Boolean get() = expectedBytes > 0 && sizeBytes >= expectedBytes
}

/**
 * Keeps what was recently listened to exactly as the service delivers it: encrypted segments in the
 * app's private storage, read back through the normal native playback path.
 */
@UnstableApi
object AudioCache {
    /** A file's signature changes while its content never does. */
    val keys = CacheKeyFactory { spec: DataSpec ->
        spec.key ?: audioFileHex(spec.uri.path)?.let { "$AUDIO_KEY$it" } ?: spec.uri.toString()
    }
    private var database: DatabaseProvider? = null
    private var cache: SimpleCache? = null
    private var evictor: WholeTrackEvictor? = null

    @Synchronized internal fun database(context: Context): DatabaseProvider =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    @Synchronized fun cache(context: Context): SimpleCache = cache ?: run {
        val app = context.applicationContext
        val limit = SettingsStore.get(app).settings.value.audioCacheLimitMb * BYTES_PER_MB
        val created = WholeTrackEvictor(limit).also { evictor = it }
        SimpleCache(File(app.noBackupFilesDir, "recent-audio"), created, database(app)).also { cache = it }
    }

    fun setLimit(context: Context, megabytes: Int) {
        val current = cache(context)
        evictor?.limit = megabytes * BYTES_PER_MB
        synchronized(current) { evictor?.trim(current) }
    }

    fun usedBytes(context: Context): Long = cache(context).cacheSpace

    internal fun key(hex: String) = "$AUDIO_KEY$hex"
    internal fun isAudioKey(key: String) = key.startsWith(AUDIO_KEY)

    /** Bytes held for a file, in this cache and among downloads. */
    private fun cachedBytes(context: Context, hex: String, expected: Long): Long {
        val length = if (expected > 0) expected else Long.MAX_VALUE
        val recent = cache(context).getCachedBytes(key(hex), 0, length)
        val kept = DownloadCenter.cache(context).getCachedBytes(key(hex), 0, length)
        return maxOf(recent.coerceAtLeast(0), kept.coerceAtLeast(0))
    }

    internal fun cachedBytesOfFile(context: Context, hex: String): Long = cachedBytes(context, hex, 0)

    /** Bytes of a file among downloads only. */
    internal fun downloadedBytesOfFile(context: Context, hex: String): Long =
        DownloadCenter.cache(context).getCachedBytes(key(hex), 0, Long.MAX_VALUE).coerceAtLeast(0)

    /** True when the whole file can be read back without the service. */
    fun isAvailableOffline(context: Context, trackId: String): Boolean {
        val file = AudioIndex.get(context).record(trackId)?.file ?: return false
        return isFileComplete(context, file.hex, file.expectedBytes)
    }

    /** Check contiguous coverage, not just an aggregate byte count that might hide a hole. */
    internal fun isFileComplete(context: Context, hex: String, expected: Long): Boolean = expected > 0 &&
        (cache(context).isCached(key(hex), 0, expected) || DownloadCenter.cache(context).isCached(key(hex), 0, expected))

    /** Every track with audio in this cache, most recently listened to first. */
    fun entries(context: Context): List<CachedAudio> {
        val current = cache(context)
        return AudioIndex.get(context).all().mapNotNull { record ->
            val file = record.file ?: return@mapNotNull null
            val size = current.getCachedBytes(key(file.hex), 0, if (file.expectedBytes > 0) file.expectedBytes else Long.MAX_VALUE)
            if (size <= 0) return@mapNotNull null
            CachedAudio(record.track, size, file.expectedBytes, record.lastPlayedMs)
        }.sortedByDescending(CachedAudio::lastPlayedMs)
    }

    fun remove(context: Context, trackIds: Collection<String>) {
        val index = AudioIndex.get(context)
        trackIds.forEach { id ->
            index.record(id)?.file?.let { cache(context).removeResource(key(it.hex)) }
            index.forget(id)
        }
        index.flush()
    }

    fun clear(context: Context) = remove(context, entries(context).map { it.track.id })

    private const val AUDIO_KEY = "audio:"
    private const val BYTES_PER_MB = 1024L * 1024
}

/**
 * Least recently used first, one whole track at a time: half a track is of no use without network,
 * while the size setting can be changed at any moment.
 */
@UnstableApi
private class WholeTrackEvictor(@Volatile var limit: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { first, second ->
        val byTime = first.lastTouchTimestamp.compareTo(second.lastTouchTimestamp)
        if (byTime != 0) byTime else first.compareTo(second)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length, key)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0, span.key)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }
    fun trim(cache: Cache) = evict(cache, 0, null)
    private fun evict(cache: Cache, required: Long, writing: String?) {
        while (size + required > limit) {
            // The track being written would otherwise evict itself, however long it is.
            val oldest = spans.firstOrNull { it.key != writing } ?: return
            cache.getCachedSpans(oldest.key).toList().forEach(cache::removeSpan)
            if (oldest in spans) spans.remove(oldest)
        }
    }
}
