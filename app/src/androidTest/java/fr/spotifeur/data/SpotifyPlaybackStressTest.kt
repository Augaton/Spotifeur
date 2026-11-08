package fr.spotifeur.data

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.playback.PlaybackDiagnostics
import fr.spotifeur.playback.SpotifyPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotifyPlaybackStressTest {
    @Test fun nativeQueueContinuesInBackground(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        assertTrue("Native runtime required", fr.spotifeur.BuildConfig.NATIVE_RUNTIME)
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val session = SpotifyAccount.require(app)
        val tracks = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
            .savedTracks.filter { it.durationMs > 90_000 }.take(2)
        assertEquals(2, tracks.size)
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = AtomicReference<SpotifyPlayer>()
        ins.runOnMainSync { holder.set(SpotifyPlayer(app, scope)) }
        val player = holder.get()
        try {
            withContext(Dispatchers.Main) { player.play(tracks) }
            withTimeout(35_000) { while (!player.state.value.playing) {
                check(player.state.value.error == null); delay(100)
            } }
            withContext(Dispatchers.Main) { player.seek(tracks[0].durationMs - 3000); activity.moveTaskToBack(true) }
            withTimeout(20_000) { while (player.state.value.index != 1 || !player.state.value.playing) {
                check(player.state.value.error == null); delay(100)
            } }
            delay(18_000)
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(15_000) { while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                check(player.state.value.error == null); delay(100)
            } }
            assertEquals(1, player.state.value.index)
            assertTrue(player.state.value.playing && player.state.value.positionMs > 15_000)
            assertTrue(android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            withContext(Dispatchers.Main) { player.toggle() }
            withTimeout(5000) { while (player.state.value.playing) delay(100) }
            withContext(Dispatchers.Main) { player.toggle() }
            withTimeout(5000) { while (!player.state.value.playing) delay(100) }
            Log.i("SpotifeurAudioTest", "Native automatic transition + background PCM + pause/resume OK")
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.close(); activity.finish() }
            scope.cancel()
        }
    }
    @Test fun inspectManifestFormats(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val track = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load().savedTracks.first()
        withContext(Dispatchers.IO) {
            SpotifyHlsDataSource.Factory(app).use { factory ->
                fun read(uri: android.net.Uri, position: Long = 0, length: Long = androidx.media3.common.C.LENGTH_UNSET.toLong()): Pair<android.net.Uri, ByteArray> {
                    val source = factory.createDataSource()
                    try {
                        source.open(androidx.media3.datasource.DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
                        val result = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) { val n = source.read(buffer, 0, buffer.size); if(n < 0) break; result.write(buffer, 0, n) }
                        return source.uri!! to result.toByteArray()
                    } finally { source.close() }
                }
                val (base, master) = read(android.net.Uri.parse(track.id))
                master.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEachIndexed { index, line ->
                    val (mediaBase, data) = read(androidx.media3.common.util.UriUtil.resolveToUri(base.toString(), line))
                    val playlist = data.toString(Charsets.UTF_8)
                    Log.i("SpotifeurAudioTest", "VARIANT $index")
                    playlist.lineSequence().filter { it.startsWith("#") }.map { it.replace(Regex("URI=\"[^\"]*\""), "URI=\"redacted\"") }.distinct().take(20).forEach { Log.i("SpotifeurAudioTest", it) }
                    Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(playlist)?.groupValues?.get(1)?.let { init ->
                        val bytes = read(androidx.media3.common.util.UriUtil.resolveToUri(mediaBase.toString(), init)).second
                        val file = androidx.media3.common.util.UriUtil.resolveToUri(mediaBase.toString(), init)
                        val ranges = Regex("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").findAll(playlist).take(3).toList()
                        for (range in ranges) {
                            val length = range.groupValues[1].toInt()
                            val position = range.groupValues[2].toInt()
                            val part = read(file, position.toLong(), length.toLong()).second
                            assertTrue("HTTP byte range $index/$position", bytes.copyOfRange(position, position + length).contentEquals(part))
                            Log.i("SpotifeurAudioTest", "range $index/$position OK, bytes=${part.size}")
                        }
                        val text = bytes.toString(Charsets.ISO_8859_1)
                        for (box in listOf("schm", "tenc")) {
                            val at = text.indexOf(box)
                            if(at >= 0) {
                                // Scheme and IV/pattern configuration, excluding the key identifier.
                                val length = if(box == "tenc") 11 else 16
                                Log.i("SpotifeurAudioTest", "$box=" + bytes.copyOfRange(at + 4, minOf(bytes.size, at + length)).joinToString("") { "%02x".format(it.toInt() and 255) })
                            }
                        }
                    }
                }
            }
        }
    }
    @Test fun decodedSignalAfterStartsAndSeeks(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val session = SpotifyAccount.require(app)
        val library = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
        val tracks = library.savedTracks.filter { it.durationMs > 90_000 }.take(2)
        assertEquals(2, tracks.size)
        withContext(Dispatchers.IO) {
            SpotifyHlsDataSource.Factory(app).use { factory ->
                val source = factory.createDataSource()
                try {
                    source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(tracks.first().id)))
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; bytes.write(buffer, 0, count) }
                    val manifest = bytes.toString("UTF-8")
                    manifest.lineSequence().filter { it.startsWith("#EXT-X-STREAM-INF") }.forEach {
                        Log.i("SpotifeurAudioTest", it)
                    }
                    manifest.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEachIndexed { index, line ->
                        val uri = androidx.media3.common.util.UriUtil.resolveToUri(source.uri.toString(), line)
                        Log.i("SpotifeurAudioTest", "variant $index path=" + uri.path.orEmpty().replace(Regex("[a-fA-F0-9]{16,}"), "ID"))
                    }
                } finally { source.close() }
            }
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = AtomicReference<SpotifyPlayer>()
        instrumentation.runOnMainSync { holder.set(SpotifyPlayer(app, scope)) }
        val player = holder.get()
        suspend fun signal(label: String, target: Int) {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            var ticks = 0
            withTimeout(12_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 15 || !player.state.value.playing || player.state.value.buffering || kotlin.math.abs(player.state.value.positionMs - target) > 15_000) {
                    player.state.value.error?.let { fail("$label: $it; ${PlaybackDiagnostics.lastError}") }
                    delay(100)
                    if (++ticks % 30 == 0) Log.i("SpotifeurAudioTest", "$label waiting target=$target pos=${player.state.value.positionMs} duration=${player.state.value.durationMs} playing=${player.state.value.playing} buffering=${player.state.value.buffering} signal=${PlaybackDiagnostics.nonSilentBuffers.get()} bytes=${PlaybackDiagnostics.decodedBytes.get()} outputs=${PlaybackDiagnostics.outputStarts.get()} encrypted=${PlaybackDiagnostics.encryptedSamples.get()} clear=${PlaybackDiagnostics.clearSamples.get()}")
                }
            }
            delay(700)
            assertTrue("$label: recent signal required", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2_000)
            Log.i("SpotifeurAudioTest", "$label OK, signal=${PlaybackDiagnostics.nonSilentBuffers.get()}, sorties=${PlaybackDiagnostics.outputStarts.get()}")
        }
        try {
            val failures = mutableListOf<String>()
            tracks.forEachIndexed { index, track ->
                withContext(Dispatchers.Main) { player.play(tracks, index) }
                try { signal("start $index", 0) } catch (e: Exception) { failures += "start $index" }
                delay(8_000)
                Log.i("SpotifeurAudioTest", "continuous $index signal=${PlaybackDiagnostics.nonSilentBuffers.get()} age=${android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get()}")
                for (fraction in listOf(0.2, 0.7, 0.5)) {
                    val target = (track.durationMs * fraction).toInt()
                    withContext(Dispatchers.Main) { player.seek(target) }
                    try { signal("seek $index/$fraction", target) } catch (e: Exception) { failures += "seek $index/$fraction" }
                }
            }
            assertTrue("No signal: $failures", failures.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { player.stop(); player.close() }
            scope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
