package fr.spotifeur.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifyEntitlementsDiagnosticTest {
    @Test fun nativePlaybackAttributes(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val url = spotifyUrl("nebula/v1/configuration/attributes")
        val headers = session.headers(url.host)
        val response = SpotifyAccount.transport.send(Request.Builder().url(url)
            .header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
            .header("Accept", "application/x-protobuf").header("Cache-Control", "no-cache")
            .header("Spotify-App-Version", SpotifyAndroid.VERSION).header("X-Client-Id", SpotifyAndroid.CLIENT_ID)
            .header("App-Platform", "Android").build(), "Playback attributes")
        val whitelist = setOf("ads", "catalogue", "offline", "on-demand", "pick-and-shuffle", "player-license", "product", "streaming", "streaming-rules", "type", "audio-quality")
        Proto(response).messages(1).forEach { entry ->
            val name = entry.text(1) ?: return@forEach
            if (name !in whitelist) return@forEach
            val value = entry.message(2) ?: return@forEach
            val text = value.text(4)
            val safe = when {
                name == "player-license" -> "present"
                text in setOf("premium", "free", "unlimited", "family", "premium-duo", "premium-family", "true", "false", "0", "1", "2") -> text
                value.number(2) != null -> "bool:${value.number(2)}"
                value.number(3) != null -> "number:${value.number(3)}"
                else -> "present"
            }
            Log.i("SpotifeurAudioTest", "attribute $name=$safe")
        }
    }
}
