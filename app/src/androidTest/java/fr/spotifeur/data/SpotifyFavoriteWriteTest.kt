package fr.spotifeur.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Explicit mutation check. The selected track is absent initially and removed in finally. */
@RunWith(AndroidJUnit4::class)
class SpotifyFavoriteWriteTest {
    @Test
    fun savesAndRestoresOnePreviouslyUnsavedTrack(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyFavoriteWrite") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val transport = SpotifyAccount.transport
        val catalog = SpotifyCatalog(transport, session)
        val library = SpotifyLibrary(transport, session, catalog)
        val initial = library.load().savedTrackUris.map(::identity).toHashSet()
        val search = SpotifySearch(transport, session)
        var candidate: Music? = null
        for (query in listOf("Daft Punk", "Nils Frahm", "Aphex Twin")) {
            candidate = search.search(query, Locale.FRANCE, null).items.firstOrNull {
                it.kind == MusicKind.Track && identity(it.id) !in initial
            }
            if (candidate != null) break
        }
        assumeTrue("No unsaved test track was found.", candidate != null)
        val track = checkNotNull(candidate)
        val uri = checkNotNull(canonicalMusicUri(track.id))
        try {
            library.setSaved(track, true)
            assertTrue("The added track must be present in the native collection.", awaitMembership(library, uri, true))
        } finally {
            withContext(NonCancellable) {
                library.setSaved(track, false)
                assertTrue("The test track must be removed to restore the collection.", awaitMembership(library, uri, false))
                Log.i("SpotifeurFavoriteTest", "Native add and remove confirmed, collection restored.")
            }
        }
    }

    private suspend fun awaitMembership(library: SpotifyLibrary, uri: String, expected: Boolean): Boolean {
        repeat(6) {
            if ((identity(uri) in library.load().savedTrackUris.map(::identity)) == expected) return true
            delay(300)
        }
        return false
    }

    private fun identity(uri: String): String {
        val canonical = canonicalMusicUri(uri) ?: return uri
        return canonical.substringBeforeLast(':') + ":" + canonical.substringAfterLast(':').padStart(22, '0')
    }
}
