package fr.spotifeur.playback

import android.content.Intent
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlaybackSignalTest {
    @Test fun signalBeyondClearLeadAndAfterSeeks(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val session = SpotifyAccount.require(app)
        val tracks = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
            .savedTracks.filter { it.durationMs > 90_000 }.take(2)
        assertEquals(2, tracks.size)
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val native = NativeAudioProvider(app)
        val http = SpotifyHlsDataSource.Factory(app, nativeAudio = true)
        var player: ExoPlayer? = null
        suspend fun signal(label: String) {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(35_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val code = withContext(Dispatchers.Main) { player!!.playerError?.errorCodeName }
                    check(code == null) { "Native playback error=$code diagnostic=${PlaybackDiagnostics.lastError}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            Log.i("SpotifeurNativeTest", "$label PCM OK encrypted=${PlaybackDiagnostics.encryptedSamples.get()}")
        }
        try {
            withContext(Dispatchers.Main) {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = native.codecFactory
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                player = ExoPlayer.Builder(app, renderers).build().apply { addAnalyticsListener(PlaybackDiagnostics) }
            }
            for ((index, track) in tracks.withIndex()) {
                withContext(Dispatchers.Main) {
                    player!!.setMediaSource(HlsMediaSource.Factory(http).setExtractorFactory(NativeHlsExtractor(native))
                        .createMediaSource(MediaItem.fromUri(track.id)))
                    player!!.prepare(); player!!.play()
                }
                signal("start $index")
                delay(20_000)
                signal("continuous $index")
                for (fraction in listOf(0.2, 0.7, 0.5)) {
                    withContext(Dispatchers.Main) { player!!.seekTo((track.durationMs * fraction).toLong()) }
                    signal("seek $index/$fraction")
                }
            }
        } catch (error: Throwable) {
            Log.e("SpotifeurNativeTest", "Playback failure=" + generateSequence(error) { it.cause }.take(12).joinToString(">") {
                it.javaClass.simpleName + if (it is NativeAudioException) ":${it.nativeCode ?: 0}" else ""
            })
            throw error
        } finally {
            withContext(Dispatchers.Main) { player?.release(); activity.finish() }
            native.close(); http.close()
        }
    }
}
