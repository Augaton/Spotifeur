package fr.spotifeur.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Spotify's recently played contexts, read with the account session. */
@RunWith(AndroidJUnit4::class)
class ListeningHistoryNetworkTest {
    @Test fun recentPlaysHaveTracksAndTimes(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyNetwork") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val plays = spotifyRecentPlays(SpotifyAccount.require(app))
        assertTrue(plays.isNotEmpty())
        assertTrue(plays.all { it.track.startsWith("spotify:track:") && it.playedAt > 1_500_000_000_000 })
        assertTrue(plays.zipWithNext().all { (a, b) -> a.playedAt >= b.playedAt })
        Log.i("SpotifeurHistory", "recent plays=${plays.size}")
    }
}
