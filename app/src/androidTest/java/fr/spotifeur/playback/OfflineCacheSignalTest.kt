package fr.spotifeur.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The real playback service, audio muted.
 * `spotifyOfflinePhase=fill` fills the cache and readies the keys while online, then cuts the network
 * in this same process: signal past the clear lead, after a seek and across a track change, with every
 * read served from the device.
 * `spotifyOfflinePhase=restart`, with a build made with -PspotifeurNoInternet=true, proves fresh
 * encrypted PCM after a process restart with no socket permission, seeks and a track transition.
 * `spotifyOfflinePhase=downloads` exercises explicit downloads before cutting app/native HTTP.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OfflineCacheSignalTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracksFile get() = File(app.noBackupFilesDir, "offline-test-tracks.json")

    private suspend fun testTracks(session: SpotifySession): List<Music> {
        val catalog = SpotifyCatalog(SpotifyAccount.transport, session)
        val requested = InstrumentationRegistry.getArguments().getString("spotifyTrackUris")
            ?.split(',')?.filter(String::isNotBlank)
        InstrumentationRegistry.getArguments().getString("spotifyAccountType")?.let { expected ->
            assertEquals(expected, spotifyAttribute(SpotifyAccount.transport, session, "type"))
        }
        return (if (requested != null) catalog.hydrate(requested)
            else SpotifyLibrary(SpotifyAccount.transport, session, catalog).load().savedTracks)
            .filter { it.durationMs in 90_000..240_000 }.take(2)
    }

    private suspend fun controller(): MediaController = withContext(Dispatchers.Main) {
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, SpotifeurPlaybackService::class.java))).buildAsync()
        val main = Executor { ContextCompat.getMainExecutor(app).execute(it) }
        suspendCoroutine { continuation -> future.addListener({ continuation.resume(future.get()) }, main) }
    }

    private fun item(track: Music) = MediaItem.Builder().setMediaId(track.id).setUri(track.id).setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.subtitle)
            .setArtworkUri(track.imageUrl?.let(Uri::parse)).setExtras(Bundle().apply { putInt("duration", track.durationMs) }).build()).build()

    private suspend fun signal(player: MediaController, label: String) {
        // Let a seek/transition flush the previous decoder before counting fresh output.
        delay(500)
        val before = PlaybackDiagnostics.nonSilentBuffers.get()
        val started = android.os.SystemClock.elapsedRealtime()
        withTimeout(40_000) {
            while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                val failure = withContext(Dispatchers.Main) { player.playerError }
                check(failure == null) { "$label: ${failure?.errorCodeName} ${PlaybackDiagnostics.lastError}" }
                delay(100)
            }
        }
        val at = withContext(Dispatchers.Main) { player.currentPosition }
        Log.i(TAG, "$label: PCM after ${android.os.SystemClock.elapsedRealtime() - started} ms at ${at / 1000} s encrypted=${PlaybackDiagnostics.encryptedSamples.get()}")
    }

    @Test fun fill(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyOfflinePhase") == "fill")
        val session = SpotifyAccount.require(app)
        val tracks = testTracks(session)
        assertEquals(2, tracks.size)
        tracksFile.writeText(encodePins(tracks))
        AudioCache.remove(app, tracks.map(Music::id))
        val player = controller()
        withContext(Dispatchers.Main) {
            player.volume = 0f
            player.setMediaItems(tracks.map(::item), 0, 0L); player.prepare(); player.play()
        }
        signal(player, "online start")
        withTimeout(240_000) {
            while (!tracks.all { AudioCache.isAvailableOffline(app, it.id) }) delay(500)
        }
        AudioCache.entries(app).filter { entry -> tracks.any { it.id == entry.track.id } }.forEach {
            Log.i(TAG, "cached ${it.sizeBytes}/${it.expectedBytes} complete=${it.isComplete}")
        }
        val provider = checkNotNull(SpotifeurPlaybackService.currentNativeAudio)
        val files = tracks.map { checkNotNull(AudioIndex.get(app).record(it.id)?.file?.hex) }
        files.forEach(provider::warm)
        withTimeout(20_000) { while (!files.all(provider::isReady)) delay(100) }
        NetworkMonitor.setOfflineForTest(true)
        provider.setOfflineForTest(true)
        try {
            withContext(Dispatchers.Main) { player.seekTo(0, 0L); player.play() }
            signal(player, "cut off, start")
            delay(15_000)
            signal(player, "cut off, beyond the clear lead")
            withContext(Dispatchers.Main) { player.seekTo(tracks[0].durationMs / 2L) }
            signal(player, "cut off, after seek")
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            signal(player, "cut off, next track")
            assertEquals(1, withContext(Dispatchers.Main) { player.currentMediaItemIndex })
            delay(15_000)
            signal(player, "cut off, next track, later")
            withContext(Dispatchers.Main) { player.seekTo(tracks[1].durationMs * 3L / 4L) }
            signal(player, "cut off, next track, after seek")
        } finally {
            NetworkMonitor.setOfflineForTest(false)
            provider.setOfflineForTest(false)
            withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems(); player.release() }
        }
    }

    @Test fun downloads(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyOfflinePhase") == "downloads")
        val session = SpotifyAccount.require(app)
        val tracks = testTracks(session)
        assertEquals(2, tracks.size)
        tracksFile.writeText(encodePins(tracks))
        AudioCache.remove(app, tracks.map(Music::id))
        val failed = java.util.concurrent.atomic.AtomicBoolean()
        withContext(Dispatchers.Main) {
            DownloadCenter.start(app)
            tracks.filterNot { DownloadCenter.downloads.value[it.id]?.isComplete == true }.forEach { track ->
                DownloadCenter.add(app, track) { failed.set(true) }
            }
        }
        withTimeout(180_000) {
            while (!tracks.all { DownloadCenter.downloads.value[it.id]?.isComplete == true }) {
                assertFalse("Download preparation failed", failed.get())
                delay(250)
            }
        }
        tracks.forEach { assertTrue(AudioCache.isAvailableOffline(app, it.id)) }
        Log.i(TAG, "two explicit downloads complete")
        val player = controller()
        withContext(Dispatchers.Main) {
            player.volume = 0f
            player.setMediaItems(tracks.map(::item), 0, 20_000L); player.prepare(); player.play()
        }
        signal(player, "explicit downloads, encrypted samples")
        val provider = checkNotNull(SpotifeurPlaybackService.currentNativeAudio)
        NetworkMonitor.setOfflineForTest(true); provider.setOfflineForTest(true)
        try {
            withContext(Dispatchers.Main) { player.seekTo(tracks[0].durationMs / 2L) }
            signal(player, "explicit downloads, offline seek")
            withContext(Dispatchers.Main) { player.seekTo(1, 20_000L) }
            signal(player, "explicit downloads, offline next track")
        } finally {
            NetworkMonitor.setOfflineForTest(false); provider.setOfflineForTest(false)
            withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems(); player.release() }
        }
    }

    @Test fun restart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyOfflinePhase") == "restart")
        val soft = InstrumentationRegistry.getArguments().getString("spotifyOfflineSoft") == "true"
        if (soft) NetworkMonitor.setOfflineForTest(true)
        else {
            val sockets = withContext(Dispatchers.IO) { runCatching { java.net.Socket("1.1.1.1", 443).close() } }
            assertTrue("This build must not be able to open a socket", sockets.isFailure)
        }
        Log.i(TAG, "stored envelopes=" + File(app.noBackupFilesDir, "native-keys").walkTopDown().count { it.isFile })
        if (InstrumentationRegistry.getArguments().getString("spotifyOfflineExpired") == "true") {
            // Only in-memory fixtures change; the saved credentials and tokens remain intact.
            val session = SpotifyAccount.require(app)
            val grantField = SpotifySession::class.java.getDeclaredField("userGrant").apply { isAccessible = true }
            val grant = checkNotNull(grantField.get(session) as? LoginGrant)
            grantField.set(session, LoginGrant("offline-bootstrap-fixture", android.os.SystemClock.elapsedRealtime() - 3_600_000, grant.credential))
            val client = SpotifySession::class.java.getDeclaredField("clientGrant").apply { isAccessible = true }.get(session)
            if (client != null) client.javaClass.getDeclaredField("validUntil").apply { isAccessible = true }
                .setLong(client, android.os.SystemClock.elapsedRealtime() - 3_600_000)
            Log.i(TAG, "expired deadlines and invalid bootstrap bearer supplied, stored credentials unchanged")
        }
        val tracks = decodePins(tracksFile.readText())
        tracks.forEach { assertTrue(AudioCache.isAvailableOffline(app, it.id)) }
        val player = controller()
        withContext(Dispatchers.Main) {
            player.volume = 0f
            player.setMediaItems(tracks.map(::item), 0, 0L); player.prepare(); player.play()
        }
        try {
            signal(player, "restarted offline, start")
            delay(20_000)
            signal(player, "restarted offline, encrypted samples")
            withContext(Dispatchers.Main) { player.seekTo(tracks[0].durationMs / 2L) }
            signal(player, "restarted offline, seek")
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            signal(player, "restarted offline, next track")
            assertEquals(1, withContext(Dispatchers.Main) { player.currentMediaItemIndex })
            delay(15_000)
            signal(player, "restarted offline, next encrypted samples")
            withContext(Dispatchers.Main) { player.seekTo(tracks[1].durationMs * 3L / 4L) }
            signal(player, "restarted offline, next seek")
            if (InstrumentationRegistry.getArguments().getString("spotifyOfflineReconnect") == "true") {
                check(soft && !fr.spotifeur.BuildConfig.FORCE_OFFLINE)
                NetworkMonitor.setOfflineForTest(false)
                check(NetworkMonitor.online.value)
                checkNotNull(SpotifeurPlaybackService.currentNativeAudio).setOfflineForTest(false)
                val session = SpotifyAccount.require(app)
                val next = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
                    .savedTracks.first { it.durationMs > 90_000 && tracks.none { previous -> previous.id == it.id } }
                withContext(Dispatchers.Main) { player.setMediaItem(item(next), 20_000L); player.prepare(); player.play() }
                signal(player, "returned online, new encrypted track")
                val file = checkNotNull(AudioIndex.get(app).record(next.id)?.file?.hex)
                assertTrue(checkNotNull(SpotifeurPlaybackService.currentNativeAudio).isReady(file))
            }
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems(); player.release() }
            if (soft) NetworkMonitor.setOfflineForTest(false)
        }
    }

    private companion object { const val TAG = "SpotifeurOfflineTest" }
}
