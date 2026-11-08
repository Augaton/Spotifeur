package fr.spotifeur.data

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.SpotifeurModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifySuggestionUpdateTest {
    @Test fun oneClickUpdatesThePlaylistAndStoredDetail(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyPlaylistWrite") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[SpotifeurModel::class.java] }
        val session = SpotifyAccount.require(app)
        var playlist: Music? = null
        try {
            withTimeout(30_000) { while (model.library.value.content == null) delay(100) }
            val seed = checkNotNull(model.library.value.content).savedTracks.first()
            playlist = withContext(Dispatchers.Main) { model.createSpotifyPlaylist("Spotifeur — immediate add test", listOf(seed), false) }
            val suggestion = model.playlistSuggestions(playlist, listOf(seed)).first()
            val write = async(Dispatchers.Main) { model.addSpotifyPlaylistTracks(playlist, listOf(suggestion)) }
            withTimeout(15_000) { while (model.details.value[playlist.id]?.content?.tracks?.size != 2) delay(20) }
            assertEquals(listOf(seed.id, suggestion.id), model.details.value[playlist.id]?.content?.tracks?.map { it.id })
            write.await()
            assertEquals(listOf(seed.id, suggestion.id), CatalogStore(app).loadDetail(playlist.id)?.tracks?.map { it.id })
            assertEquals(listOf(seed.id, suggestion.id), SpotifyCatalog(SpotifyAccount.transport, session).detail(playlist).tracks.map { it.id })
            Log.i("SpotifeurPlaylistTest", "One suggested track: visible immediately, persisted and confirmed on Spotify without a second click")
        } finally {
            playlist?.let { model.spotifyPlaylists.unfollow(it.id) }
            withContext(Dispatchers.Main) { activity.finish() }
            delay(500)
            val cache = CatalogStore(app)
            cache.saveLibrary(SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load(cache.loadLibrary()))
        }
    }
}
