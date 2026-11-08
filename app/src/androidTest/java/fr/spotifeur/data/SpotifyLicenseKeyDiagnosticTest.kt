package fr.spotifeur.data

import android.media.MediaDrm
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Compares each manifest KEYID with the key identifiers and statuses the platform CDM reports
 * after a licence response. Only identifiers and status codes are read: the CDM keeps the keys.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpotifyLicenseKeyDiagnosticTest {
    @Test fun licenseKeysMatchManifest(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyAudioStress") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SpotifyAccount.require(app)
        val track = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load().savedTracks.first()
        val license = Uri.parse("https://spclient.wg.spotify.com/widevine-license/v1/audio/license")
        SpotifyHlsDataSource.Factory(app).use { http ->
            fun exchange(spec: DataSpec): Pair<Uri, ByteArray> {
                val source = http.createDataSource()
                try {
                    source.open(spec)
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; bytes.write(buffer, 0, n) }
                    return source.uri!! to bytes.toByteArray()
                } finally { source.close() }
            }
            val thread = HandlerThread("drm-diagnostic").apply { start() }
            try {
                val (masterBase, masterBytes) = exchange(DataSpec(Uri.parse(track.id)))
                val variants = String(masterBytes).lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
                Log.i(TAG, "variants=${variants.size}")
                variants.forEachIndexed { index, media ->
                    val (_, playlistBytes) = exchange(DataSpec(UriUtil.resolveToUri(masterBase.toString(), media)))
                    val key = String(playlistBytes).lineSequence().firstOrNull { it.startsWith("#EXT-X-KEY:") }
                    val method = key?.let { Regex("METHOD=([^,]+)").find(it)?.groupValues?.get(1) }
                    val manifestKid = key?.let { Regex("KEYID=0x([0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }?.lowercase()
                    val format = key?.let { Regex("KEYFORMAT=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                    Log.i(TAG, "variant=$index method=$method format=$format manifestKid=$manifestKid")
                    if (format?.contains(C.WIDEVINE_UUID.toString()) != true) return@forEachIndexed
                    val pssh = Base64.decode(Regex("URI=\"([^\"]+)\"").find(key)!!.groupValues[1].substringAfter(','), Base64.DEFAULT)
                    val drm = MediaDrm(C.WIDEVINE_UUID)
                    val statuses = CopyOnWriteArrayList<String>()
                    val received = CountDownLatch(1)
                    try {
                        drm.setPropertyString("securityLevel", "L3")
                        drm.setOnKeyStatusChangeListener({ _, _, keys, _ ->
                            keys.forEach { statuses += "${hex(it.keyId)}:${statusName(it.statusCode)}" }
                            received.countDown()
                        }, Handler(thread.looper))
                        val id = drm.openSession()
                        try {
                            val request = drm.getKeyRequest(id, pssh, "audio/mp4", MediaDrm.KEY_TYPE_STREAMING, null)
                            val (_, response) = exchange(DataSpec.Builder().setUri(license)
                                .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(request.data)
                                .setHttpRequestHeaders(mapOf("Content-Type" to "application/octet-stream")).build())
                            drm.provideKeyResponse(id, response)
                            received.await(5, TimeUnit.SECONDS)
                            val granted = statuses.map { it.substringBefore(':') }
                            Log.i(TAG, "variant=$index requestBytes=${request.data.size} responseBytes=${response.size} keys=$statuses manifestKidGranted=${manifestKid in granted}")
                            Log.i(TAG, "variant=$index query=${drm.queryKeyStatus(id)}")
                        } finally { drm.closeSession(id) }
                    } catch (e: Exception) {
                        Log.e(TAG, "variant=$index failure=${e.javaClass.simpleName}: ${e.message}")
                    } finally { drm.close() }
                }
            } finally { thread.quitSafely() }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun statusName(code: Int) = when (code) {
        MediaDrm.KeyStatus.STATUS_USABLE -> "USABLE"
        MediaDrm.KeyStatus.STATUS_EXPIRED -> "EXPIRED"
        MediaDrm.KeyStatus.STATUS_OUTPUT_NOT_ALLOWED -> "OUTPUT_NOT_ALLOWED"
        MediaDrm.KeyStatus.STATUS_PENDING -> "PENDING"
        MediaDrm.KeyStatus.STATUS_INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> code.toString()
    }
    private companion object { const val TAG = "SpotifeurKeyDiag" }
}
