package fr.spotifeur.data

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a real idle TLS connection, which used to throw from Service.onDestroy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotifyTransportLifecycleTest {
    @Test fun tlsConnectionCanBeReleasedFromMainThread(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val factory = SpotifyHlsDataSource.Factory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            withContext(Dispatchers.IO) {
                val source = factory.createDataSource()
                try {
                    source.open(DataSpec(Uri.parse("https://www.gstatic.com/generate_204")))
                    val bytes = ByteArray(256)
                    while(source.read(bytes, 0, bytes.size) >= 0) { }
                } finally { source.close() }
            }
            withContext(Dispatchers.Main) { factory.close(); factory.close() }
            delay(1_000)
        } finally { factory.close() }
    }
}
