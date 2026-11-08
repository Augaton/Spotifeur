package fr.spotifeur.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotifyNativeRuntimeTest {
    @Test fun authenticatedMediaHandle(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val session = SpotifyAccount.require(app)
            val track = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load().savedTracks.first()
            var manifestId = ""
            var key = withContext(Dispatchers.IO) {
                SpotifyHlsDataSource.Factory(app).use { factory ->
                    fun read(uri: Uri, cap: Int = 64 * 1024): Pair<Uri, ByteArray> {
                        val source = factory.createDataSource()
                        try {
                            source.open(DataSpec(uri))
                            val bytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (bytes.size() < cap) {
                                val count = source.read(buffer, 0, minOf(buffer.size, cap - bytes.size()))
                                if (count < 0) break
                                bytes.write(buffer, 0, count)
                            }
                            return source.uri!! to bytes.toByteArray()
                        } finally { source.close() }
                    }
                    val masterUrl = factory.manifest(track.id).replace("/hls/1/", "/hls/4/")
                    manifestId = masterUrl.substringBeforeLast('/').substringAfterLast('/')
                    val (base, master) = read(Uri.parse(masterUrl))
                    val variant = master.toString(Charsets.UTF_8).lineSequence().first { it.isNotBlank() && !it.startsWith("#") }
                    val (mediaBase, media) = read(UriUtil.resolveToUri(base.toString(), variant))
                    val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(media.toString(Charsets.UTF_8))!!.groupValues[1]
                    val bytes = read(UriUtil.resolveToUri(mediaBase.toString(), init)).second
                    val at = bytes.toString(Charsets.ISO_8859_1).indexOf("tenc")
                    assertTrue("Track encryption box", at >= 0)
                    NativeAudioProtocol.keyId(bytes.copyOfRange(at + 12, at + 28))
                }
            }
            val runtime = SpotifyNativeRuntime.open(app)
            Log.i("SpotifeurNativeTest", "Authenticated native session created")
            try {
                val manifest = runtime.manifest(manifestId)
                Log.i("SpotifeurNativeTest", "Native manifest error=${manifest.number(4) ?: 0} bytes=${manifest.bytes(1)?.size ?: 0}")
                val text = manifest.bytes(1)?.decodeToString().orEmpty()
                Log.i("SpotifeurNativeTest", "Manifest HLS=${text.startsWith("#EXTM3U")} variants=${text.lineSequence().count { it.startsWith("#EXT-X-STREAM-INF") }} media=${text.contains("#EXTINF")}")
                text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach {
                    val uri = Uri.parse(it)
                    Log.i("SpotifeurNativeTest", "Native variant scheme=${uri.scheme} api=${uri.host == "spclient.wg.spotify.com"} segmentLengths=${uri.pathSegments.map(String::length)} hls=${uri.path?.contains("/hls/")} isAudioManifest=${uri.lastPathSegment == "audio.m3u8"}")
                }
                val variant = UriUtil.resolve(manifest.text(5) ?: "https://spclient.wg.spotify.com/sneaktables/v2/hls/4/$manifestId/master.m3u8",
                    text.lineSequence().first { it.isNotBlank() && !it.startsWith("#") })
                val fileId = Uri.parse(variant).pathSegments.first { it.length == 40 && it.all { c -> c.digitToIntOrNull(16) != null } }
                val nativeVariant = runtime.manifest(fileId)
                Log.i("SpotifeurNativeTest", "Native media manifest error=${nativeVariant.number(4) ?: 0} bytes=${nativeVariant.bytes(1)?.size ?: 0}")
                val playlist = checkNotNull(nativeVariant.bytes(1)).decodeToString()
                val manifestKeys = mutableListOf<String>()
                playlist.lineSequence().filter { it.startsWith("#EXT-X-KEY:") }.forEach { line ->
                    val format = Regex("KEYFORMAT=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1)
                    val keyId = Regex("KEYID=(?:\"?)(?:0x)?([a-fA-F0-9]{32})").find(line)?.groupValues?.get(1)?.lowercase()
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    Log.i("SpotifeurNativeTest", "Key metadata method=$method format=$format keyIdPresent=${keyId != null} uriScheme=${uri?.let(Uri::parse)?.scheme} uriLength=${uri?.length} matchesFile=${uri == fileId} keyMatchesFilePrefix=${keyId == fileId.take(32)}")
                    keyId?.let(manifestKeys::add)
                }
                val base = Uri.parse(nativeVariant.text(5) ?: variant)
                val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(checkNotNull(nativeVariant.bytes(1)).decodeToString())!!.groupValues[1]
                val fileUri = UriUtil.resolveToUri(base.toString(), init)
                val streamer = runtime.request("CreateSegmentFileStreamer", proto {
                    writeString(1, fileUri.toString()); writeString(2, runtime.playbackId); writeString(3, runtime.sessionId); writeEnum(4, 1); writeEnum(5, 0)
                })
                val streamerId = checkNotNull(streamer.number(1))
                Log.i("SpotifeurNativeTest", "Native segment streamer available=${streamerId != 0}")
                val dataReady = CompletableDeferred<Unit>()
                val path = fileUri.encodedPath.orEmpty() + (fileUri.encodedQuery?.let { "?$it" } ?: "")
                val download = runtime.stream("RequestSegmentData", proto {
                    writeByteArray(1, proto { writeInt32(1, streamerId) })
                    writeString(2, path)
                    writeString(3, fileUri.buildUpon().encodedPath("/").clearQuery().build().toString())
                    writeInt64(4, 0); writeInt64(5, 65536)
                }, { packet ->
                    val response = Proto(packet)
                    val error = response.number(4) ?: 0
                    val size = response.bytes(5)?.size ?: 0
                    Log.i("SpotifeurNativeTest", "Native segment status=$error bytes=$size final=${response.number(3)}")
                    if (error != 0) dataReady.completeExceptionally(IllegalStateException("Segment preparation failed"))
                    else if (size > 0) dataReady.complete(Unit)
                }, { dataReady.completeExceptionally(it) })
                try { withTimeout(35_000) { dataReady.await() } } finally { download.close() }
                if (Uri.parse(variant).scheme == "https") key = withContext(Dispatchers.IO) {
                    SpotifyHlsDataSource.Factory(app).use { factory ->
                        fun read(uri: Uri): Pair<Uri, ByteArray> {
                            val source = factory.createDataSource()
                            try {
                                source.open(DataSpec(uri))
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (bytes.size() < 65536) {
                                    val n = source.read(buffer, 0, minOf(buffer.size, 65536 - bytes.size()))
                                    if (n < 0) break
                                    bytes.write(buffer, 0, n)
                                }
                                return source.uri!! to bytes.toByteArray()
                            } finally { source.close() }
                        }
                        val base = Uri.parse(nativeVariant.text(5) ?: variant)
                        val data = checkNotNull(nativeVariant.bytes(1))
                        val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(data.decodeToString())!!.groupValues[1]
                        val bytes = read(UriUtil.resolveToUri(base.toString(), init)).second
                        val at = bytes.toString(Charsets.ISO_8859_1).indexOf("tenc")
                        check(at >= 0)
                        NativeAudioProtocol.keyId(bytes.copyOfRange(at + 12, at + 28)).also {
                            Log.i("SpotifeurNativeTest", "Native variant matches previous key=${it == key}")
                            Log.i("SpotifeurNativeTest", "Manifest key count=${manifestKeys.size} matchesTrack=${manifestKeys.any { candidate -> candidate == it }}")
                        }
                    }
                }
                if (manifestKeys.isNotEmpty()) key = manifestKeys.first()
                delay(3_000)
                val ready = CompletableDeferred<Int>()
                val subscription = runtime.subscribe(fileId, { result ->
                    Log.i("SpotifeurNativeTest", "Media handle status=$result")
                    when (result) {
                        is MediaHandleResult.Ready -> ready.complete(result.handle)
                        MediaHandleResult.Failed, MediaHandleResult.Sunset -> ready.completeExceptionally(IllegalStateException("Media handle unavailable"))
                        else -> Unit
                    }
                }, { ready.completeExceptionally(it) })
                try { assertNotEquals(0, withTimeout(35_000) { ready.await() }) }
                finally { subscription.close() }
            } finally { withContext(Dispatchers.IO) { runtime.close() } }
        } catch (failure: Throwable) {
            Log.e("SpotifeurNativeTest", "Failure chain=" + generateSequence(failure) { it.cause }.take(10).joinToString(">") { it.javaClass.simpleName })
            throw AssertionError("Native runtime diagnostic failed; inspect sanitized stage logs")
        }
    }
}
