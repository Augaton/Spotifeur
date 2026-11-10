package fr.spotifeur.data

import fr.spotifeur.data.diagnostics.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap

/**
 * The bitrate of each audio file seen in a master playlist, and what the key service answered for it.
 * A refused key for a file above 128 kbit/s lowers the account's limit, so the next preparation picks
 * a variant the account can play instead of stopping after the clear lead-in every time.
 */
internal object AudioGrant {
    private val playlistBandwidth = ConcurrentHashMap<String, Long>()
    private val fileBandwidth = ConcurrentHashMap<String, Long>()
    private val keysAccepted = ConcurrentHashMap.newKeySet<Long>()

    private fun key(url: String) = url.substringBefore('?')

    fun rememberVariant(playlistUrl: String, bandwidth: Long) {
        if (bandwidth > 0) playlistBandwidth[key(playlistUrl)] = bandwidth
    }

    fun rememberFile(playlistUrl: String, fileHex: String) {
        playlistBandwidth[key(playlistUrl)]?.let { fileBandwidth[fileHex.lowercase()] = it }
    }

    fun bandwidthOf(fileHex: String): Long? = fileBandwidth[fileHex.lowercase()]

    fun describe(fileHex: String): String = bandwidthOf(fileHex)?.let { "${it / 1000}k" } ?: "unknown"

    /** Every response of `playplay/v1/key/{file}`, from the native engine's HTTP thread. */
    fun onKeyResponse(fileHex: String, status: Int) {
        val bandwidth = bandwidthOf(fileHex)
        if (status == 200) {
            if (keysAccepted.add(bandwidth ?: 0)) DiagnosticLog.record("audio", "key accepted bitrate=${describe(fileHex)}")
            return
        }
        DiagnosticLog.record("audio", "key status=$status bitrate=${describe(fileHex)}")
        if (status in 400..499 && status != 429 && bandwidth != null && bandwidth > AudioQuality.NORMAL_BANDWIDTH) {
            SpotifyAccount.current()?.restrictAudioBandwidth(AudioQuality.NORMAL_BANDWIDTH, status)
        }
    }
}
