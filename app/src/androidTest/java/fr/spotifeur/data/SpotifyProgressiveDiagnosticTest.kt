package fr.spotifeur.data

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.playback.PlaybackDiagnostics
import fr.spotifeur.playback.SignalProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolates HLS segmentation from the same encrypted MP4 and platform CDM. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotifyProgressiveDiagnosticTest {
    @Test fun referenceWidevineSignal(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var player: ExoPlayer? = null
        val start = PlaybackDiagnostics.nonSilentBuffers.get()
        try {
            withContext(Dispatchers.Main) {
                val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                val drm = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid -> FrameworkMediaDrm.newInstance(uuid).apply { setPropertyString("securityLevel", "L3") } }
                    .build(HttpMediaDrmCallback("https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test", true, http))
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                player = ExoPlayer.Builder(app, renderers).build().apply {
                    addAnalyticsListener(PlaybackDiagnostics)
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    setMediaSource(androidx.media3.exoplayer.dash.DashMediaSource.Factory(http).setDrmSessionManagerProvider { drm }.createMediaSource(MediaItem.fromUri("https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd")))
                    prepare(); play()
                }
            }
            delay(20_000)
            assertNull(withContext(Dispatchers.Main) { player!!.playerError?.errorCodeName })
            Log.i("SpotifeurAudioTest", "reference signal=${PlaybackDiagnostics.nonSilentBuffers.get() - start} age=${android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get()}")
            assertTrue("Widevine reference signal", PlaybackDiagnostics.nonSilentBuffers.get() > start + 50 && android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2_000)
        } finally { withContext(Dispatchers.Main) { player?.release(); activity.finish() } }
    }
    @Test fun continuousMp4Signal(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val session = SpotifyAccount.require(app)
        val track = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load().savedTracks.first()
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SpotifyHlsDataSource.Factory(app).use { http ->
            fun read(uri: Uri): Pair<Uri, String> {
                val source = http.createDataSource()
                try {
                    source.open(DataSpec(uri))
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while(true) { val n = source.read(buffer, 0, buffer.size); if(n < 0) break; bytes.write(buffer, 0, n) }
                    return source.uri!! to bytes.toString("UTF-8")
                } finally { source.close() }
            }
            val (file, init) = withContext(Dispatchers.IO) {
                val (masterBase, master) = read(Uri.parse(track.id))
                val media = master.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }
                val (mediaBase, playlist) = read(UriUtil.resolveToUri(masterBase.toString(), media))
                val map = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
                val data = Regex("#EXT-X-KEY:[^\\n]*URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
                val hlsPssh = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
                val fileId = mediaBase.pathSegments.let { it[it.lastIndex - 1] }
                val table = org.json.JSONObject(read(Uri.parse("https://seektables.scdn.co/seektable/$fileId.json")).second)
                Log.i("SpotifeurAudioTest", "seektable keys=" + table.keys().asSequence().joinToString(","))
                for (name in listOf("pssh", "pssh_widevine")) {
                    val value = table.optString(name)
                    val decoded = Base64.decode(value, Base64.DEFAULT)
                    Log.i("SpotifeurAudioTest", "$name size=${decoded.size} uuid=${androidx.media3.extractor.mp4.PsshAtomUtil.parseUuid(decoded)} version=${androidx.media3.extractor.mp4.PsshAtomUtil.parseVersion(decoded)} match=${decoded.contentEquals(hlsPssh)}")
                }
                val alternative = table.optString("pssh_widevine", table.optString("pssh", ""))
                val pssh = if(alternative.isBlank()) hlsPssh else Base64.decode(alternative, Base64.DEFAULT)
                Log.i("SpotifeurAudioTest", "PSSH match=" + hlsPssh.contentEquals(pssh))
                UriUtil.resolveToUri(mediaBase.toString(), map) to DrmInitData("cenc", DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, pssh))
            }
            val extractors = ExtractorsFactory {
                val actual = FragmentedMp4Extractor(0, TimestampAdjuster(0))
                arrayOf(object : Extractor by actual {
                    override fun init(output: ExtractorOutput) {
                        actual.init(object : ExtractorOutput by output {
                            override fun track(id: Int, type: Int): TrackOutput {
                                val target = output.track(id, type)
                                return object : TrackOutput by target {
                                    override fun format(format: Format) { target.format(format.buildUpon().setDrmInitData(init).build()) }
                                }
                            }
                        })
                    }
                })
            }
            var player: ExoPlayer? = null
            try {
                withContext(Dispatchers.Main) {
                    val drm = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid -> FrameworkMediaDrm.newInstance(uuid).apply { setPropertyString("securityLevel", "L3") } }
                        .build(HttpMediaDrmCallback("https://spclient.wg.spotify.com/widevine-license/v1/audio/license", false, http))
                    val renderers = object : DefaultRenderersFactory(app) {
                        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                    }
                    player = ExoPlayer.Builder(app, renderers).build().apply {
                        addAnalyticsListener(PlaybackDiagnostics)
                        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                        setMediaSource(ProgressiveMediaSource.Factory(http, extractors).setDrmSessionManagerProvider { drm }.createMediaSource(MediaItem.fromUri(file)))
                        prepare(); play()
                    }
                }
                delay(18_000)
                val error = withContext(Dispatchers.Main) { player!!.playerError?.errorCodeName }
                assertNull(error)
                Log.i("SpotifeurAudioTest", "progressive signal=${PlaybackDiagnostics.nonSilentBuffers.get()} age=${android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get()}")
                assertTrue("Signal after the clear lead", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2_000)
            } finally { withContext(Dispatchers.Main) { player?.release(); activity.finish() } }
        }
    }
}
