package fr.spotifeur.data

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.playback.SpotifyPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Explicit checks against the connected account, without printing credentials or signed URLs. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpotifyIntegrationTest {
    @Test
    fun libraryDetailsAndFullPlayback(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyIntegration") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val session = SpotifyAccount.require(app)
        val transport = SpotifyAccount.transport
        val catalog = SpotifyCatalog(transport, session)
        val repository = SpotifyLibrary(transport, session, catalog)
        val library = repository.load()
        Log.i(TAG, "Library: ${library.playlists.size} playlists, ${library.albums.size} albums, ${library.savedTrackCount} saved tracks.")
        val explicitTracks = InstrumentationRegistry.getArguments().getString("spotifyTrackUris") != null
        if (!explicitTracks) assertTrue(library.playlists.isNotEmpty())
        val more = repository.loadMore(library)
        assertEquals(minOf(library.savedTracksLoaded + 100, library.savedTrackUris.size), more.savedTracksLoaded)
        assertTrue(more.savedTracks.size >= library.savedTracks.size)
        if (library.playlists.isNotEmpty()) {
            val playlist = catalog.detail(library.playlists.first())
            assertTrue("The playlist must contain tracks", playlist.tracks.isNotEmpty())
            if (playlist.nextPage != null) assertTrue(catalog.detail(playlist.item, playlist.nextPage).tracks.isNotEmpty())
            Log.i(TAG, "Playlist and pagination verified.")
        }
        val results = SpotifySearch(transport, session).search("Daft Punk", Locale.FRANCE, null)
        val album = catalog.detail(results.items.first { it.kind == MusicKind.Album })
        assertTrue("The album must contain tracks", album.tracks.size > 1)
        val artist = catalog.detail(results.items.first { it.kind == MusicKind.Artist })
        assertTrue("The artist page must offer music", artist.tracks.isNotEmpty() || artist.related.isNotEmpty())
        Log.i(TAG, "Album: ${album.tracks.size} tracks. Artist page verified.")

        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = AtomicReference<SpotifyPlayer>()
        instrumentation.runOnMainSync { holder.set(SpotifyPlayer(app, scope)) }
        val player = holder.get()
        suspend fun signal(label: String) {
            delay(500)
            val before = fr.spotifeur.playback.PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(30_000) {
                while (fr.spotifeur.playback.PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    assertNull(player.state.value.error)
                    delay(100)
                }
            }
            Log.i(TAG, "$label: decoded PCM verified")
        }
        try {
            withContext(Dispatchers.Main) { player.play(album.tracks.take(2), 0) }
            val playing = withTimeout(45_000) { player.state.first { it.error != null || it.playing && it.positionMs > 1_200 } }
            assertNull(playing.error)
            assertTrue("The stream must cover the whole track", playing.durationMs > 60_000)
            assertTrue("The HLS duration must match the metadata", abs(playing.durationMs - album.tracks.first().durationMs) < 5_000)
            Log.i(TAG, "Playback confirmed, full duration=${playing.durationMs} ms.")
            delay(20_000)
            signal("service past clear lead-in")
            val target = playing.durationMs / 2
            withContext(Dispatchers.Main) { player.seek(target) }
            val seek = withTimeout(25_000) { player.state.first { it.error != null || it.playing && it.positionMs >= target - 1_000 } }
            assertNull(seek.error)
            signal("service after seek")
            Log.i(TAG, "Seek confirmed at ${seek.positionMs} ms.")
            withContext(Dispatchers.Main) { player.toggle() }
            withTimeout(5_000) { player.state.first { !it.playing } }
            withContext(Dispatchers.Main) { player.toggle() }
            withTimeout(15_000) { player.state.first { it.playing } }
            withContext(Dispatchers.Main) { player.shuffle(); player.repeat() }
            withTimeout(5_000) { player.state.first { it.shuffle && it.repeat == 1 } }
            withContext(Dispatchers.Main) { player.shuffle(); player.repeat() }
            withTimeout(5_000) { player.state.first { !it.shuffle && it.repeat == 2 } }
            withContext(Dispatchers.Main) { player.repeat() }
            withTimeout(5_000) { player.state.first { it.repeat == 0 && it.canNext } }
            Log.i(TAG, "Pause/resume, shuffle and repeat confirmed.")
            withContext(Dispatchers.Main) { player.next() }
            val next = withTimeout(30_000) { player.state.first { it.error != null || it.index == 1 && it.playing && it.positionMs > 500 } }
            assertNull(next.error)
            delay(20_000)
            signal("service next track past clear lead-in")
            val before = next.positionMs
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            delay(1_500)
            assertTrue("Playback must continue in the background", player.state.value.playing && player.state.value.positionMs > before + 500)
            Log.i(TAG, "Full playback, Android license, seek, pause/resume, queue, shuffle/repeat and background verified.")
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.close() }
            scope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun radioContainsMusic(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyIntegration") == "true")
        val session = SpotifyAccount.require(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = SpotifyAccount.transport
        val track = SpotifySearch(transport, session).search("Daft Punk", Locale.FRANCE, null).items.first { it.kind == MusicKind.Track }
        val station = SpotifyRadio(transport, session).station(track)
        assertTrue(station.tracks.isNotEmpty())
        assertTrue(station.tracks.all { it.kind == MusicKind.Track })
        Log.i(TAG, "Linked radio verified: ${station.tracks.size} tracks.")
    }
    private companion object { const val TAG = "SpotifeurIntegration" }
}
