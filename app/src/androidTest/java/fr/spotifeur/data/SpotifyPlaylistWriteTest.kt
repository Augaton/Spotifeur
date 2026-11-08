package fr.spotifeur.data

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SpotifyPlaylistWriteTest {
    @Test fun lifecycle(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyPlaylistWrite") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val catalog = SpotifyCatalog(SpotifyAccount.transport, session)
        val library = SpotifyLibrary(SpotifyAccount.transport, session, catalog).load()
        val tracks = library.savedTracks.take(2)
        assertEquals(2, tracks.size)
        val service = SpotifyPlaylists(app)
        // Clean remnants of interrupted earlier runs of this explicitly named test.
        library.playlists.filter { it.title in setOf("Spotifeur — temporary test", "Spotifeur — renamed test") }.forEach {
            service.setPublished(it.id, false); service.unfollow(it.id)
        }
        var playlist: Music? = null
        try {
            playlist = service.create("Spotifeur — temporary test", tracks.take(1))
            Log.i("SpotifeurPlaylistTest", "created playlist")
            service.rename(playlist.id, "Spotifeur — renamed test")
            service.add(playlist.id, tracks.drop(1))
            Log.i("SpotifeurPlaylistTest", "renamed and added")
            assertTrue(service.isOwned(playlist.id))
            service.setPublished(playlist.id, true)
            assertTrue(service.published(playlist.id))
            Log.i("SpotifeurPlaylistTest", "published on server")
            service.setPublished(playlist.id, false)
            assertFalse(service.published(playlist.id))
            Log.i("SpotifeurPlaylistTest", "unpublished on server")
            val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(75, 95, 155)) }
            val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            image.recycle()
            service.setCover(playlist.id, bytes)
            Log.i("SpotifeurPlaylistTest", "cover uploaded")
            withTimeout(45_000) {
                while (true) {
                    val remote = SpotifyCatalog(SpotifyAccount.transport, session).detail(playlist)
                    if (remote.item.title == "Spotifeur — renamed test" && remote.tracks.map(Music::id) == tracks.map(Music::id) && remote.item.imageUrl != null) break
                    delay(1500)
                }
            }
            assertTrue(service.recommendations(playlist.id, tracks, emptyList()).isNotEmpty())
            Log.i("SpotifeurPlaylistTest", "remote content and suggestions verified")
        } finally {
            playlist?.let { service.setPublished(it.id, false); service.unfollow(it.id) }
            val cache = CatalogStore(app)
            cache.saveLibrary(SpotifyLibrary(SpotifyAccount.transport, session, catalog).load(cache.loadLibrary()))
            service.close()
            Log.i("SpotifeurPlaylistTest", "test playlist removed and library cache refreshed")
        }
    }
}
